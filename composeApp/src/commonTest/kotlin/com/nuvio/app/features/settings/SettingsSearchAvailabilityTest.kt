package com.nuvio.app.features.settings

import com.nuvio.app.features.home.HomeDisplayMode
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Home Layout page hides the other modes' rows instead of drawing them disabled, so the search
 * index must not offer a destination that is not on the page. These are the gates from
 * HomescreenSettingsPage: each row below is drawn only in the modes listed.
 */
class SettingsSearchAvailabilityTest {

    private fun available(
        key: String,
        mode: HomeDisplayMode,
        autoFraming: Boolean = true,
        rowDots: Boolean = false,
    ) = settingsSearchEntryAvailable(
        key = key,
        homeDisplayMode = mode,
        adaptiveHeroAutoFraming = autoFraming,
        tvRowDotsEnabled = rowDots,
    )

    private val tvOnly = listOf(
        "home-tv-row-transition",
        "home-tv-full-backdrop",
        "home-tv-row-dots",
    )

    @Test
    fun automaticFramingStaysAdaptiveOnly() {
        assertTrue(available("home-adaptive-hero-auto-framing", HomeDisplayMode.Adaptive))
        assertFalse(available("home-adaptive-hero-auto-framing", HomeDisplayMode.Basic))
        assertFalse(available("home-adaptive-hero-auto-framing", HomeDisplayMode.TvMode))
    }

    @Test
    fun theManualPositionSliderWaitsForAutomaticFramingToBeOffInEveryMode() {
        assertTrue(available("home-adaptive-hero-position", HomeDisplayMode.Adaptive, autoFraming = false))
        assertFalse(available("home-adaptive-hero-position", HomeDisplayMode.Adaptive, autoFraming = true))
        // Auto-framing and its manual position are Adaptive-only rows again.
        assertFalse(available("home-adaptive-hero-position", HomeDisplayMode.Basic, autoFraming = false))
        assertFalse(available("home-adaptive-hero-position", HomeDisplayMode.TvMode, autoFraming = false))
    }

    @Test
    fun tvRowsAreOfferedOnlyInTvMode() {
        for (key in tvOnly) {
            assertTrue(available(key, HomeDisplayMode.TvMode), "$key in TV")
            assertFalse(available(key, HomeDisplayMode.Basic), "$key in Basic")
            assertFalse(available(key, HomeDisplayMode.Adaptive), "$key in Adaptive")
        }
    }

    @Test
    fun theDotPositionWaitsForTheDots() {
        assertTrue(available("home-tv-row-dots-anchor", HomeDisplayMode.TvMode, rowDots = true))
        assertFalse(available("home-tv-row-dots-anchor", HomeDisplayMode.TvMode, rowDots = false))
        assertFalse(available("home-tv-row-dots-anchor", HomeDisplayMode.Basic, rowDots = true))
    }

    @Test
    fun basicDoesNotOfferTheTrailerAreaChoiceItDoesNotDraw() {
        assertFalse(available("home-hero-trailer", HomeDisplayMode.Basic))
        assertTrue(available("home-hero-trailer", HomeDisplayMode.Adaptive))
        assertTrue(available("home-hero-trailer", HomeDisplayMode.TvMode))
    }

    @Test
    fun tvModeDoesNotOfferPointerOnlyRows() {
        for (key in listOf("home-smooth-scrolling", "home-hover-preview")) {
            assertFalse(available(key, HomeDisplayMode.TvMode), "$key in TV")
            assertTrue(available(key, HomeDisplayMode.Basic), "$key in Basic")
            assertTrue(available(key, HomeDisplayMode.Adaptive), "$key in Adaptive")
        }
    }

    @Test
    fun rowsDrawnInEveryModeStayIndexed() {
        val everywhere = listOf(
            "home-hero",
            "home-hero-badge-count",
            "home-hero-badge-position",
            "home-hero-badge-size",
            "home-hero-badge-priority",
            "home-hero-release-status",
            "home-display-mode",
            "hero-backdrop-crossfade",
        )
        for (mode in HomeDisplayMode.entries) {
            for (key in everywhere) {
                assertTrue(available(key, mode), "$key in $mode")
            }
        }
    }
}
