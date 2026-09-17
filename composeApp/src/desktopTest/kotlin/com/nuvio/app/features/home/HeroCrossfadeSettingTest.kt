package com.nuvio.app.features.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The crossfade exists because an instantaneous hero backdrop swap is a full-screen brightness step,
 * which a variable-refresh OLED answers with a visible dim - and because a hard cut between a dark
 * and a bright backdrop is unpleasant in a dark room regardless of display. 0 restores the old hard
 * cut for anyone who wants it.
 */
class HeroCrossfadeSettingTest {
    @Test
    fun `the default fades rather than cutting`() {
        assertTrue(
            HERO_CROSSFADE_DEFAULT_MS > 0,
            "a 0 default would ship the hard cut this setting exists to remove",
        )
        assertTrue(HERO_CROSSFADE_DEFAULT_MS in HERO_CROSSFADE_MIN_MS..HERO_CROSSFADE_MAX_MS)
    }

    @Test
    fun `off is reachable and the range is sane`() {
        assertEquals(0, HERO_CROSSFADE_MIN_MS, "users must be able to turn the fade off entirely")
        assertTrue(HERO_CROSSFADE_MAX_MS in 500..2000, "an upper bound past ~1s stops feeling like a hero")
    }
}
