package com.nuvio.app.core.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The frame-budget report is only worth reading if its arithmetic is right, and the arithmetic that
 * can be wrong is the nesting: a probe's self time is its inclusive time minus its children's, and
 * getting that backwards produces a table where the row and its cards each appear to cost the whole
 * frame. These assert the identity rather than any duration, so they do not depend on how long the
 * machine running them takes to do anything.
 */
class FrameBudgetAccountingTest {

    private fun probe(name: String, body: () -> Unit = {}) {
        val depth = FrameBudget.probeStart()
        val start = System.nanoTime()
        body()
        FrameBudget.probeEnd(name, depth, start)
    }

    private fun burn(iterations: Int) {
        var sink = 0L
        repeat(iterations) { sink += it.toLong() * 31 }
        check(sink >= 0)
    }

    @Test
    fun `self time excludes nested probes`() {
        FrameBudget.debugReset()
        FrameBudget.setScrolling("test", true)
        FrameBudget.onFrameClock(System.nanoTime())
        probe("row") {
            burn(50_000)
            probe("card") { burn(50_000) }
            probe("card") { burn(50_000) }
        }

        val snapshot = FrameBudget.debugProbeSnapshot()
        val (rowDraws, rowSelf, rowInclusive) = snapshot.getValue("row")
        val (cardDraws, _, cardInclusive) = snapshot.getValue("card")

        assertEquals(1, rowDraws)
        assertEquals(2, cardDraws)
        // The identity the report depends on, asserted exactly: whatever the two cards cost comes
        // off the row's own figure.
        assertEquals(rowInclusive - cardInclusive, rowSelf)
        assertTrue(rowSelf > 0, "row self time should stay positive: was $rowSelf")
        assertTrue(rowInclusive > cardInclusive, "row must enclose its cards")
        FrameBudget.debugReset()
    }

    @Test
    fun `siblings do not steal each other's time`() {
        FrameBudget.debugReset()
        FrameBudget.setScrolling("test", true)
        FrameBudget.onFrameClock(System.nanoTime())
        probe("hero") { burn(50_000) }
        probe("ambient") { burn(50_000) }

        val snapshot = FrameBudget.debugProbeSnapshot()
        val (_, heroSelf, heroInclusive) = snapshot.getValue("hero")
        val (_, ambientSelf, ambientInclusive) = snapshot.getValue("ambient")
        // Neither is nested in the other, so both are pure self time.
        assertEquals(heroInclusive, heroSelf)
        assertEquals(ambientInclusive, ambientSelf)
        FrameBudget.debugReset()
    }

    @Test
    fun `idle frames are not recorded`() {
        FrameBudget.debugReset()
        FrameBudget.onFrameClock(System.nanoTime())
        probe("row") { burn(10_000) }

        assertTrue(
            FrameBudget.debugProbeSnapshot().isEmpty(),
            "frames outside a scroll must not reach the report",
        )
    }

    @Test
    fun `probe depth is restored even when nesting is deep`() {
        FrameBudget.debugReset()
        FrameBudget.setScrolling("test", true)
        FrameBudget.onFrameClock(System.nanoTime())
        // Deeper than MaxProbeDepth on purpose: the depth counter must still unwind to zero, or
        // every subsequent frame mis-attributes its self time.
        fun nest(remaining: Int) {
            if (remaining == 0) return
            probe("deep-$remaining") { nest(remaining - 1) }
        }
        nest(40)

        // A second, flat frame proves the counter came back: at a non-zero depth the top-level
        // probe below would be treated as nested and report as somebody's child.
        FrameBudget.onFrameClock(System.nanoTime())
        probe("flat") { burn(10_000) }

        val (_, flatSelf, flatInclusive) = FrameBudget.debugProbeSnapshot().getValue("flat")
        assertEquals(flatInclusive, flatSelf)
        FrameBudget.debugReset()
    }
}
