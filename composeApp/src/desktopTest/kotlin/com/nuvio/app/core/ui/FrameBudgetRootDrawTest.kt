package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the finding that decided how [FrameBudget] counts frames.
 *
 * The first design bracketed the root in a `drawWithContent` and derived every per-frame figure
 * from it. Two live runs produced no report at all. The cause is measured here: across 12 rendered
 * frames that each recomposed and redrew every visible card, a draw modifier at the ROOT runs once
 * — Compose re-records only the draw nodes it invalidated. Per-subtree probes are unaffected, which
 * is why the attribution table survived the redesign and the frame accounting moved to the frame
 * clock.
 *
 * If this ever starts failing because the root redraws per frame, Compose's invalidation has
 * changed and a root bracket would become viable again — but nothing should depend on it without
 * re-measuring.
 */
class FrameBudgetRootDrawTest {

    @AfterTest
    fun tearDown() {
        FrameBudget.debugReset()
        FrameBenchMode.debugSetProbesEnabled(false)
    }

    @Test
    fun `probes redraw every frame but a root bracket does not`() {
        FrameBudget.debugReset()
        FrameBenchMode.debugSetProbesEnabled(true)

        var rootDraws = 0
        val visibleCards = 10
        val offset = mutableIntStateOf(0)
        val scene = ImageComposeScene(width = 800, height = 600, density = Density(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        rootDraws++
                        drawContent()
                    },
            ) {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items((0 until 40).toList()) { index ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .frameBudgetProbe("card")
                                .background(Color(0xFF202020)),
                        ) {
                            RecompositionProbe("card")
                            Text("row $index offset ${offset.intValue}")
                        }
                    }
                }
            }
        }
        try {
            scene.render()
            val rootDrawsAfterFirstFrame = rootDraws
            // Only now: the initial composition is not a frame, and counting its ten card
            // recompositions would put the expected totals ten out.
            FrameBudget.setScrolling("test", true)
            val renders = 12
            repeat(renders) { i ->
                // Mutate through the snapshot system exactly as a real scroll would, then render.
                Snapshot.withMutableSnapshot { offset.intValue = i + 1 }
                FrameBudget.onFrameClock(System.nanoTime())
                scene.render()
            }

            val cardRecompositions = FrameBudget.debugRecompositionSnapshot()["card"] ?: 0
            val cardDraws = FrameBudget.debugProbeSnapshot()["card"]?.first ?: 0

            // The harness really is driving work: every visible card recomposed on every render.
            assertEquals(renders * visibleCards, cardRecompositions)
            // ...and every one of those redraws reached its probe.
            assertEquals(renders * visibleCards, cardDraws)
            // ...while the root bracket saw none of them.
            assertEquals(
                rootDrawsAfterFirstFrame,
                rootDraws,
                "a root draw bracket must not be trusted to fire per frame",
            )
            // Frames come from the frame clock, so they are counted despite all of the above.
            assertTrue(
                FrameBudget.debugFrameCount() >= renders - 1,
                "frame counting must survive the root never redrawing: " +
                    FrameBudget.debugFrameCount(),
            )
        } finally {
            scene.close()
        }
    }
}
