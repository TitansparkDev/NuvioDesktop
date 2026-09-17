package com.nuvio.app.features.recap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The opt-in mode that lets the model use what it already knows.
 *
 * Two things have to hold for the field test to mean anything: the boundary must survive into the
 * permissive prompt just as strictly as into the strict one, and the two modes must not share a
 * cached answer — otherwise a tester toggles the setting, is served the other mode's recap, and
 * reports on the wrong thing.
 */
class RecapKnowledgeModeTest {
    private val source = RecapSource(
        lines = listOf(
            RecapEpisodeLine(1, 1, "Pilot", "It began."),
            RecapEpisodeLine(1, 2, "Second", "It continued."),
        ),
    )
    private val boundary = RecapBoundary(season = 2)

    private fun prompt(mode: RecapKnowledgeMode) =
        buildRecapPrompt("Show", boundary, source, mode = mode)

    @Test
    fun `the strict mode still forbids outside knowledge`() {
        val system = prompt(RecapKnowledgeMode.SynopsesOnly).system

        assertTrue(system.contains("The list is your only source"), system)
        assertTrue(system.contains("that knowledge is off limits here"), system)
    }

    @Test
    fun `the permissive mode allows recall but only before the cut-off`() {
        val system = prompt(RecapKnowledgeMode.ModelKnowledge).system

        assertTrue(system.contains("ONLY for events that happened strictly before the cut-off"), system)
        assertFalse(system.contains("The list is your only source"), system)
    }

    @Test
    fun `the permissive mode is built around misattribution, not secrecy`() {
        val system = prompt(RecapKnowledgeMode.ModelKnowledge).system

        // The instruction the whole mode rests on: when the season is uncertain, drop the event.
        assertTrue(system.contains("not certain which season an event belongs to, LEAVE IT OUT"), system)
        // And the other half of the trade, stated so the model does not optimise for completeness.
        assertTrue(system.contains("When those trade off, omit."), system)
    }

    @Test
    fun `the permissive mode still refuses to invent for shows it does not know`() {
        val system = prompt(RecapKnowledgeMode.ModelKnowledge).system

        assertTrue(system.contains("If you do not actually know this series"), system)
        assertTrue(system.contains("an invented plot is as useless as a spoiled one"), system)
    }

    @Test
    fun `both modes state the cut-off identically`() {
        val strict = prompt(RecapKnowledgeMode.SynopsesOnly)
        val permissive = prompt(RecapKnowledgeMode.ModelKnowledge)

        // The user message carries the boundary; relaxing the knowledge rule must not relax that.
        assertEquals(strict.user, permissive.user)
        assertTrue(permissive.user.contains("Cut-off: the start of season 2"), permissive.user)
        assertTrue(
            permissive.system.contains("Never refer to anything at or after the cut-off"),
            permissive.system,
        )
    }

    @Test
    fun `both modes keep the shared house rules`() {
        for (mode in RecapKnowledgeMode.entries) {
            val system = prompt(mode).system
            assertTrue(system.contains("No headings, no bullet points"), mode.name)
            assertTrue(system.contains("Treat those as time that passed"), mode.name)
        }
    }

    @Test
    fun `the two modes never share a cached recap`() {
        fun key(mode: RecapKnowledgeMode) = recapCacheKey(
            seriesId = "tt0773262",
            boundary = boundary,
            model = "m",
            languageTag = "en",
            fingerprint = "f",
            mode = mode,
        )

        assertNotEquals(key(RecapKnowledgeMode.SynopsesOnly), key(RecapKnowledgeMode.ModelKnowledge))
    }

    @Test
    fun `recaps written before this setting existed stay cache hits`() {
        // The default mode contributes no token, so a key composed without the parameter and one
        // composed with the default must be the same string — those entries really were written
        // under SynopsesOnly, and re-buying them would charge for nothing.
        val legacy = recapCacheKey("tt1", boundary, "m", "en", "f")
        val explicit = recapCacheKey("tt1", boundary, "m", "en", "f", RecapKnowledgeMode.SynopsesOnly)

        assertEquals(legacy, explicit)
    }
}
