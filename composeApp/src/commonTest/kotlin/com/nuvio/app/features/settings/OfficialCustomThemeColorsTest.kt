package com.nuvio.app.features.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OfficialCustomThemeColorsTest {

    @Test
    fun flatAccentEncodesAsThreeEqualStops() {
        assertEquals("#FFD700,#FFD700,#FFD700", OfficialCustomThemeColors.encode("#FFD700", "#FFD700"))
    }

    @Test
    fun gradientEncodesWithInterpolatedMidpoint() {
        assertEquals("#000000,#808080,#FFFFFF", OfficialCustomThemeColors.encode("#000000", "#ffffff"))
    }

    @Test
    fun invalidAccentEndFallsBackToFlat() {
        assertEquals("#112233,#112233,#112233", OfficialCustomThemeColors.encode("112233", "nope"))
    }

    @Test
    fun invalidAccentEncodesToNothing() {
        assertNull(OfficialCustomThemeColors.encode("#12345", "#FFFFFF"))
    }

    @Test
    fun decodeTakesFirstAndThirdStops() {
        assertEquals("#B75AFF" to "#FFB37A", OfficialCustomThemeColors.decode("#B75AFF,#EC70A9,#FFB37A"))
    }

    @Test
    fun decodeNormalisesCaseAndHash() {
        assertEquals("#ABCDEF" to "#ABCDEF", OfficialCustomThemeColors.decode("abcdef, #ABCDEF ,#abcdef"))
    }

    @Test
    fun decodeRejectsWrongStopCountOrBadColour() {
        assertNull(OfficialCustomThemeColors.decode(null))
        assertNull(OfficialCustomThemeColors.decode("#FFFFFF,#FFFFFF"))
        assertNull(OfficialCustomThemeColors.decode("#FFFFFF,#GGGGGG,#FFFFFF"))
    }

    @Test
    fun roundTripKeepsBothEnds() {
        val encoded = OfficialCustomThemeColors.encode("#FF0000", "#0000FF")!!
        assertEquals("#FF0000" to "#0000FF", OfficialCustomThemeColors.decode(encoded))
    }
}
