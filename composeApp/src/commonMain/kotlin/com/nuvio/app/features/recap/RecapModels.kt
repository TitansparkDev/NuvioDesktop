package com.nuvio.app.features.recap

/**
 * The point a recap stops at.
 *
 * **Exclusive on purpose.** A boundary names the episode the viewer is about to watch, not the last
 * one they saw, so both entry points reduce to the same comparison: "recap everything strictly
 * before this". A season tab is `RecapBoundary(3, null)` — nothing from season 3 — and resuming
 * mid-season is `RecapBoundary(3, 5)` after finishing S03E04.
 *
 * Getting this inclusive would spoil by exactly one episode every time, which is the failure this
 * feature exists to avoid, so [includes] is spelled out rather than expressed as arithmetic on a
 * packed key.
 */
data class RecapBoundary(
    val season: Int,
    /**
     * `null` means the boundary sits before the season's first episode, so the whole season is
     * excluded. Not defaulted to 1: an episode-0 entry inside a main season would then slip
     * through, and "the whole season is out" deserves to be its own value rather than a number
     * that happens to work.
     */
    val episode: Int? = null,
) {
    /** Whether an episode at these coordinates falls inside the recap. */
    fun includes(season: Int, episode: Int): Boolean = when {
        season < this.season -> true
        season > this.season -> false
        else -> this.episode != null && episode < this.episode
    }

    /** Stable across launches and providers — see [RecapCacheKey]. */
    internal fun cacheToken(): String = "s$season" + (episode?.let { "e$it" } ?: "")
}

/**
 * One episode as the prompt describes it.
 *
 * [overview] is nullable because condensing drops synopses and keeps titles: a long-running show
 * that will not fit its budget is better represented by "these forty episodes happened, here are
 * their titles" than by silently starting the story at season 9.
 */
data class RecapEpisodeLine(
    val season: Int,
    val episode: Int,
    val title: String,
    val overview: String?,
)

/**
 * Everything a recap request is built from, after filtering and budgeting.
 *
 * The two loss fields are not diagnostics — they are shown to the user. A recap of a
 * twenty-season show that quietly began at season 12 reads as a bad recap rather than as a
 * truncated one, and the difference is the only thing that tells someone the fix is a longer
 * budget rather than a different model.
 */
data class RecapSource(
    val lines: List<RecapEpisodeLine>,
    /** Seasons reduced to episode titles to fit the budget, earliest first. */
    val condensedSeasons: List<Int> = emptyList(),
    /** Seasons left out entirely because titles alone still did not fit, earliest first. */
    val omittedSeasons: List<Int> = emptyList(),
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /** Seasons actually represented, in order. Drives the sheet's subtitle. */
    val seasons: List<Int> get() = lines.map { it.season }.distinct()

    val wasTrimmed: Boolean get() = condensedSeasons.isNotEmpty() || omittedSeasons.isNotEmpty()
}

/** Why a recap could not be produced. Each of these is shown to the user, so none is a silent no-op. */
sealed interface RecapUnavailable {
    /** No episode before the boundary carried a synopsis, or there are no earlier episodes at all. */
    data object NothingToRecap : RecapUnavailable

    /** The switch is off, or the credential behind it is missing. */
    data object NotEnabled : RecapUnavailable
}
