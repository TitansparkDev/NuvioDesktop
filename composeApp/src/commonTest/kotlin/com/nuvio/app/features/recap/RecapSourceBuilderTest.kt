package com.nuvio.app.features.recap

import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The spoiler boundary and the budget.
 *
 * The first group of tests is the feature's whole promise: a recap that mentions one episode past
 * where someone stopped has done the exact harm it exists to prevent, and nothing downstream — not
 * the prompt, not the model — can undo it.
 */
class RecapSourceBuilderTest {
    private fun episode(
        season: Int,
        number: Int,
        title: String = "S${season}E$number",
        overview: String? = "Something happened in $season.$number.",
    ) = MetaVideo(
        id = "tt1:$season:$number",
        title = title,
        season = season,
        episode = number,
        overview = overview,
    )

    private val threeSeasons = listOf(
        episode(1, 1), episode(1, 2), episode(1, 3),
        episode(2, 1), episode(2, 2), episode(2, 3),
        episode(3, 1), episode(3, 2), episode(3, 3),
    )

    @Test
    fun `a season boundary excludes that season and everything after it`() {
        val source = buildRecapSource(threeSeasons, RecapBoundary(season = 3))

        assertContentEquals(listOf(1, 2), source.seasons)
        assertTrue(source.lines.none { it.season >= 3 }, source.lines.toString())
        assertEquals(6, source.lines.size)
    }

    @Test
    fun `an episode boundary keeps earlier episodes of the same season and drops the rest`() {
        val source = buildRecapSource(threeSeasons, RecapBoundary(season = 2, episode = 3))

        assertContentEquals(listOf(1, 2), source.seasons)
        // S02E01 and S02E02 are in; S02E03 — the one about to be watched — is not.
        assertContentEquals(
            listOf(1, 2),
            source.lines.filter { it.season == 2 }.map { it.episode },
        )
    }

    @Test
    fun `a boundary with no episode excludes the whole season, including an episode zero`() {
        val withEpisodeZero = threeSeasons + episode(2, 0)

        val source = buildRecapSource(withEpisodeZero, RecapBoundary(season = 2))

        assertContentEquals(listOf(1), source.seasons)
    }

    @Test
    fun `season one has nothing before it`() {
        assertTrue(buildRecapSource(threeSeasons, RecapBoundary(season = 1)).isEmpty)
    }

    @Test
    fun `episodes are ordered even when the metadata is not`() {
        val shuffled = listOf(episode(2, 1), episode(1, 2), episode(2, 2), episode(1, 1))

        val source = buildRecapSource(shuffled, RecapBoundary(season = 3))

        assertContentEquals(
            listOf(1 to 1, 1 to 2, 2 to 1, 2 to 2),
            source.lines.map { it.season to it.episode },
        )
    }

    @Test
    fun `an overview that is really the series description is dropped, keeping the title`() {
        val description = "  A chemistry teacher turns to crime.  "
        val episodes = listOf(
            episode(1, 1, title = "Pilot", overview = "A chemistry teacher turns to CRIME!"),
            episode(1, 2, overview = "A real synopsis."),
        )

        val source = buildRecapSource(episodes, RecapBoundary(season = 2), seriesDescription = description)

        assertNull(source.lines.first { it.episode == 1 }.overview)
        assertEquals("Pilot", source.lines.first { it.episode == 1 }.title)
        assertEquals("A real synopsis.", source.lines.first { it.episode == 2 }.overview)
    }

    @Test
    fun `an episode with no synopsis still counts as time that passed`() {
        val episodes = listOf(
            episode(1, 1, title = "Pilot", overview = null),
            episode(1, 2, overview = "A real synopsis."),
        )

        val source = buildRecapSource(episodes, RecapBoundary(season = 2))

        assertEquals(2, source.lines.size)
        assertNull(source.lines.first().overview)
    }

    @Test
    fun `nothing is sent when no episode before the boundary has a synopsis`() {
        val titlesOnly = (1..8).map { episode(1, it, overview = null) }

        assertTrue(buildRecapSource(titlesOnly, RecapBoundary(season = 2)).isEmpty)
    }

    @Test
    fun `episodes with unusable coordinates are skipped rather than guessed at`() {
        val unnumbered = MetaVideo(id = "x", title = "Mystery", overview = "Who knows.")

        val source = buildRecapSource(threeSeasons + unnumbered, RecapBoundary(season = 3))

        assertEquals(6, source.lines.size)
    }

    @Test
    fun `an oversized show is condensed from the earliest season forward`() {
        val long = (1..6).flatMap { season ->
            (1..10).map { episode(season, it, overview = "x".repeat(200)) }
        }

        // Room for roughly the last two seasons in full.
        val source = buildRecapSource(long, RecapBoundary(season = 7), maxChars = 5_000)

        assertTrue(source.condensedSeasons.isNotEmpty(), "expected condensing")
        // Whatever was condensed is a prefix of the seasons: recent seasons keep their detail.
        val condensed = source.condensedSeasons.sorted()
        assertContentEquals((1..condensed.size).toList(), condensed)
        assertTrue(
            source.lines.filter { it.season in condensed }.all { it.overview == null },
            "condensed seasons must keep titles only",
        )
        assertTrue(
            source.lines.filter { it.season !in condensed }.all { it.overview != null },
            "seasons past the condensed prefix must keep their synopses",
        )
    }

    @Test
    fun `a show too long even as titles drops its earliest seasons outright`() {
        val enormous = (1..40).flatMap { season ->
            (1..22).map { episode(season, it, title = "Episode title $season.$it") }
                .map { it.copy(overview = "y".repeat(300)) }
        }

        val source = buildRecapSource(enormous, RecapBoundary(season = 41), maxChars = 4_000)

        assertTrue(source.omittedSeasons.isNotEmpty(), "expected omissions")
        assertTrue(source.lines.none { it.season in source.omittedSeasons })
        // A season is reported once, and "missing" outranks "condensed".
        assertTrue(
            source.condensedSeasons.none { it in source.omittedSeasons },
            "a dropped season must not also be reported as condensed",
        )
        assertTrue(source.wasTrimmed)
    }

    @Test
    fun `an ordinary show is never trimmed`() {
        val source = buildRecapSource(threeSeasons, RecapBoundary(season = 3))

        assertFalse(source.wasTrimmed)
        assertTrue(source.lines.all { it.overview != null })
    }
}
