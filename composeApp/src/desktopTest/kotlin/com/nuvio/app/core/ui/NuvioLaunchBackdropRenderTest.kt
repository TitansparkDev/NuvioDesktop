package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The launch wash, read back as pixels.
 *
 * A wash this dim spread over a whole screen crosses a level only every hundred pixels or so, so
 * its contours are wide, smooth rings — the most visible kind of banding there is, and it was on
 * the one screen every session starts with. These read rendered pixels rather than trusting the
 * brush, because banding is a property of the 8-bit output and not of the stops that produced it.
 */
@OptIn(ExperimentalComposeUiApi::class)
class NuvioLaunchBackdropRenderTest {

    private val base = Color(0xFF0D0D0D)
    private val width = 1280
    private val height = 800

    /**
     * Banding is measured as how *rarely* the value changes along a scanline. On a ramp this
     * shallow every pixel between two contours holds the same level, so an undithered wash changes
     * value only where a ring is — a few dozen times in a thousand pixels. Dither moves the
     * rounding boundary from pixel to pixel, so the same ramp changes value constantly and no one
     * change is a visible edge.
     */
    @Test
    fun `dither dissolves the contours`() {
        val stacked = transitionsPerThousand(renderStackedLayers())
        val shaded = transitionsPerThousand(renderLaunchWash())

        // If this ever stops holding, the wash has steepened enough not to need dithering and the
        // assertion below has nothing left to prove.
        assertTrue(stacked <= 120, "the undithered wash has no contours to dissolve ($stacked per 1000)")
        assertTrue(
            shaded >= 300,
            "the wash only changes level $shaded times per 1000 pixels; the rest is one flat plateau " +
                "between contours, which is what a band is",
        )
    }

    /**
     * The shader replaced four stacked translucent draws, so it has to land on the same colours —
     * this is the part that would quietly drift if the glows were ever re-derived rather than
     * transcribed. It is also what caught the premultiplied-stop behaviour the ramps depend on: an
     * unpremultiplied reading of them lands nearly four levels dark on red.
     */
    @Test
    fun `the wash is the one it replaced`() {
        val stacked = renderStackedLayers()
        val shaded = renderLaunchWash()

        for (channel in 0..2) {
            val before = stacked.channelMean(channel)
            val after = shaded.channelMean(channel)
            // 1.5, not 1: the night-sky tints are dim enough that the control's four separate
            // 8-bit roundings land a full level apart from the shader's one, and the stars add a
            // fraction of a level on top. A re-derived glow would miss by several.
            assertTrue(
                abs(before - after) <= 1.5,
                "channel $channel averages $after against $before before the rewrite",
            )
        }
    }

    /**
     * The two glows are both blue-ish now, so a single channel cannot tell them apart from the
     * haze that peaks in the middle. What does: the horizon corner is cool (green well clear of
     * red), the zenith corner leans indigo (red at or above green), and both corners are lit
     * above the base in blue. The base is #0D0D0D, so anything over 13 in blue is a glow.
     */
    @Test
    fun `the glows still sit in their corners`() {
        val pixels = renderLaunchWash()
        val topLeftRed = pixels.blockMean(40, 40, channel = 0)
        val topLeftGreen = pixels.blockMean(40, 40, channel = 1)
        val topLeftBlue = pixels.blockMean(40, 40, channel = 2)
        val bottomRightRed = pixels.blockMean(width - 90, height - 90, channel = 0)
        val bottomRightGreen = pixels.blockMean(width - 90, height - 90, channel = 1)
        val bottomRightBlue = pixels.blockMean(width - 90, height - 90, channel = 2)

        assertTrue(
            topLeftBlue > 13 + 15 && bottomRightBlue > 13 + 15,
            "a corner is not lit: blue $topLeftBlue top-left, $bottomRightBlue bottom-right, over a base of 13",
        )
        assertTrue(
            topLeftGreen > topLeftRed + 6,
            "the horizon corner is not cool: green $topLeftGreen against red $topLeftRed",
        )
        assertTrue(
            bottomRightRed >= bottomRightGreen,
            "the zenith corner does not lean indigo: red $bottomRightRed against green $bottomRightGreen",
        )
    }

