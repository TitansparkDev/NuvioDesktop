package com.nuvio.app.features.home

import com.nuvio.app.features.trakt.TraktCalendarEntry
import com.nuvio.app.features.watching.domain.WatchingContentRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Seeds outside the provider window survive when their next episode airs inside it — from the
 * seed's own date (SIMKL) or the provider calendar (Trakt).
 */
class NextUpCalendarWindowTest {
    private val day = 24L * 60L * 60L * 1000L

    // 2026-09-16T00:00:00Z
    private val now = 1_789_516_800_000L
    private val traktCap = 60

    private fun candidate(
        contentId: String,
        season: Int,
        episode: Int,
        markedAtEpochMs: Long,
        nextEpisodeAirEpochMs: Long? = null,
    ) = CompletedSeriesCandidate(
        content = WatchingContentRef(type = "series", id = contentId),
        seasonNumber = season,
        episodeNumber = episode,
        markedAtEpochMs = markedAtEpochMs,
        nextEpisodeAirEpochMs = nextEpisodeAirEpochMs,
    )

    private fun calendarEntry(
        dateKey: String,
        contentId: String,
        season: Int?,
        episode: Int?,
        type: String = "series",
    ) = TraktCalendarEntry(
        dateKey = dateKey,
        type = type,
        contentId = contentId,
        title = contentId,
        posterUrl = null,
        seasonNumber = season,
        episodeNumber = episode,
    )

    @Test
    fun `a SIMKL seed carrying a premiere date inside the window is kept`() {
        val returning = candidate("tt10590066", 6, 7, now - 270L * day, nextEpisodeAirEpochMs = now + day)
        val stale = candidate("tt0000001", 2, 3, now - 270L * day)

        val kept = filterHomeNextUpCandidatesForContinueWatchingWindow(
            candidates = listOf(returning, stale),
            cutoffMs = now - 30L * day,
            nowEpochMs = now,
        )

        assertEquals(listOf("tt10590066"), kept.map { it.content.id })
    }

    @Test
    fun `a Trakt seed is kept when the calendar lists its next episode inside the window`() {
        val returning = candidate("tt10590066", 6, 7, now - 270L * day)
        val index = indexCalendarAiringsByContent(
            mapOf(
                "2026-09-17" to listOf(calendarEntry("2026-09-17", "tt10590066", 7, 1)),
            ),
        )

        val kept = filterHomeNextUpCandidatesForTraktContinueWatchingWindow(
            candidates = listOf(returning),
            isTraktProgressActive = true,
            daysCap = traktCap,
            nowEpochMs = now,
            upcomingAirEpochMs = { c -> nextAiringAfterSeed(index[c.content.id], c.seasonNumber, c.episodeNumber) },
        )

        assertEquals(listOf("tt10590066"), kept.map { it.content.id })
    }

    @Test
    fun `a Trakt seed with no calendar airing after it stays out`() {
        val stale = candidate("tt10590066", 7, 1, now - 270L * day)
        val index = indexCalendarAiringsByContent(
            mapOf(
                // Already watched — S07E01 is the seed, so this airing is not "next".
                "2026-09-17" to listOf(calendarEntry("2026-09-17", "tt10590066", 7, 1)),
            ),
        )

        val kept = filterHomeNextUpCandidatesForTraktContinueWatchingWindow(
            candidates = listOf(stale),
            isTraktProgressActive = true,
            daysCap = traktCap,
            nowEpochMs = now,
            upcomingAirEpochMs = { c -> nextAiringAfterSeed(index[c.content.id], c.seasonNumber, c.episodeNumber) },
        )

        assertEquals(emptyList(), kept)
    }

    @Test
    fun `the next airing is the first by coordinates, not the first by date`() {
        // Behind by two episodes: the next one to watch aired 100 days ago, outside a 60-day
        // window, even though a later episode airs tomorrow. Seeds inside the window are the
        // caller's business; this helper only answers "when does the next unwatched one air".
        val index = indexCalendarAiringsByContent(
            mapOf(
                "2026-06-08" to listOf(calendarEntry("2026-06-08", "show", 3, 1)),
                "2026-09-17" to listOf(calendarEntry("2026-09-17", "show", 3, 2)),
            ),
        )

        assertEquals(now - 100L * day, nextAiringAfterSeed(index["show"], seedSeasonNumber = 2, seedEpisodeNumber = 8))
    }

    @Test
    fun `specials, movies and uncoordinated entries are not next episodes`() {
        val index = indexCalendarAiringsByContent(
            mapOf(
                "2026-09-17" to listOf(
                    calendarEntry("2026-09-17", "show", 0, 3),
                    calendarEntry("2026-09-17", "show", null, null),
                    calendarEntry("2026-09-17", "show", 9, 9, type = "movie"),
                ),
            ),
        )

        assertNull(nextAiringAfterSeed(index["show"], seedSeasonNumber = 1, seedEpisodeNumber = 1))
    }

    @Test
    fun `an unreadable date bucket is skipped rather than failing the index`() {
        val index = indexCalendarAiringsByContent(
            mapOf("not-a-date" to listOf(calendarEntry("not-a-date", "show", 1, 2))),
        )

        assertNull(index["show"])
    }
}
