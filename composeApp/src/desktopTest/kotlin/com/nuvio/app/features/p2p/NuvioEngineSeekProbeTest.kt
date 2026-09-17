package com.nuvio.app.features.p2p

import com.nuvio.engine.NuvioEngine
import com.nuvio.engine.internal.NuvioEngineLibrary
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import kotlin.test.Test
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Opt-in: reproduces mpv's access pattern against the app's REAL engine state so a stall seen in
 * the app can be replayed without the app. `-Pnuvio.p2pSeekProbeHash=<hash>` plus
 * `-Pnuvio.p2pSeekProbeOffsetsMb=0,tail,522` (comma list; "tail" = last 4 MiB). Each offset opens
 * an HTTP range request like ffmpeg does, reads up to 8 MiB, and reports how long the first byte
 * took and what the engine's demand window looked like meanwhile. Close Nuvio first: two engines
 * on one payload directory fight over the files.
 */
class NuvioEngineSeekProbeTest {
    @Test
    fun replaySeeks() {
        val hash = System.getProperty("nuvio.p2pSeekProbeHash")?.trim().orEmpty()
        if (hash.isEmpty() || !NuvioEngineLibrary.isAvailable) {
            println("seek probe skipped"); return
        }
        val offsets = System.getProperty("nuvio.p2pSeekProbeOffsetsMb")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("0")
        val root = File(System.getenv("LOCALAPPDATA"), "NuvioHTPC/nuvio-engine")
        val engine = NuvioEngine.create(
            buildNuvioEngineConfig(
                stateDirectory = root.resolve("state"),
                cacheDirectory = root.resolve("payload"),
                uploadEnabled = true,
                torrentProfile = P2pTorrentProfile.FAST,
                diskCacheCapacityBytes = 2L * 1024 * 1024 * 1024,
            ),
        )
        val startedAt = System.nanoTime()
        fun t() = (System.nanoTime() - startedAt) / 1_000_000
        try {
            runBlocking {
                val torrentId = engine.addMagnet(buildP2pMagnetUri(hash, listOf("udp://tracker.opentrackr.org:1337/announce")))
                val stream = engine.prepareStream(torrentId)
                println("STREAM after ${t()}ms size=${stream.fileSize} url=${stream.url}")
                val sampler = launch(kotlinx.coroutines.Dispatchers.Default) {
                    while (true) {
                        val r = runCatching { engine.currentStreamStats(stream.id) }.getOrNull()
                        val s = engine.stats.value
                        println("t=${t()}ms peers=${s.connectedPeers} seeds=${s.connectedSeeds} downBps=${s.downloadRateBytesPerSecond} " +
                            "contig=${r?.contiguousReadyBytes} delivered=${r?.deliveredBytes} blocking=${r?.blockingPieces}/${r?.primaryBlockingPiece} " +
                            "demand=${r?.primaryDemandStart}-${r?.primaryDemandEnd} secondary=${r?.secondaryDemandStart}-${r?.secondaryDemandEnd} " +
                            "scheduled=${r?.scheduledPieces} lastReady=${r?.lastReadyPiece}")
                        delay(1_000)
                    }
                }
                for (spec in offsets) {
                    val offset = if (spec == "tail") stream.fileSize - 4L * 1024 * 1024 else spec.toLong() * 1024 * 1024
                    val piece = offset / (16L * 1024 * 1024)
                    println("REQUEST offset=${offset / 1_000_000}MB (piece ~$piece) at t=${t()}ms")
                    val conn = URI(stream.url).toURL().openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000; conn.readTimeout = 90_000
                    conn.setRequestProperty("Range", "bytes=$offset-")
                    val reqStart = t()
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    try {
                        val code = conn.responseCode
                        val input = conn.inputStream
                        val buf = ByteArray(256 * 1024); var total = 0L; var firstAt = -1L
                        while (total < 8L * 1024 * 1024) {
                            val n = input.read(buf); if (n < 0) break
                            if (firstAt < 0) firstAt = t() - reqStart
                            total += n
                        }
                        println("RESPONSE $code offset=${offset / 1_000_000}MB firstByte=${firstAt}ms read=${total / 1_000_000}MB in ${t() - reqStart}ms")
                    } catch (e: Exception) {
                        println("RESPONSE FAILED offset=${offset / 1_000_000}MB after ${t() - reqStart}ms: $e")
                    } finally { conn.disconnect() }
                    }
                }
                sampler.cancel()
                engine.stopStream(stream.id)
            }
        } finally { engine.close() }
    }
}
