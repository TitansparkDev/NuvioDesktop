package com.nuvio.app.core.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Bitmap

/**
 * The wash variants exist to price each component of the ambient background against the frame
 * budget. Two things have to hold or the measurements they produce are worse than useless:
 *
 * 1. A variant that silently draws the same thing would be read as "that component is free", which
 *    is the exact opposite of the truth.
 * 2. Nothing may change what an ordinary user sees. A stray Ctrl+Shift+W must do nothing at all
 *    unless diagnostics are on.
 */
@OptIn(ExperimentalComposeUiApi::class)
class AmbientWashVariantTest {

    @AfterTest
    fun tearDown() {
        FrameBenchMode.debugSetProbesEnabled(true)
        // Cycle back to Full so ordering between tests cannot leak.
        while (AmbientWashProbe.variant != AmbientWashVariant.Full) AmbientWashProbe.cycle()
        FrameBenchMode.debugSetProbesEnabled(false)
        FrameBudget.debugReset()
    }

    @Test
    fun `diagnostics off means the shipped rendering, and an inert shortcut`() {
        FrameBenchMode.debugSetProbesEnabled(false)
        assertNull(AmbientWashProbe.cycle(), "cycling must do nothing while diagnostics are off")
        assertEquals(AmbientWashVariant.Full, AmbientWashProbe.effective())
    }

    @Test
    fun `cycling visits every variant and returns to full`() {
        FrameBenchMode.debugSetProbesEnabled(true)
        val seen = mutableListOf<AmbientWashVariant>()
        repeat(AmbientWashVariant.entries.size) { seen += AmbientWashProbe.cycle()!! }
        assertEquals(AmbientWashVariant.entries.toSet(), seen.toSet())
        assertEquals(
            AmbientWashVariant.Full,
            AmbientWashProbe.variant,
            "a full cycle must land back on the shipped rendering",
        )
    }

    private fun renderWash(): Bitmap {
        val scene = ImageComposeScene(width = 64, height = 128, density = Density(1f)) {
            NuvioTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    HeroAmbientBackdrop(
                        // No image: the backdrop half of the wash needs a loaded bitmap, which an
                        // offscreen scene has no way to fetch. This covers the tint branch only —
                        // see the note on the test below.
                        backdrop = null,
                        accent = Color(0xFF3D7BD9),
                        onAccentChanged = {},
                    )
                }
            }
        }
        val image = scene.render()
        val bitmap = Bitmap().apply { allocN32Pixels(image.width, image.height) }
        image.readPixels(bitmap)
        scene.close()
        return bitmap
    }

    private fun differs(a: Bitmap, b: Bitmap): Boolean {
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.getColor(x, y) != b.getColor(x, y)) return true
            }
        }
        return false
    }

    /**
     * Only [AmbientWashVariant.NoTint] can be checked this way. `NoBlur` and `NoColorFilter` change
     * how a loaded backdrop bitmap is drawn, and `NoCrossfade` only differs mid-transition, none of
     * which an offscreen scene with no network can produce. If one of those ever reports a suspicious
     * zero cost, check its wiring before believing it.
     */
    @Test
    fun `NoTint actually removes the tint`() {
        FrameBenchMode.debugSetProbesEnabled(true)
        while (AmbientWashProbe.variant != AmbientWashVariant.Full) AmbientWashProbe.cycle()
        val full = renderWash()
        while (AmbientWashProbe.variant != AmbientWashVariant.NoTint) AmbientWashProbe.cycle()
        val noTint = renderWash()
        assertTrue(differs(full, noTint), "NoTint rendered identically to Full — the branch is dead")
    }
}
