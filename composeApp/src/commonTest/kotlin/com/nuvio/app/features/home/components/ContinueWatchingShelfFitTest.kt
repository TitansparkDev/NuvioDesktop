package com.nuvio.app.features.home.components

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.ui.ExtraLargePosterCardWidthDp
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TV Mode's Continue Watching cards must fit the shelf at every UI scale, and must be left alone
 * whenever they already do — the 0% look is the design, the cap is only for scaled viewports.
 */
class ContinueWatchingShelfFitTest {

    // The TV card: Extra Large base width, 1.2x a catalog landscape card — 412x233dp.
    private val naturalHeight = continueWatchingLandscapeCardHeight(ExtraLargePosterCardWidthDp)

    @Test
    fun cardThatAlreadyFitsIsNotTouched() {
        // 0% and +20% shelves leave 306dp and 253dp for a card; the 233dp card fits both.
        assertNull(continueWatchingCardBaseWidthDpFittingHeight(ExtraLargePosterCardWidthDp, 306.dp))
        assertNull(continueWatchingCardBaseWidthDpFittingHeight(ExtraLargePosterCardWidthDp, 253.dp))
        assertNull(continueWatchingCardBaseWidthDpFittingHeight(ExtraLargePosterCardWidthDp, naturalHeight))
    }

    @Test
    fun overflowingCardIsCappedToTheShelfKeepingItsAspect() {
        // +50%: the shelf leaves 176dp, 57dp less than the card.
        val base = continueWatchingCardBaseWidthDpFittingHeight(ExtraLargePosterCardWidthDp, 176.dp)!!
        val height = landscapePosterHeightForWidth(landscapePosterWidth(base))
        assertTrue(height <= 176.dp, "capped card is $height tall for a 176dp limit")
        assertTrue(height >= 174.dp, "capped card gives away too much: $height for a 176dp limit")
    }

    @Test
    fun posterStyleShrinksPosterAndKeepsItsTitleBlock() {
        val layout = rememberContinueWatchingLayoutForTest()
        assertEquals(layout, layout.fittingPosterCardHeight(layout.posterCardHeight + layout.posterTitleBlockHeight))
        val fitted = layout.fittingPosterCardHeight(200.dp)
        assertEquals(200.dp - layout.posterTitleBlockHeight, fitted.posterCardHeight)
        assertEquals(layout.posterTitleBlockHeight, fitted.posterTitleBlockHeight)
        val naturalAspect = layout.posterCardWidth / layout.posterCardHeight
        val fittedAspect = fitted.posterCardWidth / fitted.posterCardHeight
        assertTrue(kotlin.math.abs(naturalAspect - fittedAspect) < 0.01f)
    }

    private fun rememberContinueWatchingLayoutForTest(): ContinueWatchingLayout = ContinueWatchingLayout(
        itemGap = 20.dp,
        wideCardWidth = 400.dp,
        wideCardHeight = 160.dp,
        widePosterStripWidth = 100.dp,
        wideContentPadding = 16.dp,
        posterCardWidth = 180.dp,
        posterCardHeight = 270.dp,
        cardRadius = 18.dp,
        progressHeight = 6.dp,
        wideTitleSize = 20.sp,
        wideMetaSize = 16.sp,
        posterTitleSize = 16.sp,
        posterTitleBlockHeight = 40.dp,
        posterMetaSize = 14.sp,
        progressLabelSize = 14.sp,
        wideBadgeTextSize = 14.sp,
        posterBadgeTextSize = 12.sp,
    )
}
