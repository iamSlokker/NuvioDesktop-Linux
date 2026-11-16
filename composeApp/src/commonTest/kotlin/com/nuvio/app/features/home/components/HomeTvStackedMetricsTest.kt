package com.nuvio.app.features.home.components

import com.nuvio.app.core.ui.BalancedPosterCardWidthDp
import com.nuvio.app.core.ui.PosterLandscapeAspectRatio
import com.nuvio.app.core.ui.landscapePosterWidth
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun near(expected: Float, actual: Float, tolerance: Float = 0.1f) =
    assertTrue(abs(expected - actual) <= tolerance, "expected $expected, was $actual")

class HomeTvStackedMetricsTest {
    @Test
    fun referenceWindowMatchesTargets() {
        val m = homeTvStackedMetrics(widthDp = 1136f, heightDp = 639f)
        near(338.7f, m.heroHeightDp)
        near(5.68f, m.sidePaddingDp, 0.01f)
        near(138.0f, m.rowPitchDp) // 0.27 of the height, at the 125% UI scale it is authored for.
        // 0.94 before the 10% stacked text reduction; 0.9 times it, at 100% GUI scale.
        near(0.848f, m.textScale, 0.01f)
    }

    @Test
    fun fullHdWindowMatchesTargets() {
        val m = homeTvStackedMetrics(widthDp = 1920f, heightDp = 1080f)
        near(572.4f, m.heroHeightDp)
        near(9.6f, m.sidePaddingDp, 0.05f)
        near(233.3f, m.rowPitchDp)
    }

    @Test
    fun heroIsFiftyThreePercentAndRowOneLandsAtSeventyNinePercent() {
        for (h in listOf(360f, 720f, 900f, 1080f, 1440f, 2160f)) {
            val m = homeTvStackedMetrics(widthDp = h * 16f / 9f, heightDp = h)
            if (h * 0.53f >= 300f) near(0.53f, m.heroHeightDp / h, 0.001f)
            // 0.27 of the height reduced to the authored UI scale: 0.53 + 0.216.
            if (h * 0.53f >= 300f) near(0.746f, (m.heroHeightDp + m.rowPitchDp) / h, 0.001f)
        }
    }

    @Test
    fun sidePaddingIsHalfAPercentOfWidth() {
        near(0.005f, homeTvStackedMetrics(widthDp = 1000f, heightDp = 600f).sidePaddingDp / 1000f, 0.0001f)
    }

    @Test
    fun cardInsetKeepsTheFormerFivePointFourPercent() {
        // Card widths read this inset, so tightening the content inset must not widen the cards.
        near(0.054f, homeTvStackedMetrics(widthDp = 1000f, heightDp = 600f).cardInsetDp / 1000f, 0.0001f)
    }

    @Test
    fun heroHasAThreeHundredDpFloorOnShortWindows() {
        assertEquals(300f, homeTvStackedMetrics(widthDp = 400f, heightDp = 400f).heroHeightDp)
    }

    @Test
    fun textScaleFollowsWidthAndTheSynopsisLineHeight() {
        // 47 px of a 2576 px reference is 0.01824 of width; bodyLarge's 22 dp line is the base.
        // The 10% stacked reduction makes these 0.9 times 1.026 and 1.592.
        near(0.923f, homeTvStackedMetrics(widthDp = 1237f, heightDp = 696f).textScale, 0.001f)
        near(1.433f, homeTvStackedMetrics(widthDp = 1920f, heightDp = 1080f).textScale, 0.001f)
    }

    @Test
    fun textScaleIsNinetyPercentOfThePreviousScaleAtEveryGuiScale() {
        // The previous scale, without the stacked reduction, at the same width and GUI scale.
        fun previousScale(widthDp: Float, guiScale: Float) =
            0.01824f * widthDp / 22f * guiScale
        for (gui in listOf(1f, 1.25f, 1.5f)) {
            for (width in listOf(1136f, 1237f, 1920f)) {
                val m = homeTvStackedMetrics(widthDp = width, heightDp = 720f, guiScale = gui)
                near(0.9f * previousScale(width, gui), m.textScale, 0.0001f)
            }
        }
    }

