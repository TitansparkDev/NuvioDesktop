package com.nuvio.app.core.ui

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopArtworkTelemetryTest {
    @BeforeTest fun reset() = DesktopArtworkTelemetry.resetForTest()
    @AfterTest fun clear() = DesktopArtworkTelemetry.resetForTest()

    private fun openWindow() = assertNull(DesktopArtworkTelemetry.summaryOrNull(0L))

    @Test
    fun `a 4K backdrop reduced to a card reports its saving against the old pin`() {
        openWindow()
        // 3840x2160 source, decoded for a 224x336 card. The pin would have decoded it at
        // 1536x864 = 5.06 MB; this decodes 224x336 = 0.28 MB, so the saving is ~94%.
        DesktopArtworkTelemetry.recordDecode(3840, 2160, 224, 336, destinationSized = true)
        val summary = DesktopArtworkTelemetry.summaryOrNull(30_000L)!!
        assertTrue("decodes=1" in summary, summary)
        assertTrue("src=31MB" in summary, summary)
        assertTrue("vsPin=5MB" in summary, summary)
        assertTrue("savedVsPin=94%" in summary, summary)
        assertTrue("above1536=0" in summary, summary)
    }

    @Test
    fun `a source already smaller than the pin is credited no saving it did not make`() {
        openWindow()
        // A w500 poster: the pin never reduced it either, so source and pin must read the same and the
        // whole 79% is the destination sizing, not the pin removal.
        DesktopArtworkTelemetry.recordDecode(500, 750, 224, 336, destinationSized = true)
        val summary = DesktopArtworkTelemetry.summaryOrNull(30_000L)!!
        assertTrue("src=1MB" in summary, summary)
        assertTrue("vsPin=1MB" in summary, summary)
        assertTrue("savedVsPin=79%" in summary, summary)
    }

    @Test
    fun `draw samples dedupe per source and size and name the worst overdraw`() {
        openWindow()
        repeat(5) { DesktopArtworkTelemetry.recordDraw("poster-a", 224, 336, 210, 315) }
        DesktopArtworkTelemetry.recordDraw("poster-a", 224, 336, 105, 158)
        DesktopArtworkTelemetry.recordDraw("poster-b", 224, 336, 210, 315)
        DesktopArtworkTelemetry.recordDraw("hero", 3840, 2160, 1920, 1080)
        val summary = DesktopArtworkTelemetry.summaryOrNull(30_000L)!!
        // Five identical draws of one bitmap are one sample; four distinct pairs remain.
        assertTrue("draws=4" in summary, summary)
        assertTrue("atLeast2x=2" in summary, summary)
        assertTrue("worst=2.13x(224x336->105x158)" in summary, summary)
    }

    @Test
    fun `nothing is emitted before the interval or for an empty window`() {
        openWindow()
        DesktopArtworkTelemetry.recordDecode(500, 750, 224, 336, destinationSized = true)
        assertNull(DesktopArtworkTelemetry.summaryOrNull(29_999L))
        assertTrue(DesktopArtworkTelemetry.summaryOrNull(30_000L) != null)
        // The window reset, so a quiet one says nothing at all rather than repeating itself.
        assertNull(DesktopArtworkTelemetry.summaryOrNull(60_000L))
    }

    @Test
    fun `hero decodes are not credited against a pin that never governed them`() {
        openWindow()
        // A 4K backdrop on the hero path: unsized by design, so it must not drag savedVsPin down
        // and must be visible as its own number instead.
        DesktopArtworkTelemetry.recordDecode(3840, 2160, 3840, 2160, destinationSized = false)
        DesktopArtworkTelemetry.recordDecode(500, 750, 224, 336, destinationSized = true)
        val summary = DesktopArtworkTelemetry.summaryOrNull(30_000L)!!
        assertTrue("sized=1/0MB" in summary, summary)
        assertTrue("unsized=1/31MB" in summary, summary)
        assertTrue("savedVsPin=79%" in summary, summary)
        assertTrue("above1536=1" in summary, summary)
        assertTrue("largest=31MB(3840x2160 unsized)" in summary, summary)
    }

    @Test
    fun `a url loaded again from outside memory counts as an eviction paid twice`() {
        openWindow()
        DesktopArtworkTelemetry.recordLoad("poster-a", fromMemoryCache = false)
        DesktopArtworkTelemetry.recordLoad("poster-a", fromMemoryCache = true)  // cache hit, free
        DesktopArtworkTelemetry.recordLoad("poster-a", fromMemoryCache = false) // evicted, re-paid
        DesktopArtworkTelemetry.recordLoad("poster-b", fromMemoryCache = false) // first sight
        DesktopArtworkTelemetry.recordDecode(500, 750, 224, 336, destinationSized = true)
        val summary = DesktopArtworkTelemetry.summaryOrNull(30_000L)!!
        assertTrue("loads=4 reloads=1" in summary, summary)
    }
}
