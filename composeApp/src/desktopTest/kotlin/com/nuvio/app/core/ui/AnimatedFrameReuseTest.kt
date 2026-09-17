package com.nuvio.app.core.ui

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Animated posters used to build a fresh Skia `Image` on every draw and close it again.
 *
 * A fresh `Image` carries a fresh unique id, which is what Skia's texture cache keys on — so every
 * visible animated poster re-uploaded its entire frame to the GPU on every frame, then threw the
 * texture away. The pixels never changed; only the wrapper did.
 *
 * The property that has to hold is identity, not equality: the same frame must hand back the *same*
 * object every time, or the texture cache misses again and the fix is worth nothing. That cannot be
 * seen by looking at the picture, which is why it is asserted here.
 */
class AnimatedFrameReuseTest {

    private fun animation(frameCount: Int, durationMs: Int = 100): SkiaAnimatedImage =
        SkiaAnimatedImage(
            width = 4,
            height = 4,
            frames = List(frameCount) { ImageBitmap(4, 4) },
            frameDurationsMs = IntArray(frameCount) { durationMs },
        )

    @Test
    fun `the same frame is handed back as the same object`() {
        val image = animation(frameCount = 1)
        val first = image.currentSkiaFrame()
        repeat(20) { assertSame(first, image.currentSkiaFrame()) }
    }

    @Test
    fun `distinct frames are distinct objects`() {
        val image = animation(frameCount = 3)
        // Reached through the public surface rather than by index, so this exercises the same path
        // a draw takes.
        val seen = linkedSetOf<Any>()
        repeat(60) {
            seen += image.currentSkiaFrame()
            Thread.sleep(6)
        }
        assertTrue(seen.size >= 2, "the clock never advanced past one frame: saw ${seen.size}")
        assertTrue(seen.size <= 3, "more distinct images than frames: ${seen.size}")
    }

    @Test
    fun `a still image still reports its bitmap`() {
        // DesktopArtworkCachesTest and the coil Image contract both read this; caching the Skia
        // images must not have detached it from the frame the clock is actually on.
        val image = animation(frameCount = 1)
        assertTrue(image.currentFrameForCompose().width == 4)
        assertTrue(image.currentSkiaFrame().width == 4)
    }

    @Test
    fun `playback opens on frame zero, whatever time it is`() {
        val image = animation(frameCount = 5, durationMs = 100)
        // The bug this pins: the index came from `System.currentTimeMillis() % totalDuration`, so a
        // card starting playback opened on whatever frame the wall clock was on. The still shows
        // frame 0, so anything else is a visible jump the moment the animation takes over.
        assertTrue(image.frameIndexAt(0L) == 0, "playback must open on frame 0")
        assertSame(image.skiaFrameAt(0L), image.skiaFrameAt(0L))
    }

    @Test
    fun `elapsed time walks the frames in order and loops`() {
        val image = animation(frameCount = 4, durationMs = 100)
        assertTrue(image.frameIndexAt(50L) == 0)
        assertTrue(image.frameIndexAt(150L) == 1)
        assertTrue(image.frameIndexAt(250L) == 2)
        assertTrue(image.frameIndexAt(350L) == 3)
        // 400 ms is one full cycle, so 450 ms is 50 ms into the second loop: frame 0 again, not
        // frame 1, and certainly not off the end of the array.
        assertTrue(image.frameIndexAt(450L) == 0)
        assertTrue(image.frameIndexAt(800L) == 0)
        // A negative elapsed (clock stepping backwards) must not throw or index out of range.
        assertTrue(image.frameIndexAt(-10L) == 0)
    }
}
