package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The hero must draw the URL enrichment will settle on, not one it will replace a moment later.
 *
 * The raw catalog item carries metahub's "medium" backdrop; enrichment upgrades it to "large". Until
 * the raw item was normalised too, the hero fetched, decoded and faded in the same picture twice -
 * harmless-looking until the backdrop crossfade made every change visible.
 */
class HeroBackdropNormalizationTest {
    @Test
    fun `a metahub medium backdrop is upgraded before it is ever drawn`() {
        assertEquals(
            "https://images.metahub.space/background/large/tt36056731/img",
            heroBackdropUrlForTest("https://images.metahub.space/background/medium/tt36056731/img"),
        )
    }

    @Test
    fun `an already-large metahub backdrop is left alone`() {
        val large = "https://images.metahub.space/background/large/tt36056731/img"
        assertEquals(large, heroBackdropUrlForTest(large))
    }

    @Test
    fun `non-metahub backdrops are untouched`() {
        val tmdb = "https://image.tmdb.org/t/p/original/abc.jpg"
        assertEquals(tmdb, heroBackdropUrlForTest(tmdb))
        // A metahub LOGO is a different asset; only the background path may be rewritten.
        val logo = "https://images.metahub.space/logo/medium/tt36056731/img"
        assertEquals(logo, heroBackdropUrlForTest(logo))
    }

    @Test
    fun `a missing backdrop stays missing`() {
        assertEquals(null, heroBackdropUrlForTest(null))
    }
}
