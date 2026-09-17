package com.nuvio.app.features.simkl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * SIMKL's `last_watched` marker has two forms, and only one was ever parsed.
 *
 * `E2` is what a live account returned for an anime on 2026-09-09 — anime entries are scoped to a
 * single anime-list entry whose season is always 1, so SIMKL omits the season rather than writing
 * `S01`. Rejecting it dropped the entry and took the title out of Continue Watching entirely.
 */
class SimklEpisodeMarkerTest {
    @Test
    fun `the series form keeps its stated season`() {
        assertEquals(5 to 16, parseSimklEpisodeMarker("S05E16"))
        assertEquals(1 to 7, parseSimklEpisodeMarker("S01E07"))
        assertEquals(12 to 3, parseSimklEpisodeMarker("s12e03"))
    }

    @Test
    fun `the season-less anime form resolves to the entry-local season 1`() {
        assertEquals(1 to 2, parseSimklEpisodeMarker("E2"))
        assertEquals(1 to 24, parseSimklEpisodeMarker("e24"))
        assertEquals(1 to 2, parseSimklEpisodeMarker(" E2 "))
    }

    @Test
    fun `a marker that is neither form is still rejected`() {
        assertNull(parseSimklEpisodeMarker(""))
        assertNull(parseSimklEpisodeMarker("2"))
        assertNull(parseSimklEpisodeMarker("Episode 2"))
        // The E-only branch is anchored on purpose: without that this reads "E16" out of a marker
        // the season/episode form has already rejected, and invents season 1 for it.
        assertNull(parseSimklEpisodeMarker("SxxE16"))
    }
}
