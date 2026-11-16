package com.nuvio.app.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.DesktopNavigationGestureBridge
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Back hierarchy from a settings item's perspective: page content and search results return to
 * the category list, the category list returns to the navigation bar, and only the bar itself stays
 * on the bridge so Back leaves Settings.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsBackHierarchyTest {

    @Test
    fun backFromTopLevelContentReturnsToTheSidebar() = runComposeUiTest {
        var backRequests = 0
        GlobalScope.launch(Dispatchers.Unconfined) {
            DesktopNavigationGestureBridge.backRequests.collect { backRequests += 1 }
        }
        var sidebarFocused = false
        val row = FocusRequester()
        val sidebarEntry = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    CompositionLocalProvider(
                        LocalSettingsFocusRegion provides SettingsFocusRegion.Content,
                        LocalSettingsPage provides SettingsPage.WatchParty,
                    ) {
                        Box(
                            Modifier
                                .size(60.dp)
                                .focusRequester(row)
                                .settingsNavItem(onActivate = {})
                                .focusable(),
                        )
                    }
                    SidebarEntryBox(
                        requester = sidebarEntry,
                        onFocused = { sidebarFocused = it },
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { row.requestFocus() }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertTrue(sidebarFocused, "Back from a top-level page's content returns to the sidebar")
        assertEquals(0, backRequests, "and must not exit Settings")
    }

    @Test
    fun backFromNestedContentAlsoReturnsToTheSidebar() = runComposeUiTest {
        var backRequests = 0
        GlobalScope.launch(Dispatchers.Unconfined) {
            DesktopNavigationGestureBridge.backRequests.collect { backRequests += 1 }
        }
        var sidebarFocused = false
        val row = FocusRequester()
        val sidebarEntry = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    CompositionLocalProvider(
                        LocalSettingsFocusRegion provides SettingsFocusRegion.Content,
                        LocalSettingsPage provides SettingsPage.StreamScoring,
                    ) {
                        Box(
                            Modifier
                                .size(60.dp)
                                .focusRequester(row)
                                .settingsNavItem(onActivate = {})
                                .focusable(),
                        )
                    }
                    SidebarEntryBox(
                        requester = sidebarEntry,
                        onFocused = { sidebarFocused = it },
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { row.requestFocus() }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertTrue(sidebarFocused, "Back from a nested page's content returns to the sidebar too")
        assertEquals(0, backRequests, "and must not exit Settings")
    }

    @Test
    fun backFromTheSidebarRootGoesToTheNavigationBar() = runComposeUiTest {
        var backRequests = 0
        GlobalScope.launch(Dispatchers.Unconfined) {
            DesktopNavigationGestureBridge.backRequests.collect { backRequests += 1 }
        }
        var topBarFocused = false
        val row = FocusRequester()
        val topBarEntry = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                CompositionLocalProvider(LocalSettingsFocusRegion provides SettingsFocusRegion.Sidebar) {
                    Box(
                        Modifier
                            .size(60.dp)
                            .focusRequester(row)
                            .settingsNavItem(onActivate = {})
                            .focusable(),
                    )
                }
                TopBarEntryBox(
                    requester = topBarEntry,
                    onFocused = { topBarFocused = it },
                )
            }
        }
        waitForIdle()
        runOnIdle { row.requestFocus() }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertTrue(topBarFocused, "Back from the sidebar root focuses the navigation bar")
        assertEquals(0, backRequests, "and does not leave Settings on the same press")

        // Only the bar itself leaves Settings.
        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertEquals(1, backRequests, "Back on the navigation bar stays on the bridge (to exit Settings)")
    }

    @Test
    fun backFromAContextPanelRowReturnsToTheContent() = runComposeUiTest {
        var sidebarFocused = false
        var contentFocused = false
        val row = FocusRequester()
        val contentEntry = FocusRequester()
        val sidebarEntry = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    CompositionLocalProvider(LocalSettingsFocusRegion provides SettingsFocusRegion.ContextPanel) {
                        Box(
                            Modifier
                                .size(60.dp)
                                .focusRequester(row)
                                .settingsNavItem(onActivate = {})
                                .focusable(),
                        )
                    }
                    ContentEntryBox(requester = contentEntry, onFocused = { contentFocused = it })
                    SidebarEntryBox(
                        requester = sidebarEntry,
                        onFocused = { sidebarFocused = it },
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { row.requestFocus() }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertTrue(contentFocused, "a context-panel row returns to the page in front of it")
        assertEquals(false, sidebarFocused, "not past the page to the categories")
    }

    /** A focusable registered as the navigation bar's keyboard entry. */
    @Composable
    private fun TopBarEntryBox(requester: FocusRequester, onFocused: (Boolean) -> Unit) {
        val focusManager = LocalFocusManager.current
        DisposableEffect(requester, focusManager) {
            SettingsTopBarFocus.register(requester, focusManager)
            onDispose { SettingsTopBarFocus.unregister(requester) }
        }
        Box(
            Modifier
                .size(60.dp)
                .focusRequester(requester)
                .onFocusChanged { onFocused(it.hasFocus) }
                // The real bar's row handles Back itself and leaves Settings through the bridge.
                .onPreviewKeyEvent { event ->
                    if (event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                        event.key == Key.Backspace
                    ) {
                        DesktopNavigationGestureBridge.requestBack()
                        true
                    } else {
                        false
                    }
                }
                .focusable(),
        )
    }

    /** A focusable registered as the page content's keyboard entry. */
    @Composable
    private fun ContentEntryBox(requester: FocusRequester, onFocused: (Boolean) -> Unit) {
        val focusManager = LocalFocusManager.current
        DisposableEffect(requester, focusManager) {
            SettingsContentFocus.register(requester, focusManager)
            onDispose { SettingsContentFocus.unregister(requester) }
        }
        Box(
            Modifier
                .size(60.dp)
                .focusRequester(requester)
                .onFocusChanged { onFocused(it.hasFocus) }
                .focusable(),
        )
    }

    /** A focusable that also serves as the registered sidebar entry, so Back's destination is observable. */
    @Composable
    private fun SidebarEntryBox(requester: FocusRequester, onFocused: (Boolean) -> Unit) {
        val focusManager = LocalFocusManager.current
        DisposableEffect(requester, focusManager) {
            SettingsSidebarFocus.register(requester, focusManager)
            onDispose { SettingsSidebarFocus.unregister(requester) }
        }
        Box(
            Modifier
                .size(60.dp)
                .focusRequester(requester)
                .onFocusChanged { onFocused(it.hasFocus) }
                .focusable(),
        )
    }
}
