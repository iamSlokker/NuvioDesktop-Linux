package com.nuvio.app.features.settings

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import com.nuvio.app.core.ui.DialogBackRegistry
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsNavTest {
    @AfterTest
    fun clearBridge() {
        SettingsBackBridge.clearForTest()
        DialogBackRegistry.clear()
    }

    @Test
    fun arrowsEnterAndBackMapToSettingsActions() {
        assertEquals(SettingsNavKeyAction.Up, settingsNavKeyAction(Key.DirectionUp))
        assertEquals(SettingsNavKeyAction.Down, settingsNavKeyAction(Key.DirectionDown))
        assertEquals(SettingsNavKeyAction.Left, settingsNavKeyAction(Key.DirectionLeft))
        assertEquals(SettingsNavKeyAction.Right, settingsNavKeyAction(Key.DirectionRight))
        assertEquals(SettingsNavKeyAction.Activate, settingsNavKeyAction(Key.Enter))
        assertEquals(SettingsNavKeyAction.Activate, settingsNavKeyAction(Key.NumPadEnter))
        assertEquals(SettingsNavKeyAction.Back, settingsNavKeyAction(Key.Escape))
        assertEquals(SettingsNavKeyAction.Back, settingsNavKeyAction(Key.Backspace))
    }

    @Test
    fun otherKeysAreNotSettingsActions() {
        assertNull(settingsNavKeyAction(Key.A))
        assertNull(settingsNavKeyAction(Key.Spacebar))
        assertNull(settingsNavKeyAction(Key.Unknown))
    }

    @Test
    fun settingsKeysAreOffWhileATextFieldHasFocus() {
        assertTrue(settingsNavKeysActive(textInputFocused = false))
        assertFalse(settingsNavKeysActive(textInputFocused = true))
    }

    @Test
    fun backGoesToTheAnchorThenTheParentThenHome() {
        assertEquals(
            SettingsBackTarget.Page(SettingsPage.Streams),
            settingsBackTarget(anchorPage = SettingsPage.Streams, parentPage = SettingsPage.Integrations),
        )
        assertEquals(
            SettingsBackTarget.Page(SettingsPage.Integrations),
            settingsBackTarget(anchorPage = null, parentPage = SettingsPage.Integrations),
        )
        assertEquals(SettingsBackTarget.Home, settingsBackTarget(anchorPage = null, parentPage = null))
    }

    @Test
    fun theRootEntersThePageOnlyWhenItHasFocusAndNoTextIsActive() {
        val arrow = SettingsNavKeyAction.Down
        assertTrue(settingsRootEntersPage(rootFocused = true, textInputActive = false, searchFocused = false, action = arrow))
        assertTrue(settingsRootEntersPage(rootFocused = true, textInputActive = false, searchFocused = false, action = SettingsNavKeyAction.Activate))
        // A key that bubbles up from a focused child must not walk focus through the top bar.
        assertFalse(settingsRootEntersPage(rootFocused = false, textInputActive = false, searchFocused = false, action = arrow))
        assertFalse(settingsRootEntersPage(rootFocused = true, textInputActive = true, searchFocused = false, action = arrow))
        assertFalse(settingsRootEntersPage(rootFocused = true, textInputActive = false, searchFocused = true, action = arrow))
        assertFalse(settingsRootEntersPage(rootFocused = true, textInputActive = false, searchFocused = false, action = SettingsNavKeyAction.Back))
        assertFalse(settingsRootEntersPage(rootFocused = true, textInputActive = false, searchFocused = false, action = null))
    }

    @Test
    fun bridgeReportsHandledOnlyWhilePageRegistered() {
        assertFalse(SettingsBackBridge.handle(), "nothing registered: Back is not handled here")
        var calls = 0
        val token = Any()
        SettingsBackBridge.register(token) {
            calls += 1
            true
        }
        assertTrue(SettingsBackBridge.handle())
        assertEquals(1, calls)
        SettingsBackBridge.unregister(token)
        assertFalse(SettingsBackBridge.handle())
    }

    @Test
    fun bridgeLetsAPageDeclineSoBackGoesHome() {
        SettingsBackBridge.register(Any()) { false }
        assertFalse(SettingsBackBridge.handle())
    }

    @Test
    fun backClosesAnOpenDialogBeforeThePageNavigates() {
        DialogBackRegistry.clear()
        val order = mutableListOf<String>()
        SettingsBackBridge.register(Any()) {
            order += "page"
            true
        }
        DialogBackRegistry.register(Any()) { order += "dialog" }
        assertTrue(SettingsBackBridge.handle(), "the dialog takes the first Back")
        assertEquals(listOf("dialog"), order)
        assertTrue(SettingsBackBridge.handle(), "with no dialog the page goes up a level")
        assertEquals(listOf("dialog", "page"), order)
    }

    @Test
    fun unregisteringTheInnermostLayerRestoresTheOuterOne() {
        DialogBackRegistry.clear()
        val order = mutableListOf<String>()
        val pageToken = Any()
        val editorToken = Any()
        SettingsBackBridge.register(pageToken) {
            order += "page"
            true
        }
        SettingsBackBridge.register(editorToken) {
            order += "editor"
            true
        }
        assertTrue(SettingsBackBridge.handle(), "the editor takes the first Back")
        assertEquals(listOf("editor"), order)
        SettingsBackBridge.unregister(editorToken)
        assertTrue(SettingsBackBridge.handle(), "after the editor closes the page answers")
        assertEquals(listOf("editor", "page"), order)
        SettingsBackBridge.unregister(pageToken)
        assertFalse(SettingsBackBridge.handle())
    }

    @Test
    fun backClosesTheInnermostDialogFirst() {
        DialogBackRegistry.clear()
        val order = mutableListOf<String>()
        val outer = Any()
        val inner = Any()
        DialogBackRegistry.register(outer) { order += "outer" }
        DialogBackRegistry.register(inner) { order += "inner" }
        assertTrue(DialogBackRegistry.dismissTop())
        assertEquals(listOf("inner"), order)
        assertTrue(DialogBackRegistry.dismissTop())
        assertEquals(listOf("inner", "outer"), order)
        assertFalse(DialogBackRegistry.dismissTop(), "nothing left to close")
    }

    @Test
    fun contentScrollDelegatesWhileRegisteredAndStopsAfter() {
        var direction = 0
        SettingsContentScroll.register { direction = it }
        assertTrue(SettingsContentScroll.scrollFurther(1))
        assertEquals(1, direction)
        assertTrue(SettingsContentScroll.scrollFurther(-1))
        assertEquals(-1, direction)
        SettingsContentScroll.unregister()
        assertFalse(SettingsContentScroll.scrollFurther(1), "nothing registered: nothing scrolls")
    }

    @Test
    fun arrowActionsCarryTheFocusDirectionTheyStandFor() {
        assertEquals(FocusDirection.Up, SettingsNavKeyAction.Up.focusDirection())
        assertEquals(FocusDirection.Down, SettingsNavKeyAction.Down.focusDirection())
        assertEquals(FocusDirection.Left, SettingsNavKeyAction.Left.focusDirection())
        assertEquals(FocusDirection.Right, SettingsNavKeyAction.Right.focusDirection())
        assertNull(SettingsNavKeyAction.Activate.focusDirection())
        assertNull(SettingsNavKeyAction.Back.focusDirection())
    }

    @Test
    fun steppedSliderLandsOnTheStepGrid() {
        // Nine steps between the ends is ten intervals, so a whole-number range steps by one.
        val range = 0f..10f
        assertEquals(6f, settingsSliderAdjustedValue(5f, range, steps = 9, direction = 1))
        assertEquals(4f, settingsSliderAdjustedValue(5f, range, steps = 9, direction = -1))
        // A value that sits between two steps is rounded onto one before it is stepped.
        assertEquals(5f, settingsSliderAdjustedValue(4.4f, range, steps = 9, direction = 1))
        // The ends stop rather than wrapping.
        assertEquals(10f, settingsSliderAdjustedValue(10f, range, steps = 9, direction = 1))
        assertEquals(0f, settingsSliderAdjustedValue(0f, range, steps = 9, direction = -1))
        // A value outside the range is brought inside it first.
        assertEquals(10f, settingsSliderAdjustedValue(20f, range, steps = 9, direction = 1))
    }

    @Test
    fun continuousSliderMovesATwentiethOfItsRange() {
        val range = 0f..1f
        assertEquals(0.55f, settingsSliderAdjustedValue(0.5f, range, steps = 0, direction = 1), 1e-4f)
        assertEquals(0.45f, settingsSliderAdjustedValue(0.5f, range, steps = 0, direction = -1), 1e-4f)
        assertEquals(1f, settingsSliderAdjustedValue(0.99f, range, steps = 0, direction = 1))
        assertEquals(0f, settingsSliderAdjustedValue(0f, range, steps = 0, direction = -1))
    }

    @Test
    fun sliderWithNoSpanDoesNotMove() {
        val range = 3f..3f
        assertEquals(3f, settingsSliderAdjustedValue(3f, range, steps = 4, direction = 1))
        assertEquals(3f, settingsSliderAdjustedValue(3f, range, steps = 0, direction = -1))
        assertEquals(5f, settingsSliderAdjustedValue(5f, 0f..10f, steps = 9, direction = 0))
    }

    @Test
    fun rangeSliderMovesBothEndsTogetherAndStopsAtTheEdges() {
        val range = 0f..10f
        assertEquals(3f to 6f, settingsRangeShifted(2f, 5f, range, steps = 9, direction = 1))
        assertEquals(1f to 4f, settingsRangeShifted(2f, 5f, range, steps = 9, direction = -1))
        // A band already against an edge stays put, so the key falls through to focus traversal.
        assertEquals(0f to 3f, settingsRangeShifted(0f, 3f, range, steps = 9, direction = -1))
        assertEquals(7f to 10f, settingsRangeShifted(7f, 10f, range, steps = 9, direction = 1))
        // An inverted band (a page mid-update) is left alone.
        assertEquals(6f to 4f, settingsRangeShifted(6f, 4f, range, steps = 9, direction = 1))
    }

    @Test
    fun selectOnAChoiceRowTakesTheNextOptionAndWraps() {
        val options = listOf(
            SettingsChoiceOption("a", "A"),
            SettingsChoiceOption("b", "B"),
            SettingsChoiceOption("c", "C"),
        )
        assertEquals("b", settingsNextChoiceOption(options, "a"))
        assertEquals("c", settingsNextChoiceOption(options, "b"))
        assertEquals("a", settingsNextChoiceOption(options, "c"))
        // A selection that matches no option (a page swapping its list) starts from the top.
        assertEquals("a", settingsNextChoiceOption(options, "gone"))
        assertNull(settingsNextChoiceOption(emptyList(), "a"))
        // A two-option row is a toggle: Select takes the other side.
        val pair = listOf(SettingsChoiceOption(true, "On"), SettingsChoiceOption(false, "Off"))
        assertEquals(false, settingsNextChoiceOption(pair, true))
        assertEquals(true, settingsNextChoiceOption(pair, false))
    }

    @Test
    fun arrowStepsAChoiceRowAndReleasesTheKeyAtTheEnds() {
        assertEquals(1, settingsChoiceStepIndex(0, count = 3, forward = true))
        assertEquals(0, settingsChoiceStepIndex(1, count = 3, forward = false))
        // Null is what lets Left at the first option leave the row for the categories.
        assertNull(settingsChoiceStepIndex(0, count = 3, forward = false))
        assertNull(settingsChoiceStepIndex(2, count = 3, forward = true))
        assertNull(settingsChoiceStepIndex(0, count = 1, forward = true))
        assertNull(settingsChoiceStepIndex(-1, count = 3, forward = true))
    }
}
