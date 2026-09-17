package com.nuvio.app.features.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * What counts as "the same recap".
 *
 * A key that is too loose serves a stale or wrong-language answer forever; one that is too tight
 * bills again for something already paid for. Both are silent, so they are pinned here.
 */
class RecapCacheKeyTest {
    private fun key(
        seriesId: String = "tt0903747",
        boundary: RecapBoundary = RecapBoundary(season = 3),
        model: String = "claude-opus-5",
        languageTag: String = "en",
        fingerprint: String = "abc123",
    ) = recapCacheKey(seriesId, boundary, model, languageTag, fingerprint)

    @Test
    fun `identical inputs are the same recap`() {
        assertEquals(key(), key())
    }

    @Test
    fun `every input that changes the answer changes the key`() {
        assertNotEquals(key(), key(seriesId = "tt0944947"))
        assertNotEquals(key(), key(boundary = RecapBoundary(season = 4)))
        assertNotEquals(key(), key(model = "gpt-4o-mini"))
        assertNotEquals(key(), key(languageTag = "de"))
        assertNotEquals(key(), key(fingerprint = "def456"))
    }

    @Test
    fun `a season boundary and a mid-season one are not interchangeable`() {
        assertNotEquals(
            key(boundary = RecapBoundary(season = 3)),
            key(boundary = RecapBoundary(season = 3, episode = 1)),
        )
    }

    @Test
    fun `an ongoing series that gains an episode before the boundary gets a new fingerprint`() {
        val before = RecapSource(
            lines = listOf(
                RecapEpisodeLine(1, 1, "Pilot", "It began."),
                RecapEpisodeLine(1, 2, "Second", "It continued."),
            ),
        )
        val after = RecapSource(lines = before.lines + RecapEpisodeLine(1, 3, "Third", "It went on."))

        assertNotEquals(before.fingerprint(), after.fingerprint())
    }

    @Test
    fun `an edited synopsis is a different fingerprint`() {
        val before = RecapSource(lines = listOf(RecapEpisodeLine(1, 1, "Pilot", "Short.")))
        val after = RecapSource(lines = listOf(RecapEpisodeLine(1, 1, "Pilot", "A much longer one.")))

        assertNotEquals(before.fingerprint(), after.fingerprint())
    }

    @Test
    fun `the same episodes fingerprint the same across runs`() {
        val lines = listOf(RecapEpisodeLine(2, 4, "Peekaboo", "Jesse meets a family."))

        assertEquals(RecapSource(lines = lines).fingerprint(), RecapSource(lines = lines).fingerprint())
    }
}
