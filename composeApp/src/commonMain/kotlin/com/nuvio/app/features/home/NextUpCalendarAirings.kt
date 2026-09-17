package com.nuvio.app.features.home

import com.nuvio.app.features.trakt.TraktCalendarEntry
import com.nuvio.app.features.trakt.parseTraktIsoDateTimeToEpochMs

/**
 * One dated episode of a show, as the provider calendar lists it.
 *
 * Trakt's watched-shows seeds carry no next-episode date of their own (SIMKL's do), so the
 * Continue Watching window reads it from the Trakt calendar instead. The calendar is bucketed by
 * day for the month grid; this index turns it around by show so a lookup per seed is a map hit
 * rather than a walk over every day.
 */
internal data class CalendarAiring(
    val airEpochMs: Long,
    val seasonNumber: Int?,
    val episodeNumber: Int?,
)

/** Series airings by content id, each list in air-date order. Movies are not seeds and are skipped. */
internal fun indexCalendarAiringsByContent(
    entriesByDate: Map<String, List<TraktCalendarEntry>>,
): Map<String, List<CalendarAiring>> {
    if (entriesByDate.isEmpty()) return emptyMap()
    val byContent = mutableMapOf<String, MutableList<CalendarAiring>>()
    entriesByDate.forEach { (dateKey, entries) ->
        val airEpochMs = calendarDateKeyToEpochMs(dateKey) ?: return@forEach
        entries.forEach { entry ->
            if (entry.type != "series" || entry.contentId.isBlank()) return@forEach
            byContent.getOrPut(entry.contentId) { mutableListOf() }.add(
                CalendarAiring(
                    airEpochMs = airEpochMs,
                    seasonNumber = entry.seasonNumber,
                    episodeNumber = entry.episodeNumber,
                ),
            )
        }
    }
    return byContent.mapValues { (_, airings) -> airings.sortedBy { it.airEpochMs } }
}

/**
 * When the first episode after the seed airs, or null when the calendar lists none.
 *
 * "After" is by coordinates, not date: an unwatched episode that aired last week is still the next
 * one to watch, and whether that date falls inside the window is the caller's question. Specials
 * and entries without coordinates cannot be placed against the seed and are skipped.
 */
internal fun nextAiringAfterSeed(
    airings: List<CalendarAiring>?,
    seedSeasonNumber: Int,
    seedEpisodeNumber: Int,
): Long? {
    if (airings.isNullOrEmpty()) return null
    return airings
        .asSequence()
        .filter { airing ->
            val season = airing.seasonNumber ?: return@filter false
            val episode = airing.episodeNumber ?: return@filter false
            season > 0 &&
                (season > seedSeasonNumber || (season == seedSeasonNumber && episode > seedEpisodeNumber))
        }
        .minOfOrNull { it.airEpochMs }
}

/** Calendar buckets are UTC "YYYY-MM-DD"; the airing is placed at the start of that day. */
private fun calendarDateKeyToEpochMs(dateKey: String): Long? =
    if (dateKey.length == 10) parseTraktIsoDateTimeToEpochMs("${dateKey}T00:00:00Z") else null
