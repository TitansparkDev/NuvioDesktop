package com.nuvio.app.core.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect

/**
 * The ambient backdrop's blur moved out of `Modifier.blur` — a render effect that re-runs every
 * frame, measured at 3.32 ms of a 4.15 ms wash — and into the image pipeline, where it runs once per
 * URL on a decode thread.
 *
 * "Same blur, just computed earlier" is precisely the sort of claim that turns out to be false, so
 * this renders both and measures the difference instead of asserting it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AmbientBackdropBlurTest {

    private val size = 96
    private val radiusPx = 12f

    /** Hard edges and saturated blocks, so any difference in the kernel has somewhere to show. */
    private fun source(): Bitmap {
        val bitmap = Bitmap().apply {
            allocPixels(ImageInfo.makeN32(size, size, ColorAlphaType.PREMUL))
        }
        val canvas = Canvas(bitmap)
        canvas.clear(0xFF102030.toInt())
        canvas.drawRect(Rect.makeXYWH(10f, 10f, 40f, 40f), Paint().apply { color = 0xFFE04010.toInt() })
        canvas.drawRect(Rect.makeXYWH(55f, 30f, 30f, 55f), Paint().apply { color = 0xFF20C060.toInt() })
        canvas.drawRect(Rect.makeXYWH(0f, 70f, size.toFloat(), 12f), Paint().apply { color = 0xFFF0F0C0.toInt() })
        bitmap.setImmutable()
        return bitmap
    }

    private fun renderComposeBlur(input: Bitmap): Bitmap {
        val composeSource = org.jetbrains.skia.Image.makeFromBitmap(input).toComposeImageBitmap()
        val scene = ImageComposeScene(width = size, height = size, density = Density(1f)) {
            Box(modifier = Modifier.fillMaxSize()) {
                Image(
                    bitmap = composeSource,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().blur(radiusPx.dp),
                    contentScale = ContentScale.FillBounds,
                )
            }
        }
        val image = scene.render()
        val out = Bitmap().apply { allocN32Pixels(size, size) }
        image.readPixels(out)
        scene.close()
        return out
    }

    @Test
    fun `the decode-time blur matches Modifier blur in the interior`() {
        val input = source()
        val baked = blurAmbientBitmap(input, radiusPx)
        val composeBlurred = renderComposeBlur(input)

        // Edges are excluded deliberately: the two use different edge treatments (CLAMP here, so a
        // stretched result has no dark frame around it; Compose clips to the rectangle). The
        // interior is where the kernels have to agree.
        val margin = (radiusPx * 2).toInt()
        var worst = 0
        var total = 0L
        var samples = 0
        for (y in margin until size - margin) {
            for (x in margin until size - margin) {
                val a = baked.getColor(x, y)
                val b = composeBlurred.getColor(x, y)
                val delta = maxOf(
                    abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
                    abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)),
                    abs((a and 0xFF) - (b and 0xFF)),
                )
                if (delta > worst) worst = delta
                total += delta
                samples++
            }
        }
        val mean = total.toDouble() / samples
        java.io.File("build/ambient-blur-delta.txt").apply {
            parentFile?.mkdirs()
            writeText("worst=" + worst + " mean=" + mean + " samples=" + samples)
        }
        // Measured: pixel-identical in the interior. Held at 1 rather than 0 only so a future
        // Skia rounding change reports as a near-miss instead of a mystery failure.
        assertTrue(worst <= 1, "worst channel delta $worst (mean $mean)")
        assertTrue(mean <= 0.05, "mean channel delta $mean (worst $worst)")
    }

    @Test
    fun `a zero radius is a no-op rather than a copy`() {
        val input = source()
        assertTrue(blurAmbientBitmap(input, 0f) === input)
    }

    @Test
    fun `blurring actually changes the image`() {
        val input = source()
        val baked = blurAmbientBitmap(input, radiusPx)
        // Guards the case that matters most: a blur that silently did nothing would look like a
        // free optimisation and be a visual regression.
        assertTrue(
            baked.getColor(12, 12) != input.getColor(12, 12),
            "the baked blur left a hard edge untouched",
        )
    }
}
