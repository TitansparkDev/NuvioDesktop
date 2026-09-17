package com.nuvio.app.core.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [RecompositionProbe] rests entirely on `@NonRestartableComposable` making its body run whenever
 * its CALLER recomposes. That is a compiler-plugin behaviour, not something the source states, and
 * getting it wrong fails silently in the worst possible way: a probe that counts nothing reads as
 * "this composable never recomposes", which is the exact conclusion it exists to test.
 */
@OptIn(ExperimentalTestApi::class)
class RecompositionProbeTest {

    @AfterTest
    fun tearDown() {
        FrameBudget.debugReset()
        FrameBenchMode.debugSetProbesEnabled(false)
    }

    @Test
    fun `counts the callers recompositions, not its own`() = runComposeUiTest {
        FrameBudget.debugReset()
        FrameBudget.setScrolling("test", true)
        FrameBenchMode.debugSetProbesEnabled(true)

        var counter by mutableIntStateOf(0)
        setContent {
            RecompositionProbe("subject")
            // Reading the state here is what puts this scope in the invalidation set. Without a
            // read the probe would be measuring a scope nothing ever recomposes.
            Text("value $counter")
        }
        waitForIdle()
        val afterFirst = FrameBudget.debugRecompositionSnapshot()["subject"] ?: 0
        assertTrue(afterFirst >= 1, "initial composition should count once: was $afterFirst")

        counter = 1
        waitForIdle()
        counter = 2
        waitForIdle()

        val afterChanges = FrameBudget.debugRecompositionSnapshot()["subject"] ?: 0
        assertEquals(
            afterFirst + 2,
            afterChanges,
            "each recomposition of the caller should add exactly one count",
        )
    }

    @Test
    fun `records nothing while probes are off`() = runComposeUiTest {
        FrameBudget.debugReset()
        FrameBudget.setScrolling("test", true)
        FrameBenchMode.debugSetProbesEnabled(false)

        var counter by mutableIntStateOf(0)
        setContent {
            RecompositionProbe("subject")
            Text("value $counter")
        }
        waitForIdle()
        counter = 1
        waitForIdle()

        assertTrue(
            FrameBudget.debugRecompositionSnapshot().isEmpty(),
            "the opt-in flag must gate recording, not just the modifier probes",
        )
    }
}
