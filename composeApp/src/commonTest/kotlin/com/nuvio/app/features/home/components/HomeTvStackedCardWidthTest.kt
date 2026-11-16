package com.nuvio.app.features.home.components

import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.BalancedPosterCardWidthDp
import com.nuvio.app.core.ui.ExtraLargePosterCardWidthDp
import com.nuvio.app.core.ui.landscapePosterHeightForWidth
import com.nuvio.app.core.ui.landscapePosterWidth
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Linux TV Mode stacked rows: landscape CW/Up Next/Upcoming card is 75% of the TV card, rounded to 5 dp. */
class HomeTvStackedCardWidthTest {
    private val currentTvCardWidth = continueWatchingLandscapeCardWidth(ExtraLargePosterCardWidthDp).value

    @Test
    fun constantIsSeventyFivePercentOfCurrentTvCardRoundedToFiveDp() {
        val expected = ((currentTvCardWidth * 0.75f) / 5f).roundToInt() * 5
        assertEquals(expected.toFloat(), HOME_TV_STACKED_LANDSCAPE_CARD_WIDTH_DP)
        assertEquals(310f, HOME_TV_STACKED_LANDSCAPE_CARD_WIDTH_DP)
    }

    @Test
    fun constantIsWithinOnePercentOfTheSeventyFivePercentTarget() {
        val ratio = HOME_TV_STACKED_LANDSCAPE_CARD_WIDTH_DP / currentTvCardWidth
        assertTrue(abs(ratio - 0.75f) < 0.01f, "ratio was $ratio")
    }

    @Test
    fun cardHeightStaysSixteenByNineOfNewWidth() {
        val width = HOME_TV_STACKED_LANDSCAPE_CARD_WIDTH_DP.dp
        assertEquals(width.value / 1.77f, landscapePosterHeightForWidth(width).value, 0.01f)
    }

    @Test
    fun stackedRowsProvideTheWidthAndOtherSurfacesDoNot() {
        assertEquals(HOME_TV_STACKED_LANDSCAPE_CARD_WIDTH_DP.dp, homeTvStackedContinueWatchingCardWidth(true))
        assertNull(homeTvStackedContinueWatchingCardWidth(false))
    }

    @Test
    fun stackedCatalogLandscapeRowsResolveToTheContinueWatchingWidth() {
        val stacked = homeTvStackedContinueWatchingCardWidth(true)
        for (base in listOf(BalancedPosterCardWidthDp, ExtraLargePosterCardWidthDp)) {
            // Catalog and Discover rows derive landscapePosterWidth; Continue Watching derives its own.
            assertEquals(stacked, homeTvStackedLandscapeRowWidth(stacked, landscapePosterWidth(base)), "catalog, base=$base")
            assertEquals(stacked, homeTvStackedLandscapeRowWidth(stacked, continueWatchingLandscapeCardWidth(base)), "CW, base=$base")
        }
    }

    @Test
    fun otherPathsKeepTheirDerivedLandscapeWidths() {
        val none = homeTvStackedContinueWatchingCardWidth(false)
        for (base in listOf(BalancedPosterCardWidthDp, ExtraLargePosterCardWidthDp)) {
            assertEquals(landscapePosterWidth(base), homeTvStackedLandscapeRowWidth(none, landscapePosterWidth(base)))
            assertEquals(
                continueWatchingLandscapeCardWidth(base),
                homeTvStackedLandscapeRowWidth(none, continueWatchingLandscapeCardWidth(base)),
            )
        }
    }
}