    private fun renderLaunchWash(): IntArray {
        val phase = mutableStateOf(0f)
        return paint { Modifier.nuvioLaunchBackdrop(base, phase) }
    }

    /**
     * The four separate draws this replaced, kept here as the control: an opaque base, both glows
     * as their own translucent rects, then the bridge. Each one quantised its result to 8 bits
     * before the next landed on top of it.
     */
    private fun renderStackedLayers(): IntArray = paint {
        Modifier.drawBehind {
            drawRect(base)
            val reach = size.maxDimension * 0.95f
            drawRect(
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to NuvioBrandRamp.Horizon.copy(alpha = 0.20f),
                        0.45f to NuvioBrandRamp.Horizon.copy(alpha = 0.06f),
                        1f to Color.Transparent,
                    ),
                    center = Offset(x = size.width * 0.12f, y = size.height * 0.04f),
                    radius = reach,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to NuvioBrandRamp.Zenith.copy(alpha = 0.22f),
                        0.45f to NuvioBrandRamp.Zenith.copy(alpha = 0.07f),
                        1f to Color.Transparent,
                    ),
                    center = Offset(x = size.width * 0.88f, y = size.height * 0.98f),
                    radius = reach,
                ),
            )
            drawRect(
                Brush.linearGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.5f to NuvioBrandRamp.Haze.copy(alpha = 0.07f),
                        1f to Color.Transparent,
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width, size.height),
                ),
            )
        }
    }

    /** Packed 0xRRGGBB per pixel. */
    private fun paint(modifier: () -> Modifier): IntArray {
        val scene = ImageComposeScene(width = width, height = height, density = Density(1f)) {
            Box(modifier = Modifier.fillMaxSize().then(modifier()))
        }
        val image = scene.render()
        val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
        require(image.readPixels(bitmap, 0, 0))
        val pixels = IntArray(width * height) { index ->
            bitmap.getColor(index % width, index / width) and 0xFFFFFF
        }
        scene.close()
        return pixels
    }

    private fun IntArray.channelAt(index: Int, shift: Int): Int = (this[index] shr shift) and 0xFF

    private fun IntArray.channelMean(channel: Int): Double {
        val shift = 16 - channel * 8
        var sum = 0L
        for (index in indices) sum += channelAt(index, shift)
        return sum.toDouble() / size
    }

    private fun IntArray.blockMean(x0: Int, y0: Int, channel: Int): Double {
        val shift = 16 - channel * 8
        var sum = 0L
        for (y in y0 until y0 + 50) for (x in x0 until x0 + 50) sum += channelAt(y * width + x, shift)
        return sum.toDouble() / (50 * 50)
    }

    private fun transitionsPerThousand(pixels: IntArray): Int {
        var changes = 0
        var total = 0
        for (line in scanlines(pixels)) {
            for (i in 1 until line.size) {
                total++
                if (line[i] != line[i - 1]) changes++
            }
        }
        return 1000 * changes / total
    }

    /**
     * Lines that cut across the rings: two horizontals, one down the left where the cyan glow falls
     * away fastest, and one diagonal. Green, because it is the channel with the most travel in it.
     */
    private fun scanlines(pixels: IntArray): List<IntArray> = listOf(
        IntArray(width) { x -> (pixels[(height / 3) * width + x] shr 8) and 0xFF },
        IntArray(width) { x -> (pixels[(2 * height / 3) * width + x] shr 8) and 0xFF },
        IntArray(height) { y -> (pixels[y * width + 60] shr 8) and 0xFF },
        IntArray(height) { y -> (pixels[y * width + (y * width / height)] shr 8) and 0xFF },
    )
}
