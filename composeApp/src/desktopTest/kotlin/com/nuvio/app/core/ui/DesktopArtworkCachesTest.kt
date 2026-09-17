package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.ImageBitmap
import coil3.Image
import coil3.memory.MemoryCache
import org.jetbrains.skia.Canvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DesktopArtworkCachesTest {
    private fun cache(bytes: Long = 128L * 1024 * 1024) = MemoryCache.Builder()
        .maxSizeBytes(bytes).weakReferencesEnabled(false).build()

    private fun image(bytes: Long) = object : Image {
        override val width = 1
        override val height = 1
        override val size = bytes
        override val shareable = true
        override fun draw(canvas: Canvas) = Unit
    }

    @Test
    fun `playback evicts old entries and bounds late inserts then restores browsing`() {
        val transitions = mutableListOf<Boolean>()
        val memory = cache()
        val policy = DesktopArtworkCacheCoordinator(transitions::add, {})
        policy.register(memory)
        val entrySize = 24L * 1024 * 1024
        repeat(4) { memory[MemoryCache.Key("$it")] = MemoryCache.Value(image(entrySize)) }
        val recent = memory[MemoryCache.Key("3")]?.image
        val session = policy.beginPlayback()
        assertEquals(PlaybackArtworkCacheBytes, memory.maxSize)
        assertTrue(memory.size <= PlaybackArtworkCacheBytes)
        assertNull(memory[MemoryCache.Key("0")])
        // MemoryCache.get wraps each read in a fresh Value; identity lives on the image.
        assertSame(recent, memory[MemoryCache.Key("3")]?.image)
        memory[MemoryCache.Key("late")] = MemoryCache.Value(image(entrySize))
        assertTrue(memory.size <= PlaybackArtworkCacheBytes)
        policy.endPlayback(session)
        assertEquals(128L * 1024 * 1024, memory.maxSize)
        assertEquals(listOf(true, false), transitions)
    }

    @Test
    fun `an outgoing player or window restore cannot release another active restriction`() {
        val transitions = mutableListOf<Boolean>()
        val policy = DesktopArtworkCacheCoordinator(transitions::add, {})
        val memory = cache()
        policy.register(memory)
        val first = policy.beginPlayback()
        val second = policy.beginPlayback()
        policy.endPlayback(first)
        policy.endPlayback(first) // Duplicate cleanup is harmless.
        policy.onWindowVisibilityChanged(false)
        policy.onWindowVisibilityChanged(true)
        assertEquals(PlaybackArtworkCacheBytes, memory.maxSize)
        policy.onWindowVisibilityChanged(false)
        policy.endPlayback(second)
        assertEquals(PlaybackArtworkCacheBytes, memory.maxSize)
        policy.onWindowVisibilityChanged(true)
        assertEquals(128L * 1024 * 1024, memory.maxSize)
        assertEquals(listOf(true, false), transitions)
    }

    @Test
    fun `cache created during playback inherits the cap and keeps its original browsing budget`() {
        val policy = DesktopArtworkCacheCoordinator({}, {})
        val session = policy.beginPlayback()
        val memory = cache()
        policy.register(memory)
        policy.register(memory)
        assertEquals(PlaybackArtworkCacheBytes, memory.maxSize)
        policy.endPlayback(session)
        assertEquals(128L * 1024 * 1024, memory.maxSize)
        val small = cache(16L * 1024 * 1024)
        policy.register(small)
        policy.beginPlayback()
        assertEquals(16L * 1024 * 1024, small.maxSize)
    }

    @Test
    fun `Coil does not become a second owner of decoded animation frames`() {
        val memory = DesktopArtworkMemoryCache(cache())
        val animation = SkiaAnimatedImage(4, 4, listOf(ImageBitmap(4, 4)), intArrayOf(100))
        val animationKey = MemoryCache.Key("animation")
        memory[animationKey] = MemoryCache.Value(animation)
        assertNull(memory[animationKey])
        assertEquals(0L, memory.size)
        val still = image(64)
        memory[MemoryCache.Key("still")] = MemoryCache.Value(still)
        assertSame(still, memory[MemoryCache.Key("still")]?.image)
        // Skipping the cache does not close a frame still owned by its painter.
        assertEquals(4, animation.currentFrameForCompose().width)
    }

    @Test
    fun `scaled cache remains bounded after trim while existing painter references survive`() {
        ScaledBitmapCache.clear()
        ScaledBitmapCache.setBackgroundMode(false)
        try {
            val heldByPainter = ImageBitmap(1024, 1024)
            repeat(5) { ScaledBitmapCache.put("$it", heldByPainter) }
            ScaledBitmapCache.setBackgroundMode(true)
            assertTrue(ScaledBitmapCache.debugState().second <= PlaybackScaledCacheBytes)
            repeat(5) { ScaledBitmapCache.put("late-$it", heldByPainter) }
            assertTrue(ScaledBitmapCache.debugState().second <= PlaybackScaledCacheBytes)
            assertEquals(1024, heldByPainter.width)
            ScaledBitmapCache.setBackgroundMode(false)
            repeat(5) { ScaledBitmapCache.put("return-$it", heldByPainter) }
            assertTrue(ScaledBitmapCache.debugState().second > PlaybackScaledCacheBytes)
        } finally {
            ScaledBitmapCache.setBackgroundMode(false)
            ScaledBitmapCache.clear()
        }
    }
}
