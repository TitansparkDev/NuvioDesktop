package com.nuvio.app.features.recap

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.details.effectiveEpisodeNumber
import com.nuvio.app.features.details.effectiveSeasonNumber

/**
 * Turns the episodes already in memory into the exact text a recap request will carry.
 *
 * Pure, and deliberately the largest piece of this feature: the spoiler boundary, the budget and
 * the one metadata quirk that would otherwise poison every recap all live here, where they can be
 * tested without a provider, a key, or a screen.
 *
 * Callers pass episodes that are already released and already free of specials — the app has one
 * implementation of both rules in `MetaDetails.releasedMainSeasonEpisodes`, and a second copy here
 * would be a second thing to keep in step.
 */

/**
 * How much episode text one request may carry, in characters.
 *
 * Measured against real metadata rather than guessed: a whole series runs ~7.5k characters for
 * Game of Thrones, ~8.5k for Breaking Bad, ~136k for Grey's Anatomy and ~173k for The Simpsons. At
 * 120k, every ordinary show travels complete and only the two-decade run-ons are condensed — which
 * is the right place to spend the budget, because those are also the shows where the early seasons
 * matter least to "what is happening now".
 */
const val RECAP_MAX_INPUT_CHARS = 120_000

/** `S01E02 - ` plus the newline; charged per line so the budget counts what is actually sent. */
private const val LINE_OVERHEAD_CHARS = 12

/**
 * Builds the recap input for [boundary].
 *
 * @param episodes released main-season episodes, in any order.
 * @param seriesDescription the show's own synopsis, used only to recognise it when an addon has
 *   repeated it as an episode overview — see [isSeriesDescriptionEcho].
 */
fun buildRecapSource(
    episodes: List<MetaVideo>,
    boundary: RecapBoundary,
    seriesDescription: String? = null,
    maxChars: Int = RECAP_MAX_INPUT_CHARS,
): RecapSource {
    val seriesEcho = seriesDescription?.let(::normalizeForComparison)?.takeIf { it.isNotBlank() }

    val lines = episodes
        .mapNotNull { video ->
            val season = video.effectiveSeasonNumber() ?: return@mapNotNull null
            val episode = video.effectiveEpisodeNumber() ?: return@mapNotNull null
            if (!boundary.includes(season, episode)) return@mapNotNull null

            val overview = video.overview
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.takeUnless { seriesEcho != null && normalizeForComparison(it) == seriesEcho }

            RecapEpisodeLine(
                season = season,
                episode = episode,
                title = video.title.trim(),
                overview = overview,
            )
        }
        // Sorted here rather than trusted from the caller: the budget spends from the earliest
        // season forward, so the order is load-bearing, not cosmetic.
        .sortedWith(compareBy({ it.season }, { it.episode }))

    // An episode list with no synopses anywhere is a title index, not a recap input. Sending it
    // would spend a request to have a model write from its own memory of the show, which is
    // exactly the failure mode the whole feature is trying to avoid.
    if (lines.none { it.overview != null }) return RecapSource(lines = emptyList())

    return lines.fitToBudget(maxChars)
}

/**
 * Trims [this] to [maxChars] by degrading, in order, the parts worth least.
 *
 * Two stages, and the order between them is the whole design: seasons are first reduced to episode
 * titles (which still carry the shape of the season) and only then dropped entirely. Both walk from
 * the earliest season forward, because a recap exists to set up what happens next and recent events
 * are what it is for.
 */
private fun List<RecapEpisodeLine>.fitToBudget(maxChars: Int): RecapSource {
    if (cost() <= maxChars) return RecapSource(lines = this)

    val seasonsOldestFirst = map { it.season }.distinct().sorted()
    var working = this
    val condensed = mutableListOf<Int>()

    for (season in seasonsOldestFirst) {
        if (working.cost() <= maxChars) break
        working = working.map { line ->
            if (line.season == season) line.copy(overview = null) else line
        }
        condensed += season
    }

    val omitted = mutableListOf<Int>()
    for (season in seasonsOldestFirst) {
        if (working.cost() <= maxChars) break
        working = working.filterNot { it.season == season }
        omitted += season
    }

    return RecapSource(
        lines = working,
        // A season that was dropped outright is not also reported as condensed: the user is told
        // one thing about each season, and "missing" is the one that matters.
        condensedSeasons = condensed.filterNot { it in omitted },
        omittedSeasons = omitted,
    )
}

private fun List<RecapEpisodeLine>.cost(): Int =
    sumOf { it.title.length + (it.overview?.length ?: 0) + LINE_OVERHEAD_CHARS }

/**
 * Casefolded, punctuation-insensitive form for equality only.
 *
 * Exists for one verified quirk: Cinemeta hands back the *series* description as the episode
 * overview for some entries — `videos[0]` of Breaking Bad is the show's own logline, not the
 * episode's. Left in, it teaches every recap that the series premise is something that happened in
 * episode one, and it survives a naive equality check because the two copies differ in whitespace.
 */
private fun normalizeForComparison(text: String): String =
    text.lowercase().filter { it.isLetterOrDigit() }
