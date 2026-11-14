package com.nuvio.app.core.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Back out of the bar: the control the page had when the bar took the keys gets them again, and a
 * control remembered for another page is never handed the keys. The bar is drawn left of the page,
 * so the row here puts it first too and the fallback moves focus right.
 *
 * Offscreen, like the rest of the desktop suite: no window and no display are involved.
 */
@OptIn(ExperimentalTestApi::class)
class NavFocusLeaveTest {

    @AfterTest
    fun forgetRememberedControl() {
        // RootNavFocus is a singleton shared by every test in the run.
        RootNavFocus.forgetContentFocus()
    }

    @Test
    fun leavingTheBarGivesTheKeysBackToThePagesControl() = runComposeUiTest {
        val page = Any()
        var barFocused = false
        var pageFocused = false
        var focusManager: FocusManager? = null
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            focusManager = LocalFocusManager.current
            SidebarLeaveRestoresContent(
                sidebarHasFocus = { barFocused },
                pageOwner = { page },
            )
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(barRequester)
                        .onFocusChanged { barFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(pageRequester)
                        .onFocusChanged { pageFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        assertTrue(pageFocused, "the page starts with the keys")

        runOnIdle {
            // What the bar does as it takes the keys: remember the page's control, then focus itself.
            RootNavFocus.rememberContentFocus(page, focusManager!!)
            barRequester.requestFocus()
        }
        waitForIdle()
        assertTrue(barFocused)
        assertFalse(pageFocused)

        runOnIdle { RootNavFocus.leaveSidebar() }
        waitForIdle()
        assertTrue(pageFocused, "Back out of the bar returns to the page's control")
        assertFalse(barFocused)
    }

    @Test
    fun aControlRememberedForAnotherPageIsNotHandedTheKeys() = runComposeUiTest {
        val openPage = Any()
        val leftPage = Any()
        var barFocused = false
        var pageFocused = false
        var focusManager: FocusManager? = null
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            focusManager = LocalFocusManager.current
            SidebarLeaveRestoresContent(
                sidebarHasFocus = { barFocused },
                pageOwner = { openPage },
            )
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(barRequester)
                        .onFocusChanged { barFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(pageRequester)
                        .onFocusChanged { pageFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        assertTrue(pageFocused)

        runOnIdle {
            // The bar was entered from another page: this control is not the one to go back to.
            RootNavFocus.rememberContentFocus(leftPage, focusManager!!)
            barRequester.requestFocus()
        }
        waitForIdle()
        assertTrue(barFocused)

        runOnIdle { RootNavFocus.leaveSidebar() }
        waitForIdle()
        assertTrue(pageFocused, "the page beside the bar takes the keys instead")
        assertFalse(barFocused)
    }

    @Test
    fun aLeaveThatHandsTheKeysToSomethingElseLeavesThemOnTheBar() = runComposeUiTest {
        val page = Any()
        var barFocused = false
        var pageFocused = false
        var focusManager: FocusManager? = null
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            focusManager = LocalFocusManager.current
            SidebarLeaveRestoresContent(
                sidebarHasFocus = { barFocused },
                pageOwner = { page },
            )
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(barRequester)
                        .onFocusChanged { barFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(pageRequester)
                        .onFocusChanged { pageFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        runOnIdle {
            RootNavFocus.rememberContentFocus(page, focusManager!!)
            barRequester.requestFocus()
        }
        waitForIdle()
        assertTrue(barFocused)

        // The Games row: game mode takes the keys itself, so the page must not be focused first.
        runOnIdle { RootNavFocus.leaveSidebar(restoreContent = false) }
        waitForIdle()
        assertTrue(barFocused, "the keys stay where the caller is about to put them")
        assertFalse(pageFocused)
    }

    @Test
    fun aLeaveAfterThePageAlreadyTookTheKeysLeavesThemThere() = runComposeUiTest {
        val page = Any()
        var barFocused = false
        var pageFocused = false
        var focusManager: FocusManager? = null
        val barRequester = FocusRequester()
        val pageRequester = FocusRequester()
        setContent {
            focusManager = LocalFocusManager.current
            SidebarLeaveRestoresContent(
                sidebarHasFocus = { barFocused },
                pageOwner = { page },
            )
            Row {
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(barRequester)
                        .onFocusChanged { barFocused = it.hasFocus }
                        .focusable(),
                )
                Box(
                    Modifier
                        .size(40.dp)
                        .focusRequester(pageRequester)
                        .onFocusChanged { pageFocused = it.hasFocus }
                        .focusable(),
                )
            }
        }
        waitForIdle()
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        runOnIdle {
            RootNavFocus.rememberContentFocus(page, focusManager!!)
            barRequester.requestFocus()
        }
        waitForIdle()
        assertTrue(barFocused)

        // The page took the keys on its own before the emission was collected.
        runOnIdle { pageRequester.requestFocus() }
        waitForIdle()
        runOnIdle { RootNavFocus.leaveSidebar() }
        waitForIdle()
        assertTrue(pageFocused, "focus stays where the page put it")
        assertFalse(barFocused)
    }
}
