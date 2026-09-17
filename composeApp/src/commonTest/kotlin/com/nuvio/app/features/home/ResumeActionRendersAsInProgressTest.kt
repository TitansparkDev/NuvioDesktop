package com.nuvio.app.features.home

import com.nuvio.app.features.watchprogress.WatchProgressEntry
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Up Next card stands down for a resumable episode only when that episode really becomes an
 * in-progress card. A remote source reports a percentage and never a position, so a freshly queued
 * episode arrives at 0% / position 0 — resumable by one rule, "not started" by the other, and the
 * series fell through both and left Continue Watching entirely.
 */
class ResumeActionRendersAsInProgressTest {
    private fun entry(
        videoId: String,
        season: Int?,
        episode: Int?,
        positionMs: Long,
        percent: Float?,
        completed: Boolean = false,
    ) = WatchProgressEntry(
        contentType = "series",
        parentMetaId = "kitsu:50040",
        parentMetaType = "series",
        videoId = videoId,
        title = "Super no Ura de Yani Suu Futari",
        seasonNumber = season,
        episodeNumber = episode,
        lastPositionMs = positionMs,
        durationMs = 0L,
        isCompleted = completed,
        progressPercent = percent,
        lastUpdatedEpochMs = 1_788_992_201_000L,
        source = "simkl_playback",
    )

    @Test
    fun `a session at zero percent with no position does not count as an in-progress card`() {
        val entries = listOf(entry("kitsu:50040:1:3", 1, 3, positionMs = 0L, percent = 0f))

        assertFalse(
            resumeActionRendersAsInProgress(
                contentId = "kitsu:50040",
                actionVideoId = "kitsu:50040:1:3",
                actionSeasonNumber = 1,
                actionEpisodeNumber = 3,
                entries = entries,
            ),
        )
    }

    @Test
    fun `a partly watched remote session does count, so Up Next still stands down for it`() {
        val entries = listOf(entry("kitsu:50040:1:3", 1, 3, positionMs = 0L, percent = 27.9f))

        assertTrue(
            resumeActionRendersAsInProgress(
                contentId = "kitsu:50040",
                actionVideoId = "kitsu:50040:1:3",
                actionSeasonNumber = 1,
                actionEpisodeNumber = 3,
                entries = entries,
            ),
        )
    }

    @Test
    fun `a local session with a real position counts even when no percentage is reported`() {
        val entries = listOf(entry("kitsu:50040:1:3", 1, 3, positionMs = 120_000L, percent = null))

        assertTrue(
            resumeActionRendersAsInProgress(
                contentId = "kitsu:50040",
                actionVideoId = "kitsu:50040:1:3",
                actionSeasonNumber = 1,
                actionEpisodeNumber = 3,
                entries = entries,
            ),
        )
    }

    @Test
    fun `season and episode stand in when the source spells the video id differently`() {
        val entries = listOf(entry("simkl-34919913", 1, 3, positionMs = 0L, percent = 27.9f))

        assertTrue(
            resumeActionRendersAsInProgress(
                contentId = "kitsu:50040",
                actionVideoId = "kitsu:50040:1:3",
                actionSeasonNumber = 1,
                actionEpisodeNumber = 3,
                entries = entries,
            ),
        )
    }

    @Test
    fun `another series' in-progress episode is never mistaken for this one`() {
        val entries = listOf(
            entry("kitsu:50040:1:3", 1, 3, positionMs = 0L, percent = 0f),
            entry("tt0108778:1:2", 1, 2, positionMs = 500_000L, percent = 40f).copy(parentMetaId = "tt0108778"),
        )

        assertFalse(
            resumeActionRendersAsInProgress(
                contentId = "kitsu:50040",
                actionVideoId = "kitsu:50040:1:3",
                actionSeasonNumber = 1,
                actionEpisodeNumber = 3,
                entries = entries,
            ),
        )
    }
}
