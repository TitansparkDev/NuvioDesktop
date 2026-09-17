package com.nuvio.app.core.ui

import coil3.BitmapImage
import coil3.asImage
import coil3.memory.MemoryCache
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The memory-cache half of honouring a poster provider's `Cache-Control`: Coil never expires a
 * memory entry on its own, so the wrapper has to.
 */
class DesktopArtworkMemoryCacheTest {

    private val now = 1_000_000_000_000L
    private var clock = now
    private val delegate = MemoryCache.Builder().maxSizeBytes(4L * 1024 * 1024).build()
    private val cache = DesktopArtworkMemoryCache(delegate)

    @BeforeTest
    fun useTestClock() {
        ImageFreshnessRegistry.clear()
        ImageFreshnessRegistry.nowMillis = { clock }
    }

    @AfterTest
    fun restoreClock() {
        ImageFreshnessRegistry.nowMillis = System::currentTimeMillis
        ImageFreshnessRegistry.clear()
    }

    @Test
    fun `a value is served until its deadline and is a miss after it`() {
        ImageFreshnessRegistry.record(URL, expiresAtMillis = now + 1_000L, bodyChanged = true)
        cache[key("card")] = value(URL)

        clock = now + 999L
        assertNotNull(cache[key("card")])

        clock = now + 1_000L
        assertNull(cache[key("card")], "expired: the request must fall through to the disk cache")
        assertNull(delegate[key("card")], "and the bitmap is released rather than kept for nothing")
    }

    @Test
    fun `a new body retires every size decoded from the old one`() {
        ImageFreshnessRegistry.record(URL, expiresAtMillis = now + 86_400_000L, bodyChanged = true)
        cache[key("card")] = value(URL)
        cache[key("hero")] = value(URL)

        // The card triggered a refresh that brought new bytes; only the card was re-decoded.
        ImageFreshnessRegistry.record(URL, expiresAtMillis = now + 86_400_000L, bodyChanged = true)
        cache[key("card")] = value(URL)

        assertNotNull(cache[key("card")])
        assertNull(cache[key("hero")], "decoded from the superseded bytes")
    }

    @Test
    fun `a 304 keeps every size`() {
        ImageFreshnessRegistry.record(URL, expiresAtMillis = now + 1_000L, bodyChanged = true)
        cache[key("card")] = value(URL)
        cache[key("hero")] = value(URL)

        // Revalidated: the deadline moved, the bytes did not.
        ImageFreshnessRegistry.record(URL, expiresAtMillis = now + 90_000L, bodyChanged = false)
        clock = now + 5_000L

        assertNotNull(cache[key("card")])
        assertNotNull(cache[key("hero")])
    }

    @Test
    fun `values the strategy never saw are left to Coil's own rules`() {
        val untracked = value(diskCacheKey = null)
        cache[key("resource")] = untracked
        val unknownKey = value("https://example.invalid/never-fetched.png")
        cache[key("unknown")] = unknownKey

        clock = now + 365L * 86_400_000L
        assertEquals(untracked, cache[key("resource")])
        assertEquals(unknownKey, cache[key("unknown")])
    }

    @Test
    fun `animated images are still never stored`() {
        val image = SkiaAnimatedImage(width = 1, height = 1, frames = emptyList(), frameDurationsMs = IntArray(0))
        cache[key("anim")] = MemoryCache.Value(image)
        assertTrue(delegate.keys.isEmpty())
    }

    private fun key(size: String) = MemoryCache.Key(URL, mapOf("nuvio:artwork-size" to size))

    private fun value(diskCacheKey: String?): MemoryCache.Value {
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo(1, 1, ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        }
        val image: BitmapImage = bitmap.asImage()
        val extras = if (diskCacheKey == null) emptyMap() else mapOf("coil#disk_cache_key" to diskCacheKey)
        return MemoryCache.Value(image, extras)
    }

    private companion object {
        const val URL = "https://meta.example/poster-cache/proxy/poster/movie/tt1?x=1"
    }
}
