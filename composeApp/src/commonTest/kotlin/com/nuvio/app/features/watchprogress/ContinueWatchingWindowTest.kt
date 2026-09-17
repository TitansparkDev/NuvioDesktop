package com.nuvio.app.features.watchprogress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Continue Watching window used to be measured only from the last watch, so a show returning
 * after a hiatus longer than the window was gone before its premiere — the one case the
 * "New Season" / "Airs in X" badges exist for. Reported 2026-09-16 for a SIMKL user whose show
 * premiered the next day and was nowhere in Up Next.
 */
class ContinueWatchingWindowTest {
    private val day = 24L * 60L * 60L * 1000L
    private val now = 1_800_000_000_000L
    private val cutoff = now - 30L * day

    @Test
    fun `a row watched inside the window qualifies regardless of air dates`() {
        assertTrue(isWithinContinueWatchingWindow(now - 3L * day, null, cutoff, now))
    }

    @Test
    fun `a row watched outside the window with no next episode is out`() {
        assertFalse(isWithinContinueWatchingWindow(now - 200L * day, null, cutoff, now))
    }

    @Test
    fun `a premiere tomorrow keeps a show last watched months ago`() {
        assertTrue(isWithinContinueWatchingWindow(now - 270L * day, now + 1L * day, cutoff, now))
    }

    @Test
    fun `an unwatched episode that aired last week keeps the show`() {
        assertTrue(isWithinContinueWatchingWindow(now - 270L * day, now - 7L * day, cutoff, now))
    }

    @Test
    fun `an episode further out than the window does not surface yet`() {
        // A season announced for next year would otherwise sit in Up Next for months.
        assertFalse(isWithinContinueWatchingWindow(now - 270L * day, now + 31L * day, cutoff, now))
        assertTrue(isWithinContinueWatchingWindow(now - 270L * day, now + 30L * day, cutoff, now))
    }

    @Test
    fun `an episode that aired before the window opened does not count`() {
        assertFalse(isWithinContinueWatchingWindow(now - 270L * day, now - 31L * day, cutoff, now))
    }

    @Test
    fun `the horizon mirrors the cutoff around now`() {
        assertEquals(now + 30L * day, continueWatchingWindowHorizonMs(cutoff, now))
    }

    @Test
    fun `no window keeps everything`() {
        assertTrue(isWithinContinueWatchingWindow(0L, null, cutoffMs = 0L, nowEpochMs = now))
    }

    @Test
    fun `an undated row with an undated next episode stays out`() {
        assertFalse(isWithinContinueWatchingWindow(0L, null, cutoff, now))
    }
}
