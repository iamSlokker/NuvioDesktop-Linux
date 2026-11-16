package com.nuvio.app.features.home.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeTvStackedNavigationTest {
    @Test
    fun middleRowStepsDownAndUp() {
        val counts = listOf(3, 3, 3, 3, 3)
        assertEquals(3, homeTvStackedNextRow(currentRow = 2, delta = 1, rowItemCounts = counts))
        assertEquals(1, homeTvStackedNextRow(currentRow = 2, delta = -1, rowItemCounts = counts))
    }

    @Test
    fun lastRowDownStaysPut() {
        assertEquals(4, homeTvStackedNextRow(currentRow = 4, delta = 1, rowItemCounts = listOf(3, 3, 3, 3, 3)))
    }

    @Test
    fun firstRowUpStaysPut() {
        assertEquals(0, homeTvStackedNextRow(currentRow = 0, delta = -1, rowItemCounts = listOf(3, 3, 3, 3, 3)))
    }

    @Test
    fun emptyNextRowIsSkippedDown() {
        assertEquals(3, homeTvStackedNextRow(currentRow = 1, delta = 1, rowItemCounts = listOf(3, 3, 0, 3)))
    }

    @Test
    fun emptyPreviousRowIsSkippedUp() {
        assertEquals(1, homeTvStackedNextRow(currentRow = 3, delta = -1, rowItemCounts = listOf(3, 3, 0, 3)))
    }

    @Test
    fun allFollowingRowsEmptyStaysOnCurrentRow() {
        assertEquals(1, homeTvStackedNextRow(currentRow = 1, delta = 1, rowItemCounts = listOf(3, 3, 0, 0)))
    }

    @Test
    fun singleRowIsUnchangedByEitherKey() {
        assertEquals(0, homeTvStackedNextRow(currentRow = 0, delta = 1, rowItemCounts = listOf(5)))
        assertEquals(0, homeTvStackedNextRow(currentRow = 0, delta = -1, rowItemCounts = listOf(5)))
    }

    @Test
    fun noRowsReturnsZero() {
        assertEquals(0, homeTvStackedNextRow(currentRow = 0, delta = 1, rowItemCounts = emptyList()))
        assertEquals(0, homeTvStackedNextRow(currentRow = 0, delta = -1, rowItemCounts = emptyList()))
    }

    @Test
    fun currentRowThatIsItselfEmptyStillMovesToNonEmptyNeighbour() {
        assertEquals(2, homeTvStackedNextRow(currentRow = 1, delta = 1, rowItemCounts = listOf(3, 0, 4)))
    }
}

class HomeTvStackedContinueWatchingHeroTest {
    @Test
    fun linuxStackedHeroAlwaysFollowsTheFocusedCard() {
        assertTrue(continueWatchingHeroFollowsFocus(tvModeEnabled = true, stackedRowsEnabled = true, dismissed = true))
        assertTrue(continueWatchingHeroFollowsFocus(tvModeEnabled = true, stackedRowsEnabled = true, dismissed = false))
    }

    @Test
    fun otherTvModesStillHonourTheDismiss() {
        assertFalse(continueWatchingHeroFollowsFocus(tvModeEnabled = true, stackedRowsEnabled = false, dismissed = true))
        assertTrue(continueWatchingHeroFollowsFocus(tvModeEnabled = true, stackedRowsEnabled = false, dismissed = false))
    }

    @Test
    fun nonTvModeIgnoresTheDismiss() {
        assertTrue(continueWatchingHeroFollowsFocus(tvModeEnabled = false, stackedRowsEnabled = false, dismissed = true))
    }

    @Test
    fun escapeDoesNotCloseTheHeroOnLinuxStacked() {
        assertFalse(escapeClosesContinueWatchingHero(tvModeEnabled = true, stackedRowsEnabled = true, heroFocused = true))
    }

    @Test
    fun escapeStillClosesTheHeroOnOtherTvModes() {
        assertTrue(escapeClosesContinueWatchingHero(tvModeEnabled = true, stackedRowsEnabled = false, heroFocused = true))
        assertFalse(escapeClosesContinueWatchingHero(tvModeEnabled = true, stackedRowsEnabled = false, heroFocused = false))
        assertFalse(escapeClosesContinueWatchingHero(tvModeEnabled = false, stackedRowsEnabled = false, heroFocused = true))
    }
}
