package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadActivityOrderTest {

    private fun episode(
        episode: Int,
        status: DownloadStatus,
        awaitingSlot: Boolean = false,
        createdAtEpochMs: Long = episode.toLong(),
    ) = DownloadItem(
        id = "download-$episode",
        contentType = "series",
        parentMetaId = "tt0000001",
        parentMetaType = "series",
        videoId = "tt0000001:1:$episode",
        title = "Show",
        seasonNumber = 1,
        episodeNumber = episode,
        streamTitle = "Episode $episode",
        providerName = "season-pack",
        sourceUrl = "https://example.invalid/$episode.mkv",
        fileName = "Show/Show - S01E${episode.toString().padStart(2, '0')}.mkv",
        isAutomaticDownload = true,
        awaitingSlot = awaitingSlot,
        status = status,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = createdAtEpochMs,
    )

    @Test
    fun `live transfers lead, then the queued pack in episode order, then failures`() {
        // Repository insertion order is newest-first: the last episode of a pack sits on top.
        val newestFirst = listOf(
            episode(5, DownloadStatus.Failed),
            episode(4, DownloadStatus.Paused, awaitingSlot = true),
            episode(3, DownloadStatus.Paused, awaitingSlot = true),
            episode(2, DownloadStatus.Downloading),
            episode(1, DownloadStatus.Downloading),
        )

        val ordered = newestFirst.sortedWith(downloadActivityComparator).map { it.episodeNumber }

        assertEquals(listOf(1, 2, 3, 4, 5), ordered)
    }

    @Test
    fun `queued-for-slot is only a paused row the pack drain has not reached`() {
        val queued = episode(3, DownloadStatus.Paused, awaitingSlot = true)
        val userPaused = episode(4, DownloadStatus.Paused)
        val resumed = queued.copy(status = DownloadStatus.Downloading, awaitingSlot = false)

        assertEquals(true, queued.isQueuedForSlot)
        assertEquals(false, userPaused.isQueuedForSlot)
        assertEquals(false, resumed.isQueuedForSlot)
    }

    @Test
    fun `progress and eta stay null until the total and a speed sample are known`() {
        val unknownTotal = episode(1, DownloadStatus.Downloading).copy(downloadedBytes = 1_000L)
        assertNull(unknownTotal.progressPercent)
        assertNull(unknownTotal.etaSeconds)

        val halfway = unknownTotal.copy(totalBytes = 2_000L, bytesPerSecond = 500L)
        assertEquals(50, halfway.progressPercent)
        assertEquals(2L, halfway.etaSeconds)
    }

    @Test
    fun `speed reads in megabits like a connection speed`() {
        assertEquals("12.5 Mbps", formatDownloadSpeed(1_562_500L))
        assertEquals("1h 5m", formatDownloadDuration(3_900L))
        assertEquals("2m 5s", formatDownloadDuration(125L))
    }
}
