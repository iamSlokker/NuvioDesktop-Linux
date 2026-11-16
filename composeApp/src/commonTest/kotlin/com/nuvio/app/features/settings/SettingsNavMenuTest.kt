package com.nuvio.app.features.settings

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of Settings menu keyboard handling: which key does what, where the menu opens,
 * and how the index moves. Compose-free on purpose, so the rules can be checked without a window;
 * what is *not* covered here is whether a desktop popup receives the key at all (see the note on
 * `SettingsMenuPanel`).
 */
class SettingsNavMenuTest {

    private fun action(key: Key, multiSelect: Boolean = false) =
        settingsMenuKeyAction(settingsNavKeyAction(key), multiSelect = multiSelect)

    @Test
    fun upAndDownMoveAndLeftAndRightAreInert() {
        assertEquals(SettingsMenuKeyAction.MoveUp, action(Key.DirectionUp))
        assertEquals(SettingsMenuKeyAction.MoveDown, action(Key.DirectionDown))
        assertEquals(SettingsMenuKeyAction.Ignore, action(Key.DirectionLeft))
        assertEquals(SettingsMenuKeyAction.Ignore, action(Key.DirectionRight))
        assertNull(action(Key.A))
    }

    @Test
    fun enterCommitsASingleSelectAndTogglesAMultiSelect() {
        assertEquals(SettingsMenuKeyAction.Commit, action(Key.Enter, multiSelect = false))
        assertEquals(SettingsMenuKeyAction.Toggle, action(Key.Enter, multiSelect = true))
        assertEquals(SettingsMenuKeyAction.Toggle, action(Key.NumPadEnter, multiSelect = true))
    }

    @Test
    fun escapeAndBackCloseWithoutChoosing() {
        assertEquals(SettingsMenuKeyAction.Close, action(Key.Escape))
        assertEquals(SettingsMenuKeyAction.Close, action(Key.Backspace))
    }

    @Test
    fun onlySelectOnTheTriggerOpensTheMenu() {
        assertEquals(SettingsMenuKeyAction.Open, settingsMenuTriggerAction(SettingsNavKeyAction.Activate))
        assertNull(settingsMenuTriggerAction(SettingsNavKeyAction.Back))
        assertNull(settingsMenuTriggerAction(SettingsNavKeyAction.Down))
        assertNull(settingsMenuTriggerAction(null))
    }

    @Test
    fun theMenuOpensOnTheSelectionOrTheFirstOption() {
        assertEquals(2, settingsMenuInitialIndex(selected = 2, count = 5))
        // Nothing selected (or a value that matched no option): the first option.
        assertEquals(0, settingsMenuInitialIndex(selected = -1, count = 5))
        assertEquals(0, settingsMenuInitialIndex(selected = 9, count = 5))
        assertEquals(-1, settingsMenuInitialIndex(selected = 0, count = 0))
    }

    @Test
    fun theIndexClampsAtBothEnds() {
        assertEquals(0, settingsMenuIndexAfterMove(index = 0, count = 3, direction = -1))
        assertEquals(2, settingsMenuIndexAfterMove(index = 2, count = 3, direction = 1))
        assertEquals(1, settingsMenuIndexAfterMove(index = 0, count = 3, direction = 1))
        assertEquals(-1, settingsMenuIndexAfterMove(index = 0, count = 0, direction = 1))
    }

    @Test
    fun enterOnAMultiSelectTogglesAndStaysOpen() {
        val open = SettingsMenuState(index = 1, expanded = true)
        val afterToggle = settingsMenuReduce(open, SettingsMenuKeyAction.Toggle, count = 4, multiSelect = true)
        assertTrue(afterToggle.expanded, "a multi-select menu must stay open after Select")
        assertEquals(1, afterToggle.index)
    }

    @Test
    fun enterOnASingleSelectCommitsAndCloses() {
        val open = SettingsMenuState(index = 1, expanded = true)
        val afterCommit = settingsMenuReduce(open, SettingsMenuKeyAction.Commit, count = 4, multiSelect = false)
        assertFalse(afterCommit.expanded)
        assertEquals(1, afterCommit.index)
    }

    @Test
    fun escapeClosesAndLeavesThePageAlone() {
        val open = SettingsMenuState(index = 2, expanded = true)
        val afterClose = settingsMenuReduce(open, SettingsMenuKeyAction.Close, count = 4, multiSelect = false)
        assertFalse(afterClose.expanded)
        // Close changes the menu's visibility only: the index the page would act on is untouched,
        // and nothing in the mapping asks the page to do anything.
        assertEquals(2, afterClose.index)
        // A multi-select closes on Escape just the same, without toggling.
        assertFalse(
            settingsMenuReduce(open, SettingsMenuKeyAction.Close, count = 4, multiSelect = true).expanded,
        )
    }

    @Test
    fun leftRightAndUpDownAtTheEndsDoNotCloseTheMenu() {
        val open = SettingsMenuState(index = 0, expanded = true)
        assertEquals(open, settingsMenuReduce(open, SettingsMenuKeyAction.Ignore, count = 4, multiSelect = false))
        assertEquals(open, settingsMenuReduce(open, SettingsMenuKeyAction.MoveUp, count = 4, multiSelect = false))
    }
}