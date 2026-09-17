package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.io.path.exists

/**
 * Removes the failure responses that [ImageDiskCacheStrategy]'s predecessor wrote to disk.
 *
 * Coil's default strategy caches 404s (and 300/301/405/410/414/501) alongside real images and then
 * serves them back forever, so every install that has been running for a while holds a set of URLs
 * that can never load again — see the file comment on [ImageDiskCacheStrategy] for how an
 * unpublished episode still ends up in there. The new strategy stops serving them, but only a purge
 * gets them off the disk and stops them occupying the cache budget.
 *
 * Runs once per cache directory, marked by a sentinel file inside it. If the cache is deleted the
 * purge simply runs again on the new one, which is correct and costs nothing.
 *
 * Must run **before** the [coil3.disk.DiskCache] is built: it edits the journal directly, which is
 * only safe while nothing else has it open.
 */
internal object ImageDiskCachePurge {

    private val log = Logger.withTag("ImageCachePurge")

    fun purgeCachedFailures(cacheDir: Path) {
        runCatching { purge(cacheDir) }
            .onFailure { error ->
                // A cache that could not be tidied is not a reason to fail to start. The strategy
                // already refuses to serve these entries, so the only cost is the disk they hold.
                log.w(error) { "Cached-failure purge failed; leaving the image cache as it is" }
            }
    }

    private fun purge(cacheDir: Path) {
        val marker = cacheDir.resolve(MARKER_FILE_NAME)
        if (marker.exists()) return

        val journal = cacheDir.resolve(JOURNAL_FILE_NAME)
        if (!cacheDir.exists() || !journal.exists()) {
            // Nothing has been cached yet, so there is nothing to purge and never will be by the
            // old rules. Mark it done so a fresh install does not scan an empty directory forever.
            markDone(marker)
            return
        }

        val startedAtMillis = System.currentTimeMillis()
        val candidateKeys = readSmallBodiedEntryKeys(journal)
        val failedEntryKeys = candidateKeys.filter { key ->
            val code = readResponseCode(cacheDir.resolve(key + METADATA_SUFFIX))
            code != null && code !in 200 until 300
        }

        if (failedEntryKeys.isEmpty()) {
            markDone(marker)
            log.i { "Image cache clean: ${candidateKeys.size} candidate(s) checked, no cached failures" }
            return
        }

        // Journal first, files second. The journal is what Coil believes; an entry it still lists
        // as CLEAN whose files have gone is a broken read, while a file left behind by a journal
        // line we already removed is only wasted disk.
        appendRemoveLines(journal, failedEntryKeys)

        var deletedFiles = 0
        for (key in failedEntryKeys) {
            if (deleteIfExists(cacheDir.resolve(key + METADATA_SUFFIX))) deletedFiles++
            if (deleteIfExists(cacheDir.resolve(key + BODY_SUFFIX))) deletedFiles++
        }

        markDone(marker)
        log.i {
            "Purged ${failedEntryKeys.size} cached failure(s) from ${candidateKeys.size} candidate " +
                "entries ($deletedFiles files) in ${System.currentTimeMillis() - startedAtMillis}ms"
        }
    }

    /**
     * The cache keys worth opening, taken from the journal rather than from a directory listing.
     *
     * A full cache holds tens of thousands of entries and opening every metadata file to read three
     * bytes measured 1.7 seconds on a 20,000-file cache. The journal already records both file
     * lengths per entry, and a cached failure's body is an error string — 13 to 95 bytes on the
     * cache this was built against. Filtering on the recorded body length first left 394 files to
     * open instead of 10,418, and the bound is generous enough for an HTML error page.
     *
     * The journal is replayed the way DiskLruCache replays it — later lines win — so an entry that
     * was rewritten or evicted is seen in its final state.
     */
    private fun readSmallBodiedEntryKeys(journal: Path): List<String> {
        val bodyLengthByKey = LinkedHashMap<String, Long>()
        Files.newBufferedReader(journal, StandardCharsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val parts = line.split(' ')
                val key = parts.getOrNull(1)?.takeIf(String::isNotEmpty) ?: continue
                when (parts[0]) {
                    CLEAN -> {
                        val bodyLength = parts.getOrNull(3)?.toLongOrNull() ?: continue
                        bodyLengthByKey[key] = bodyLength
                    }
                    REMOVE -> bodyLengthByKey.remove(key)
                    else -> Unit // DIRTY and READ say nothing about what is on disk.
                }
            }
        }
        return bodyLengthByKey
            // Zero included: one of the cached 404s this was built against had an empty body. An
            // entry whose *metadata* is empty is the one to leave alone, and readResponseCode
            // already declines those by finding no code in it.
            .filterValues { bodyLength -> bodyLength in 0..FAILURE_BODY_MAX_BYTES }
            .keys
            .toList()
    }

    /**
     * The response code Coil stored for this entry, or null if the file is unreadable or empty.
     *
     * `CacheNetworkResponse` writes the code as the first line, so only the first few bytes are
     * read. An empty metadata file is Coil's marker for an entry written to the cache by hand; it
     * has no code and must be left alone.
     */
    private fun readResponseCode(metadataFile: Path): Int? = runCatching {
        val head = ByteArray(FIRST_LINE_BYTES)
        val read = Files.newInputStream(metadataFile).use { input -> input.read(head) }
        if (read <= 0) return@runCatching null
        val newline = head.indexOf('\n'.code.toByte())
        val length = if (newline in 0 until read) newline else read
        String(head, 0, length, StandardCharsets.UTF_8).trim().toIntOrNull()
    }.getOrNull()

    private fun appendRemoveLines(journal: Path, keys: List<String>) {
        val lines = keys.joinToString(separator = "") { key -> "$REMOVE $key\n" }
        Files.newOutputStream(journal, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
            .use { output ->
                output.write(lines.toByteArray(StandardCharsets.UTF_8))
                output.flush()
            }
    }

    private fun deleteIfExists(file: Path): Boolean =
        runCatching { Files.deleteIfExists(file) }.getOrDefault(false)

    private fun markDone(marker: Path) {
        runCatching { Files.createDirectories(marker.parent) }
        runCatching { Files.write(marker, ByteArray(0)) }
    }

    private const val MARKER_FILE_NAME = ".nuvio-cached-failure-purge-v1"
    private const val JOURNAL_FILE_NAME = "journal"
    private const val METADATA_SUFFIX = ".0"
    private const val BODY_SUFFIX = ".1"

    /** DiskLruCache's own journal verbs. `REMOVE <key>`, replayed, drops the entry. */
    private const val CLEAN = "CLEAN"
    private const val REMOVE = "REMOVE"

    /** Comfortably above any error body, comfortably below any artwork worth keeping. */
    private const val FAILURE_BODY_MAX_BYTES = 16L * 1024

    /** A three-digit code and a newline; 16 is generous and still one read. */
    private const val FIRST_LINE_BYTES = 16
}
