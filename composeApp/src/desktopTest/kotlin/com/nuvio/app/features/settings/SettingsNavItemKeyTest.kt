package com.nuvio.app.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [settingsNavItem] against a real focus tree: a key a control takes must not move focus, a key it
 * declines must, and Left with nothing further left must reach the category sidebar.
 *
 * Offscreen, like the rest of the desktop suite: no window and no display are involved.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsNavItemKeyTest {

    @Test
    fun horizontalKeyTheControlTakesKeepsFocusAndCommitsOnRelease() = runComposeUiTest {
        val steps = mutableListOf<Int>()
        var released = 0
        var activated = 0
        var firstFocused = false
        var secondFocused = false
        val first = FocusRequester()
        setContent {
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(first)
                        .settingsNavItem(
                            onActivate = { activated += 1 },
                            onHorizontal = { steps += it; true },
                            onHorizontalReleased = { released += 1 },
                        )
                        .onFocusChanged { firstFocused = it.hasFocus }
                        .focusable(),
                )
                Box(Modifier.size(40.dp).onFocusChanged { secondFocused = it.hasFocus }.focusable())
            }
        }
        waitForIdle()
        runOnIdle { first.requestFocus() }
        waitForIdle()
        assertTrue(firstFocused, "the row under test has the keyboard")
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertEquals(listOf(1), steps)
        assertEquals(1, released, "the key-up commits once")
        assertTrue(firstFocused, "a key the control took leaves focus where it was")
        assertFalse(secondFocused)

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, activated, "Select activates once, not again on its key-up")
    }

    @Test
    fun horizontalKeyTheControlDeclinesStillMovesFocus() = runComposeUiTest {
        var released = 0
        var firstFocused = false
        var secondFocused = false
        val first = FocusRequester()
        setContent {
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(first)
                        .settingsNavItem(
                            onActivate = {},
                            onHorizontal = { false },
                            onHorizontalReleased = { released += 1 },
                        )
                        .onFocusChanged { firstFocused = it.hasFocus }
                        .focusable(),
                )
                Box(Modifier.size(40.dp).onFocusChanged { secondFocused = it.hasFocus }.focusable())
            }
        }
        waitForIdle()
        runOnIdle { first.requestFocus() }
        waitForIdle()
        assertTrue(firstFocused)
        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertFalse(firstFocused)
        assertTrue(secondFocused, "a declined key falls through to focus traversal")
        assertEquals(0, released, "a key the control did not take has nothing to commit")
    }

    @Test
    fun verticalKeyTheRowTakesMovesTheSelectionNotTheFocus() = runComposeUiTest {
        val moves = mutableListOf<Int>()
        var firstFocused = false
        var secondFocused = false
        val first = FocusRequester()
        setContent {
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(first)
                        .settingsNavItem(onActivate = {}, onVertical = { moves += it; true })
                        .onFocusChanged { firstFocused = it.hasFocus }
                        .focusable(),
                )
                Box(Modifier.size(40.dp).onFocusChanged { secondFocused = it.hasFocus }.focusable())
            }
        }
        waitForIdle()
        runOnIdle { first.requestFocus() }
        waitForIdle()
        assertTrue(firstFocused)
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertEquals(listOf(1), moves)
        assertTrue(firstFocused, "the sidebar keeps focus while its selection walks the list")
        assertFalse(secondFocused)
    }

    @Test
    fun leftWithNothingFurtherLeftEntersTheSidebar() = runComposeUiTest {
        var rowFocused = false
        var sidebarFocused = false
        val row = FocusRequester()
        val sidebarEntry = FocusRequester()
        setContent {
            SidebarEntryRegistration(sidebarEntry, LocalFocusManager.current)
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(row)
                        .settingsNavItem(onActivate = {})
                        .onFocusChanged { rowFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(sidebarEntry)
                        .onFocusChanged { sidebarFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { row.requestFocus() }
        waitForIdle()
        assertTrue(rowFocused)
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertTrue(sidebarFocused, "Left at the page's edge belongs to the categories")
        assertFalse(rowFocused)
    }

    @Test
    fun leftInsideADialogStaysInTheDialog() = runComposeUiTest {
        var rowFocused = false
        var sidebarFocused = false
        val row = FocusRequester()
        val sidebarEntry = FocusRequester()
        setContent {
            // A dialog composes its own focus manager, so the registration made by the sidebar
            // behind it must not take a Left pressed inside the dialog.
            SidebarEntryRegistration(sidebarEntry, OtherFocusManager)
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(row)
                        .settingsNavItem(onActivate = {})
                        .onFocusChanged { rowFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(sidebarEntry)
                        .onFocusChanged { sidebarFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { row.requestFocus() }
        waitForIdle()
        assertTrue(rowFocused)
        onRoot().performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        assertTrue(rowFocused)
        assertFalse(sidebarFocused)
    }

    @Test
    fun backInvokesTheItemsOwnHandlerInsteadOfTheBridge() = runComposeUiTest {
        var backHandled = 0
        var rowFocused = false
        val row = FocusRequester()
        setContent {
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(row)
                        .settingsNavItem(
                            onActivate = {},
                            onBack = { backHandled += 1; true },
                        )
                        .onFocusChanged { rowFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { row.requestFocus() }
        waitForIdle()
        assertTrue(rowFocused)
        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertEquals(1, backHandled, "Back goes to the item's own handler, not the page ladder")
    }

    private companion object {
        val OtherFocusManager = object : FocusManager {
            override fun clearFocus(force: Boolean) = Unit
            override fun moveFocus(focusDirection: FocusDirection): Boolean = false
        }
    }
}

/** Registers [entry] with [SettingsSidebarFocus] for the lifetime of the composition. */
@Composable
private fun SidebarEntryRegistration(entry: FocusRequester, focusManager: FocusManager) {
    DisposableEffect(entry, focusManager) {
        SettingsSidebarFocus.register(entry, focusManager)
        onDispose { SettingsSidebarFocus.unregister(entry) }
    }
}
