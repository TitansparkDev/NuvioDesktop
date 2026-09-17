package com.nuvio.app.features.recap

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the model is actually asked. The boundary has to survive the trip into the text. */
class RecapPromptTest {
    private fun source(vararg lines: RecapEpisodeLine) = RecapSource(lines = lines.toList())

    private fun line(season: Int, episode: Int, overview: String? = "Synopsis $season.$episode") =
        RecapEpisodeLine(season, episode, "Title $season.$episode", overview)

    private val twoSeasons = source(
        line(1, 1), line(1, 2),
        line(2, 1), line(2, 2),
    )

    @Test
    fun `a season cut-off names the season that must not appear`() {
        val prompt = buildRecapPrompt("Show", RecapBoundary(season = 3), twoSeasons)

        assertTrue(prompt.user.contains("Cut-off: the start of season 3"), prompt.user)
        assertTrue(prompt.user.contains("Nothing from season 3 onwards has been watched"), prompt.user)
    }

    @Test
    fun `an episode cut-off names the episode and says it is unwatched`() {
        val prompt = buildRecapPrompt("Show", RecapBoundary(season = 2, episode = 3), twoSeasons)

        assertTrue(prompt.user.contains("Cut-off: S02E03, which has not been watched"), prompt.user)
    }

    @Test
    fun `only the supplied episodes reach the prompt`() {
        val prompt = buildRecapPrompt("Show", RecapBoundary(season = 3), twoSeasons)

        assertTrue(prompt.user.contains("S01E01 - Title 1.1: Synopsis 1.1"), prompt.user)
        assertTrue(prompt.user.contains("S02E02 - Title 2.2: Synopsis 2.2"), prompt.user)
        assertFalse(prompt.user.contains("S03"), prompt.user)
    }

    @Test
    fun `the system prompt forbids drawing on the model's own knowledge`() {
        val prompt = buildRecapPrompt("Show", RecapBoundary(season = 3), twoSeasons)

        assertTrue(prompt.system.contains("The list is your only source"), prompt.system)
        assertTrue(prompt.system.contains("Never refer to anything at or after the cut-off"), prompt.system)
    }

    @Test
    fun `a title-only season is labelled so the gap is not filled in from memory`() {
        val trimmed = RecapSource(
            lines = listOf(line(1, 1, overview = null), line(2, 1)),
            condensedSeasons = listOf(1),
        )

        val prompt = buildRecapPrompt("Show", RecapBoundary(season = 3), trimmed)

        assertTrue(prompt.user.contains("Season 1 (titles only)"), prompt.user)
        assertTrue(prompt.user.contains("S01E01 - Title 1.1\n"), prompt.user)
        assertFalse(prompt.user.contains("Season 2 (titles only)"), prompt.user)
    }

    @Test
    fun `omitted seasons are declared so the recap does not read as an origin story`() {
        val trimmed = RecapSource(lines = listOf(line(9, 1)), omittedSeasons = listOf(1, 2, 3))

        val prompt = buildRecapPrompt("Show", RecapBoundary(season = 10), trimmed)

        assertTrue(prompt.user.contains("Earlier seasons not supplied: 1, 2, 3"), prompt.user)
    }

    @Test
    fun `the answer's language is only stated when it is not the default`() {
        val english = buildRecapPrompt("Show", RecapBoundary(season = 3), twoSeasons, languageTag = "en")
        val german = buildRecapPrompt("Show", RecapBoundary(season = 3), twoSeasons, languageTag = "de")

        assertFalse(english.user.contains("IETF tag"), english.user)
        assertTrue(german.user.contains("""IETF tag "de""""), german.user)
    }
}