    @Test
    fun logoIsTwentyFourPercentOfWindowWidthAtTheAuthoredUiScale() {
        near(237.5f, homeTvStackedMetrics(widthDp = 1237f, heightDp = 696f).logoWidthDp, 0.05f)
        near(368.6f, homeTvStackedMetrics(widthDp = 1920f, heightDp = 1080f).logoWidthDp, 0.05f)
    }

    @Test
    fun logoAndRowPitchFollowTheGuiScale() {
        val base = homeTvStackedMetrics(widthDp = 1920f, heightDp = 1080f, guiScale = 1.25f)
        val smaller = homeTvStackedMetrics(widthDp = 1920f, heightDp = 1080f, guiScale = 0.7f)
        near(base.logoWidthDp * (0.7f / 1.25f), smaller.logoWidthDp, 0.05f)
        near(base.rowPitchDp * (0.7f / 1.25f), smaller.rowPitchDp, 0.05f)
        near(460.8f * 0.7f / 1.25f, smaller.logoWidthDp, 0.05f)
    }

    @Test
    fun rowBandClearsTheBackdropAndSitsAtLeastFiftyEightPercentDown() {
        // The backdrop bottom is hero - contentBottomPadding + 72 dp (IMMERSIVE_HERO_LANDSCAPE_SHELF_OVERLAP, private).
        for (h in listOf(639f, 720f, 900f, 1080f, 1440f)) {
            val m = homeTvStackedMetrics(widthDp = h * 16f / 9f, heightDp = h)
            val heroHeight = (h * 0.53f).coerceAtLeast(300f)
            val pad = (h * 0.025f).coerceIn(12f, 28f)
            val backdropBottom = heroHeight - pad + 72f
            assertTrue(m.rowBandTopDp >= h * 0.58f - 0.1f, "58% floor at h=$h")
            assertTrue(m.rowBandTopDp >= backdropBottom - 0.1f, "backdrop bottom at h=$h")
        }
    }

    @Test
    fun rowBandTopMatchesTheWorkedValues() {
        near(394.7f, homeTvStackedMetrics(widthDp = 1136f, heightDp = 639f).rowBandTopDp, 0.2f)
        near(626.4f, homeTvStackedMetrics(widthDp = 1920f, heightDp = 1080f).rowBandTopDp, 0.2f)
    }

    @Test
    fun heroTextStartsAtThirteenPercentAndStopsAboveTheRowBand() {
        for (h in listOf(639f, 720f, 900f, 1080f)) {
            val m = homeTvStackedMetrics(widthDp = h * 16f / 9f, heightDp = h)
            near(0.13f * h, m.contentTopDp, 0.1f)
            assertTrue(m.contentTopDp + m.contentHeightDp <= m.rowBandTopDp - 11.9f, "h=$h")
        }
        val reference = homeTvStackedMetrics(widthDp = 1136f, heightDp = 639f)
        near(reference.rowBandTopDp - 12f - reference.contentTopDp, reference.contentHeightDp, 0.2f)
    }

    @Test
    fun medalSitsClearAboveTheBandTop() {
        val diameter = 46f
        val bandTop = 394.7f
        val top = homeTvStackedMedalTopDp(bandTop, diameter)
        assertEquals(bandTop - diameter - HOME_TV_STACKED_MEDAL_BAND_GAP_DP, top, 0.01f)
        assertTrue(top + diameter < bandTop, "the medal still overlaps the row band")
    }

    @Test
    fun rowListStartsTheFadeAboveTheBandAndHeroTextClearsTheRail() {
        val m = homeTvStackedMetrics(widthDp = 1280f, heightDp = 720f)
        near(435.6f, m.rowBandTopDp, 0.2f)
        near(24f, m.rowBandFadeDp)
        near(411.6f, m.rowListTopDp, 0.2f)
        // 0.5% of the content width, measured from the rail's right edge.
        near(6.4f, m.heroTextLeftDp, 0.01f)
        near(10f, homeTvStackedMetrics(widthDp = 2000f, heightDp = 1125f).heroTextLeftDp, 0.01f)
    }

