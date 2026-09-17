package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import coil3.size.Dimension
import coil3.size.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The hero backdrop used to size its request from the MONITOR ([desktopDisplayMetrics]) rather than
 * from the box it is drawn in, so on a 4K display it decoded 3840x2160 (31.6 MB) for a backdrop
 * drawn across 85% of the width. Hero decodes were 10% of decodes but 83% of all decoded bytes.
 *
 * It now sizes from layout like every other surface. These pin the two things that made the old
 * approach necessary, and which a naive change would silently break.
 */
@OptIn(ExperimentalTestApi::class)
class HeroBackdropSizeRenderTest {
    /** 4K at 200% scaling: Compose constraints are PHYSICAL pixels, so density must not be applied twice. */
    private val displayWidthPx = 3840
    private val displayHeightPx = 2160

    private fun resolveHeroRequest(widthFraction: Float, heightFraction: Float): Size {
        val resolver = DesktopArtworkSizeResolver()
        var resolved: Size? = null
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(2f)) {
                    // The window in DP; the backdrop takes a fraction of it, as the immersive hero does.
                    Box(Modifier.requiredSize((displayWidthPx / 2).dp, (displayHeightPx / 2).dp)) {
                        Box(
                            Modifier
                                .fillMaxWidth(widthFraction)
                                .fillMaxHeight(heightFraction)
                                .then(resolver),
                        )
                    }
                }
            }
            waitForIdle()
            resolved = resolver.requestSize
        }
        return resolved ?: error("layout never produced a size")
    }

    private fun Size.px(pick: (Size) -> Dimension): Int =
        (pick(this) as? Dimension.Pixels)?.px ?: error("axis is not a pixel size: $this")

    @Test
    fun `a partial-width backdrop asks for less than the whole display`() {
        // HERO_BACKDROP_WIDTH_FRACTION is 0.85, and the immersive hero is not full height.
        val size = resolveHeroRequest(widthFraction = 0.85f, heightFraction = 0.55f)
        val width = size.px { it.width }
        assertTrue(
            width < displayWidthPx,
            "asked for $width px for a backdrop drawn across ${(displayWidthPx * 0.85f).toInt()} px",
        )
        // ...but never LESS than it draws, or the backdrop is upscaled and visibly soft.
        assertTrue(width >= displayWidthPx * 0.85f, "asked for $width px, draws ${displayWidthPx * 0.85f}")
    }

    @Test
    fun `a full-bleed backdrop still asks for the whole display`() {
        // Full-backdrop mode runs the artwork edge to edge; sizing must follow it back up.
        val size = resolveHeroRequest(widthFraction = 1f, heightFraction = 1f)
        assertTrue(
            size.px { it.width } >= displayWidthPx,
            "full backdrop asked for ${size.px { it.width }} px on a $displayWidthPx px display",
        )
    }

    @Test
    fun `both axes are sent, because a square request would reduce nothing`() {
        // ContentScale.Crop resolves to Scale.FILL, which takes the LARGER of the two ratios: send
        // one axis (or a square) and the short side decides, reducing nothing at all - silently,
        // with code that looks right.
        val size = resolveHeroRequest(widthFraction = 0.85f, heightFraction = 0.55f)
        val width = size.px { it.width }
        val height = size.px { it.height }
        assertTrue(width > height, "expected a landscape request, got ${width}x$height")
        assertEquals(
            Size(Dimension.Pixels(width), Dimension.Pixels(height)),
            size,
            "both axes must be real pixel values",
        )
    }

    @Test
    fun `a taller hero asks for more height, not more width`() {
        val short = resolveHeroRequest(widthFraction = 0.85f, heightFraction = 0.55f)
        val tall = resolveHeroRequest(widthFraction = 0.85f, heightFraction = 0.80f)
        assertEquals(short.px { it.width }, tall.px { it.width }, "width should not follow hero height")
        assertTrue(
            tall.px { it.height } > short.px { it.height },
            "a taller hero asked for ${tall.px { it.height }} vs ${short.px { it.height }}",
        )
    }
}
