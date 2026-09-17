package com.nuvio.app.features.watchprogress

/**
 * Whether a Continue Watching row belongs inside the selected provider's day window.
 *
 * The window used to be measured only from when the row was last watched, which excluded exactly
 * the shows the "New Season" / "Airs in X" badges exist for: a series returning after a hiatus
 * longer than the window was gone before its premiere could be noticed. A row therefore also
 * qualifies when its next unwatched episode airs within the same number of days on either side of
 * now — a premiere tomorrow, or one that aired last week and is still unwatched. Anything further
 * out stays hidden until it comes into range, so a season announced for next year does not sit in
 * Up Next for months.
 *
 * [cutoffMs] of zero or less means the window is off and every row qualifies, matching the "All
 * history" sentinel both providers use.
 */
internal fun isWithinContinueWatchingWindow(
    lastUpdatedEpochMs: Long,
    nextEpisodeAirEpochMs: Long?,
    cutoffMs: Long,
    nowEpochMs: Long,
): Boolean {
    if (cutoffMs <= 0L) return true
    if (lastUpdatedEpochMs >= cutoffMs) return true
    val airEpochMs = nextEpisodeAirEpochMs ?: return false
    return airEpochMs in cutoffMs..continueWatchingWindowHorizonMs(cutoffMs, nowEpochMs)
}

/** The far edge of the window: as many days ahead of now as [cutoffMs] is behind it. */
internal fun continueWatchingWindowHorizonMs(cutoffMs: Long, nowEpochMs: Long): Long =
    nowEpochMs + (nowEpochMs - cutoffMs)

internal fun WatchProgressEntry.isWithinContinueWatchingWindow(cutoffMs: Long, nowEpochMs: Long): Boolean =
    isWithinContinueWatchingWindow(
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        nextEpisodeAirEpochMs = nextEpisodeAirEpochMs,
        cutoffMs = cutoffMs,
        nowEpochMs = nowEpochMs,
    )