    @Test
    fun logoMetadataGapIsAFixedFourteenDpBelowTheRenderedLogo() {
        val m = homeTvStackedMetrics(widthDp = 1280f, heightDp = 720f)
        near(HOME_TV_STACKED_LOGO_METADATA_GAP_DP, m.logoMetadataGapDp, 0.01f)
        for (logoHeight in listOf(0f, 90f, 150f)) {
            val metadataTop = homeTvStackedMetadataTopDp(m, logoHeightDp = logoHeight)
            near(logoHeight + HOME_TV_STACKED_LOGO_METADATA_GAP_DP, metadataTop - m.contentTopDp, 0.01f)
        }
    }

    @Test
    fun noReservedLogoSlotSoAZeroHeightLogoPutsTheMetadataUnderTheGapAlone() {
        val m = homeTvStackedMetrics(widthDp = 1280f, heightDp = 720f)
        near(m.contentTopDp + HOME_TV_STACKED_LOGO_METADATA_GAP_DP, homeTvStackedMetadataTopDp(m, logoHeightDp = 0f), 0.01f)
    }

    @Test
    fun guiScaleEnlargesTextAndLeavesPositionsAlone() {
        val base = homeTvStackedMetrics(widthDp = 1280f, heightDp = 720f)
        val scaled = homeTvStackedMetrics(widthDp = 1280f, heightDp = 720f, guiScale = 1.5f)
        near(base.textScale * 1.5f, scaled.textScale, 0.0001f)
        assertEquals(base.rowBandTopDp, scaled.rowBandTopDp)
        assertEquals(base.contentTopDp, scaled.contentTopDp)
        assertEquals(base.sidePaddingDp, scaled.sidePaddingDp)
    }

    @Test
    fun contentBottomPaddingIsClamped() {
        assertEquals(12f, homeTvStackedMetrics(widthDp = 300f, heightDp = 200f).contentBottomPaddingDp)
        assertEquals(28f, homeTvStackedMetrics(widthDp = 3000f, heightDp = 2000f).contentBottomPaddingDp)
    }

    @Test
    fun theHeroHeightSliderMovesTheStackedRowsAcrossItsWholeRange() {
        // 125% is the existing default; the top of the slider is where the reference screenshot
        // sits, with the artwork running down into the first row.
        near(1.0f, tvStackedHeroHeightMultiplier(1.25f), 0.001f)
        near(1.40f, tvStackedHeroHeightMultiplier(1.75f), 0.001f)
        near(0.85f, tvStackedHeroHeightMultiplier(0.50f), 0.001f)

        val standard = homeTvStackedMetrics(
            widthDp = 1920f,
            heightDp = 1080f,
            heroHeightMultiplier = tvStackedHeroHeightMultiplier(1.25f),
        )
        val tallest = homeTvStackedMetrics(
            widthDp = 1920f,
            heightDp = 1080f,
            heroHeightMultiplier = tvStackedHeroHeightMultiplier(1.75f),
        )
        assertTrue(tallest.rowBandTopDp > standard.rowBandTopDp + 100f)
    }
}

/** Card scale must not move: these constants are the contract the stacked layout relies on. */
class HomeTvStackedCardScaleTest {
    @Test
    fun landscapeCardScaleIsUnchanged() {
        assertEquals(126, BalancedPosterCardWidthDp)
        near(206.18f, landscapePosterWidth(BalancedPosterCardWidthDp).value, 0.05f)
        near(247.4f, continueWatchingLandscapeCardWidth(BalancedPosterCardWidthDp).value, 0.05f)
        assertEquals(1.77f, PosterLandscapeAspectRatio)
    }
}
