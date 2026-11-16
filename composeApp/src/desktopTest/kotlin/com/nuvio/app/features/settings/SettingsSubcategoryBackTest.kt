package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Back on a sidebar sub-entry returns to its parent category row, the way the desktop sidebar's
 * `focusParent` callback wires it, instead of walking the page ladder out of Settings.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSubcategoryBackTest {

    @Test
    fun backFromASubEntryReturnsToTheParentRow() = runComposeUiTest {
        var parentFocused = false
        var subFocused = false
        val parent = FocusRequester()
        val sub = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .focusRequester(parent)
                            .settingsNavItem(onActivate = {})
                            .onFocusChanged { parentFocused = it.isFocused }
                            .focusable(),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .focusRequester(sub)
                            .settingsNavItem(
                                onActivate = {},
                                onBack = {
                                    parent.requestFocus()
                                },
                            )
                            .onFocusChanged { subFocused = it.isFocused }
                            .focusable(),
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { sub.requestFocus() }
        waitForIdle()
        assertTrue(subFocused, "the sub-entry holds focus")

        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertTrue(parentFocused, "Back from a sub-entry returns focus to its parent row")
        assertFalse(subFocused)
    }
}
