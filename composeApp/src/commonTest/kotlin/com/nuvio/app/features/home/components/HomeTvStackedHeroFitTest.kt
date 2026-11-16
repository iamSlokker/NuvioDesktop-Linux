package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Content width of a 16:9 window of height [h], with the 76 dp rail taken out. */
private fun contentWidth(h: Float) = h * 16f / 9f - 76f

/** Fixture logo height: the rendered logo is measured at runtime; 13% of the window stands in for it. */
private fun fixtureLogoHeightDp(h: Float) = 0.13f * h

private fun fitFor(h: Float, guiScale: Float, hasRatings: Boolean = true, hasFooter: Boolean = true) =
    homeTvStackedMetrics(widthDp = contentWidth(h), heightDp = h, guiScale = guiScale).let { m ->
        homeTvStackedHeroFit(
            metadataTopDp = homeTvStackedMetadataTopDp(m, logoHeightDp = fixtureLogoHeightDp(h)),
            limitDp = m.contentTopDp + m.contentHeightDp,
            lineHeightDp = HOME_TV_STACKED_BODY_LARGE_LINE_HEIGHT_DP * m.textScale,
            hasGenreLine = true,
            hasRatings = hasRatings,
            hasFooter = hasFooter,
            hasSynopsis = true,
        )
    }

class HomeTvStackedHeroFitTest {
    @Test
    fun shortWindowAtDefaultScaleKeepsEveryRow() {
        // 540 at 100%: about 301 dp of column below the metadata line, at roughly 14.5 dp a line,
        // so every optional row fits and the synopsis reaches its five-line cap. The fixed 14 dp
        // logo gap and the 10% smaller text (HomeTvStackedMetrics.kt) are what leave the room.
        val fit = fitFor(540f, 1f)
        assertEquals(HomeTvStackedHeroFit(showRatings = true, showFooter = true, synopsisLines = 5), fit)
    }

    @Test
    fun fullHdAtOneAndAQuarterScaleKeepsTheRowsAndFourLines() {
        // 1080 at 125%: the GUI scale grows the pitch and the logo gap, so the fit gives up one
        // synopsis line before either optional row; ratings and footer both stay.
        val fit = fitFor(1080f, 1.25f)
        assertEquals(HomeTvStackedHeroFit(showRatings = true, showFooter = true, synopsisLines = 4), fit)
    }

    @Test
    fun fullHdAtOneAndAHalfScaleKeepsTheRowsAndThreeLines() {
        // 1080 at 150%: three lines remain, still with both optional rows. The rows only drop on
        // a taller logo or a narrower window, which the direct fit tests below cover.
        val fit = fitFor(1080f, 1.5f)
        assertEquals(HomeTvStackedHeroFit(showRatings = true, showFooter = true, synopsisLines = 3), fit)
    }

    @Test
    fun footerDropsBeforeRatingsWhenTheColumnIsTight() {
        // A pure fit at a 20 dp line: synopsis top 32, footer + ratings need 59 dp of extras, so a
        // 115 dp limit keeps two lines only once the footer has gone.
        val fit = homeTvStackedHeroFit(
            metadataTopDp = 0f,
            limitDp = 115f,
            lineHeightDp = 20f,
            hasGenreLine = true,
            hasRatings = true,
            hasFooter = true,
            hasSynopsis = true,
        )
        assertEquals(HomeTvStackedHeroFit(showRatings = true, showFooter = false, synopsisLines = 2), fit)
    }

    @Test
    fun ratingsDropAfterTheFooterWhenEvenTheyDoNotFit() {
        val fit = homeTvStackedHeroFit(
            metadataTopDp = 0f,
            limitDp = 90f,
            lineHeightDp = 20f,
            hasGenreLine = true,
            hasRatings = true,
            hasFooter = true,
            hasSynopsis = true,
        )
        assertEquals(HomeTvStackedHeroFit(showRatings = false, showFooter = false, synopsisLines = 2), fit)
    }

    @Test
    fun synopsisNeverDropsBelowTheMinimum() {
        for (h in listOf(540f, 720f, 1080f, 2160f)) {
            for (scale in listOf(1f, 1.25f, 1.5f)) {
                val fit = fitFor(h, scale)
                assertTrue(fit.synopsisLines >= HOME_TV_STACKED_SYNOPSIS_MIN_LINES, "h=$h scale=$scale")
                assertTrue(fit.synopsisLines <= HOME_TV_STACKED_SYNOPSIS_MAX_LINES, "h=$h scale=$scale")
            }
        }
    }

    @Test
    fun rowsAreOnlyDroppedNeverAdded() {
        // A hero with no ratings or footer keeps neither.
        val fit = fitFor(1080f, 1f, hasRatings = false, hasFooter = false)
        assertEquals(false, fit.showRatings)
        assertEquals(false, fit.showFooter)
    }

    @Test
    fun fitRespectsTheColumnLimitWhenTheSynopsisIsKept() {
        // The synopsis plus the optional rows that remain must end above the band.
        for (h in listOf(540f, 720f, 1080f, 2160f)) {
            for (scale in listOf(1f, 1.25f, 1.5f)) {
                val m = homeTvStackedMetrics(widthDp = contentWidth(h), heightDp = h, guiScale = scale)
                val fit = fitFor(h, scale)
                val lineH = HOME_TV_STACKED_BODY_LARGE_LINE_HEIGHT_DP * m.textScale
                // Metadata line (genre, STACKED_GENRE_ROW_LINES), then the spacer, then the synopsis.
                val metadataTop = homeTvStackedMetadataTopDp(m, logoHeightDp = fixtureLogoHeightDp(h))
                val synTop = metadataTop + STACKED_GENRE_ROW_LINES * lineH + HOME_TV_STACKED_ROW_SPACER_DP
                val synBottom = synTop + fit.synopsisLines * lineH
                assertTrue(synBottom <= m.contentTopDp + m.contentHeightDp + 0.01f || fit.synopsisLines == HOME_TV_STACKED_SYNOPSIS_MIN_LINES,
                    "h=$h scale=$scale synBottom=$synBottom")
            }
        }
    }
}
