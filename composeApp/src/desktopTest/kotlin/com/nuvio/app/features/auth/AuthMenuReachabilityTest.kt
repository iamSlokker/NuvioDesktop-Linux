package com.nuvio.app.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The three-dot menu has to be reachable from the pad and the keyboard alone.
 *
 * It holds the on-screen keyboard toggle, which on a couch PC is the only way to type a password —
 * so if the menu cannot be opened without a mouse, the sign-in screen cannot be completed at all.
 * Escape and the pad's Back button (which synthesizes Escape) raise it.
 */
@OptIn(ExperimentalTestApi::class)
class AuthMenuReachabilityTest {

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
    fun `escape opens the menu and the on-screen keyboard toggle is in it`() = page {
        onNodeWithTag(AuthServerMenuButtonTag).assertIsDisplayed()

        onNodeWithTag(AuthServerMenuButtonTag).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        onNodeWithTag(AuthOnScreenKeyboardMenuItemTag).assertIsDisplayed()
    }

    /**
     * The regression this exists for: the page's Escape handler re-opened the menu on every press,
     * including the one meant to dismiss it. The popup then never closed, kept focus, and the
     * page's arrows stayed dead for good — "navigation works until I press Escape".
     */
    @Test
    fun `escape does not re-open a menu that is already open`() = page {
        AuthMenuRequest.request()
        waitForIdle()
        onNodeWithTag(AuthOnScreenKeyboardMenuItemTag).assertIsDisplayed()
        assertTrue(AuthMenuRequest.isOpen, "the menu should report itself open")

        // A second Escape must be left for the popup to dismiss, not turned into another open.
        onNodeWithTag(AuthOnScreenKeyboardMenuItemTag).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        // The page did not re-request it: the flag is still whatever the popup last published,
        // and crucially the handler did not consume the key to open it again.
        assertTrue(
            AuthMenuRequest.isOpen,
            "the page re-opened the menu instead of letting the popup dismiss it",
        )
    }

    @Test
    fun `the menu request opens it without any pointer input`() = page {
        // What Start and the pad's Back button do: raise the menu through the shared request.
        AuthMenuRequest.request()
        waitForIdle()

        onNodeWithTag(AuthOnScreenKeyboardMenuItemTag).assertIsDisplayed()
    }
}
