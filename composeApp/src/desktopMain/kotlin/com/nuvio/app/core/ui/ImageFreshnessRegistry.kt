package com.nuvio.app.core.ui

/**
 * When each cached image stops being fresh, so the memory cache can stop serving it.
 *
 * `ImageDiskCacheStrategy` already honours `Cache-Control` for the *disk* cache — but Coil only
 * consults the disk (and therefore the strategy) on a memory-cache miss, and its memory cache has
 * no notion of expiry at all. On a phone that is fine: the process is killed daily. This is an
 * HTPC that stays open for a week, so a poster whose provider said "keep this for a day" (a
 * trending sash, an "airing" badge) would sit in memory, decoded, for the whole week and never
 * be asked for again. The strategy therefore records the freshness it computes here, keyed the
 * way Coil keys its own memory-cache values, and [DesktopArtworkMemoryCache] treats an entry past
 * its time as a miss — which sends the request back through the disk cache and the strategy,
 * exactly as a cold start would.
 *
 * Keys are Coil disk-cache keys (`options.diskCacheKey ?: url`): that is what the strategy sees
 * and what Coil stores on every memory-cache value it writes, so the two sides agree without
 * either having to know how the request was keyed. They are held hashed, because an AIOMetadata
 * poster URL runs to 2.5 KB and thirty thousand of them is not a table worth keeping.
 *
 * The body version is what retires the *other* sizes. A poster is decoded once per destination
 * size (card, hero, hover preview — see `DesktopArtworkSizeInterceptor`), each under its own
 * memory key. When a refresh brings new bytes, the size that triggered it is re-decoded; the rest
 * would keep serving bitmaps decoded from the superseded body until evicted. Every value is
 * tagged with the version current when it was stored, so one changed body invalidates all of them.
 */
internal object ImageFreshnessRegistry {

    class Freshness(val expiresAtMillis: Long, val bodyVersion: Int)

    /** The clock everything freshness-related reads, so tests can move time rather than sleep. */
    @Volatile
    internal var nowMillis: () -> Long = System::currentTimeMillis

    private val entries = object : LinkedHashMap<Long, Freshness>(1024, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Freshness>?): Boolean =
            size > MAX_ENTRIES
    }

    @Synchronized
    fun get(diskCacheKey: String): Freshness? = entries[hash(diskCacheKey)]

    /**
     * Records that [diskCacheKey] is fresh until [expiresAtMillis]. [bodyChanged] is true when the
     * bytes on disk were replaced (a 200), false when only the freshness moved (served from disk,
     * a 304, a stale copy kept after a failed refresh).
     */
    @Synchronized
    fun record(diskCacheKey: String, expiresAtMillis: Long, bodyChanged: Boolean) {
        val key = hash(diskCacheKey)
        val previous = entries[key]
        val version = when {
            previous == null -> 1
            bodyChanged -> previous.bodyVersion + 1
            else -> previous.bodyVersion
        }
        entries[key] = Freshness(expiresAtMillis, version)
    }

    @Synchronized
    fun clear() = entries.clear()

    /** FNV-1a. 64 bits is collision-free at this table size; a String key would be the URL itself. */
    private fun hash(key: String): Long {
        var hash = -0x340d631b7bdddcdbL
        for (char in key) {
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    private const val MAX_ENTRIES = 32_768
}
