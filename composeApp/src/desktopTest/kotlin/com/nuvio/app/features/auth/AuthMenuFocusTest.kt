package com.nuvio.app.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The three-dot menu is a focus stop, so the arrows and a D-pad can reach it.
 *
 * Escape and Start also raise it, but a control that is not in the walk is invisible: a user has no
 * way to discover it exists. This asserts it can be focused and that Select opens it.
 */
@OptIn(ExperimentalTestApi::class)
class AuthMenuFocusTest {

    private fun page(block: androidx.compose.ui.test.ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = 1400, height = 900) {
            setContent {
                MaterialTheme(colorScheme = darkColorScheme()) {
                    Box(Modifier.fillMaxSize().background(Color.Black)) {
                        AuthScreen(modifier = Modifier.fillMaxSize())
                    }
                }
            }
            waitForIdle()
            block()
        }

    @Test
    fun `the menu icon takes focus`() = page {
        onNodeWithTag(AuthServerMenuButtonTag).requestFocus()
        onNodeWithTag(AuthServerMenuButtonTag).assertIsFocused()
    }

    @Test
    fun `Select on the focused menu icon opens the menu`() = page {
        onNodeWithTag(AuthServerMenuButtonTag).requestFocus()
        onNodeWithTag(AuthServerMenuButtonTag).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) {
            onAllNodesWithTag(AuthOnScreenKeyboardMenuItemTag).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
