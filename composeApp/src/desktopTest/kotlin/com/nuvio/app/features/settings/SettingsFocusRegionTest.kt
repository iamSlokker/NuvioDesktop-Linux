package com.nuvio.app.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The region entry registries behind page-change handoff and the Left/Right region transitions.
 * Offscreen, against a real focus tree: opening a page focuses its first content control, and a
 * return lands back on the control the person last used rather than the first one.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsFocusRegionTest {

    @AfterTest
    fun clearRegistries() {
        SettingsContentFocus.clear()
        SettingsContextFocus.clear()
    }

    @Test
    fun requestEntryFocusesTheFirstContentControlAndRemembersTheLastUsed() = runComposeUiTest {
        var firstFocused = false
        var secondFocused = false
        var manager: FocusManager? = null
        val second = FocusRequester()
        setContent {
            manager = LocalFocusManager.current
            CompositionLocalProvider(LocalSettingsFocusRegion provides SettingsFocusRegion.Content) {
                Column {
                    Box(
                        Modifier
                            .size(40.dp)
                            .settingsNavItem(onActivate = {})
                            .onFocusChanged { firstFocused = it.isFocused }
                            .focusable(),
                    )
                    Box(
                        Modifier
                            .size(40.dp)
                            .focusRequester(second)
                            .settingsNavItem(onActivate = {})
                            .onFocusChanged { secondFocused = it.isFocused }
                            .focusable(),
                    )
                }
            }
        }
        waitForIdle()
        val focusManager = manager
        assertNotNull(focusManager)

        runOnIdle { SettingsContentFocus.requestEntry(focusManager) }
        waitForIdle()
        assertTrue(firstFocused, "the first composed content control takes the keys")
        assertFalse(secondFocused)

        // Move to the second control, then ask for the region again: the person lands back where
        // they were, not on the first row.
        runOnIdle { second.requestFocus() }
        waitForIdle()
        assertTrue(secondFocused)

        runOnIdle { SettingsContentFocus.requestEntry(focusManager) }
        waitForIdle()
        assertTrue(secondFocused, "the remembered control is restored, not the first one")
    }

    @Test
    fun contentAndContextRegistriesStaySeparate() = runComposeUiTest {
        var contentFocused = false
        var contextFocused = false
        var manager: FocusManager? = null
        setContent {
            manager = LocalFocusManager.current
            Column {
                CompositionLocalProvider(LocalSettingsFocusRegion provides SettingsFocusRegion.Content) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .settingsNavItem(onActivate = {})
                            .onFocusChanged { contentFocused = it.isFocused }
                            .focusable(),
                    )
                }
                CompositionLocalProvider(LocalSettingsFocusRegion provides SettingsFocusRegion.ContextPanel) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .settingsNavItem(onActivate = {})
                            .onFocusChanged { contextFocused = it.isFocused }
                            .focusable(),
                    )
                }
            }
        }
        waitForIdle()
        val focusManager = manager
        assertNotNull(focusManager)

        runOnIdle { SettingsContentFocus.requestEntry(focusManager) }
        waitForIdle()
        assertTrue(contentFocused, "the content entry focuses the content, not the panel")
        assertFalse(contextFocused)

        runOnIdle { SettingsContextFocus.requestEntry(focusManager) }
        waitForIdle()
        assertTrue(contextFocused, "the panel entry focuses the panel, not the content")
    }

    @Test
    fun aHeadingIsAStopButNotThePageEntry() = runComposeUiTest {
        var headingFocused = false
        var rowFocused = false
        var headingActivated = 0
        var manager: FocusManager? = null
        setContent {
            manager = LocalFocusManager.current
            CompositionLocalProvider(LocalSettingsFocusRegion provides SettingsFocusRegion.Content) {
                Column {
                    Box(
                        Modifier
                            .size(40.dp)
                            .settingsNavItem(
                                onActivate = { headingActivated += 1 },
                                registerAsEntry = false,
                            )
                            .onFocusChanged { headingFocused = it.isFocused }
                            .focusable(),
                    )
                    Box(
                        Modifier
                            .size(40.dp)
                            .settingsNavItem(onActivate = {})
                            .onFocusChanged { rowFocused = it.isFocused }
                            .focusable(),
                    )
                }
            }
        }
        waitForIdle()
        val focusManager = manager
        assertNotNull(focusManager)

        // The page entry skips the heading and lands on the first row.
        runOnIdle { SettingsContentFocus.requestEntry(focusManager) }
        waitForIdle()
        assertTrue(rowFocused, "the first real setting, not the heading, takes the keys")
        assertFalse(headingFocused)

        // Up from the row reaches the heading, which is still a live stop.
        onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        waitForIdle()
        assertTrue(headingFocused, "the heading is reachable by Up, not skipped over")
        assertFalse(rowFocused)

        // Select on the heading collapses/expands it.
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, headingActivated)
    }
}
