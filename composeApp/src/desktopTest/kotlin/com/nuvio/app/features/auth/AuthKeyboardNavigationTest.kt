package com.nuvio.app.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.qr.QrCodeEncoder
import com.nuvio.app.core.ui.NuvioPrimaryButton
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sign-in page's focus walk, driven the way a keyboard or a pad drives it:
 *
 *  - the page opens on Refresh QR, so the first press on a D-pad moves rather than types;
 *  - Up/Down walk the controls in the order they are drawn, from the fields to Sign In to the
 *    action row;
 *  - Left/Right move between the two buttons of a row, and inside a text field they stay with the
 *    caret (a field is the one place an arrow means something else).
 *
 * The reported failure was that Down on the password field left focus nowhere visible: it did move
 * to Sign In, but the disabled button drew no focus state at all, so the page looked dead. Both
 * halves of that — the move, and the control having to look focused — are asserted here.
 */
@OptIn(ExperimentalTestApi::class)
class AuthKeyboardNavigationTest {

    private fun realPage(block: ComposeUiTest.() -> Unit) = pageAt(1400, 900, block)

    /**
     * The page at an arbitrary window size.
     *
     * The two layouts are genuinely different compositions: at [WideLayoutMinWidth] and up the form
     * and the code sit side by side; below it they stack into one scrolling column. The stacked
     * branch is where the walk used to dead-end on the control it opens with, because the action row
     * is the last thing in the column and geometry finds nothing beneath it. Every walk assertion
     * runs in both, so a fix for one layout cannot silently break the other.
     */
    private fun pageAt(width: Int, height: Int, block: ComposeUiTest.() -> Unit) =
        runDesktopComposeUiTest(width = width, height = height) {
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

    /** Below the two-column breakpoint: the same page stacked into one column. */
    private fun stackedPage(block: ComposeUiTest.() -> Unit) = pageAt(820, 900, block)

    @Test
    fun `the page opens on the refresh button`() = realPage {
        assertTagFocused(QrRefreshTag)
    }

    @Test
    fun `down from the password field reaches the sign-in button`() = realPage {
        // Refresh, Continue, Sign Up, Password, Email is the upward walk; then down twice.
        press(Key.DirectionRight)
        press(Key.DirectionUp)
        assertTagFocused(AuthToggleModeTag)
        press(Key.DirectionUp)
        assertTagFocused(AuthPasswordFieldTag)
        press(Key.DirectionUp)
        assertTagFocused(AuthEmailFieldTag)

        press(Key.DirectionDown)
        assertTagFocused(AuthPasswordFieldTag)
        press(Key.DirectionDown)
        assertTagFocused(AuthSignInTag)
    }

    /**
     * The regression this whole investigation was about: in the stacked layout the page opens on
     * Refresh and Down used to do nothing at all, because the action row is the bottom of the
     * column and geometry finds nothing beneath it. Down must leave the row for the top of the form.
     */
    @Test
    fun `in the stacked layout down from the opening control reaches the form`() = stackedPage {
        assertTagFocused(QrRefreshTag)
        press(Key.DirectionDown)
        assertTagFocused(AuthEmailFieldTag)
    }

    @Test
    fun `in the stacked layout the walk runs refresh to form to buttons and back`() = stackedPage {
        val walk = listOf(
            Key.DirectionDown to AuthEmailFieldTag,
            Key.DirectionDown to AuthPasswordFieldTag,
            Key.DirectionDown to AuthSignInTag,
            Key.DirectionDown to QrRefreshTag,
        )
        for ((key, tag) in walk) {
            press(key)
            assertTagFocused(tag)
        }
    }

    /**
     * The wide layout's walk, which the tests above already covered at 1400x900, plus the wrap out of
     * the action row — the same edge that was dead in the stacked branch.
     */
    @Test
    fun `in the wide layout down from the opening control reaches the form`() = realPage {
        assertTagFocused(QrRefreshTag)
        press(Key.DirectionDown)
        assertTagFocused(AuthEmailFieldTag)
    }

    @Test
    fun `left and right move between the two buttons of a row`() = realPage {
        press(Key.DirectionRight) // Continue Without Account
        press(Key.DirectionUp) // Sign Up
        assertTagFocused(AuthToggleModeTag)
        press(Key.DirectionLeft)
        assertTagFocused(AuthSignInTag)
        press(Key.DirectionRight)
        assertTagFocused(AuthToggleModeTag)
    }

    @Test
    fun `the action row is reachable from the form and from itself`() = realPage {
        // Down from Sign In reaches Refresh QR, which is the form's next row.
        press(Key.DirectionRight)
        press(Key.DirectionUp)
        press(Key.DirectionUp) // password
        press(Key.DirectionDown) // Sign In
        press(Key.DirectionDown) // Refresh QR
        assertTagFocused(QrRefreshTag)
        press(Key.DirectionRight)
        assertTagFocused(AuthContinueWithoutAccountTag)
        press(Key.DirectionLeft)
        assertTagFocused(QrRefreshTag)
    }

    @Test
    fun `every control is reachable without the pointer`() = realPage {
        // The full walk, from the control the page opens on: Refresh, Continue, up through Sign Up,
        // Password, Email, then back down to Sign In.
        val expected = listOf(
            Key.DirectionRight to AuthContinueWithoutAccountTag,
            Key.DirectionUp to AuthToggleModeTag,
            Key.DirectionUp to AuthPasswordFieldTag,
            Key.DirectionUp to AuthEmailFieldTag,
            Key.DirectionDown to AuthPasswordFieldTag,
            Key.DirectionDown to AuthSignInTag,
        )
        for ((key, tag) in expected) {
            press(key)
            assertTagFocused(tag)
        }
    }

    @Test
    fun `a disabled sign-in button still takes and reports focus`() = realPage {
        // The form starts empty, so Sign In is disabled. It is still the next stop from the
        // password field; removing it from the focus order was the original dead end.
        press(Key.DirectionRight)
        press(Key.DirectionUp)
        press(Key.DirectionUp) // password
        press(Key.DirectionDown) // Sign In
        assertTagFocused(AuthSignInTag)
    }

    @Test
    fun `the three-dot menu offers the on-screen keyboard`() = realPage {
        onNodeWithTag(AuthServerMenuButtonTag).performClick()
        waitForIdle()
        onNodeWithTag(AuthOnScreenKeyboardMenuItemTag).assertExists()
    }

    @Test
    fun `Select activates a focused primary button`() = runComposeUiTest {
        var pressed = 0
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NuvioPrimaryButton(
                        text = "Sign In",
                        modifier = Modifier.testTag("sign-in"),
                        onClick = { pressed++ },
                    )
                }
            }
        }

        onNodeWithTag("sign-in").requestFocus()
        onNodeWithTag("sign-in").assertIsFocused()
        onNodeWithTag("sign-in").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, pressed, "Enter must press the focused button")

        onNodeWithTag("sign-in").performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()
        assertEquals(2, pressed, "space must press it too")
    }

    @Test
    fun `a disabled primary button does not fire from the keyboard`() = runComposeUiTest {
        var pressed = 0
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NuvioPrimaryButton(
                        text = "Sign In",
                        modifier = Modifier.testTag("sign-in"),
                        enabled = false,
                        onClick = { pressed++ },
                    )
                }
            }
        }

        onNodeWithTag("sign-in").performClick()
        onNodeWithTag("sign-in").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(0, pressed)
    }

    @Test
    fun `the refresh button is in the focus order`() = runComposeUiTest {
        var refreshes = 0
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    QrLoginPanel(state = waitingState(), onRefresh = { refreshes++ })
                }
            }
        }

        onNodeWithTag(QrRefreshTag).requestFocus()
        onNodeWithTag(QrRefreshTag).assertIsFocused()
        onNodeWithTag(QrRefreshTag).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(1, refreshes)
    }

    /**
     * The canvas draws the panel's symbol and text: a scene render proves the card's content is
     * laid out (not clipped) at a realistic window height.
     */
    @Test
    fun `the code card draws its countdown and button inside the panel`() {
        val density = 1f
        val scene = ImageComposeScene(560, 760, Density(density)) {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.fillMaxSize().background(Color.Black).padding(16.dp)) {
                    QrLoginPanel(state = waitingState(), onRefresh = {})
                }
            }
        }
        try {
            val image = scene.render(16_666_666L)
            assertTrue(image.width > 0 && image.height > 0)
            image.close()
        } finally {
            scene.close()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun waitingState() = QrLoginUiState(
        phase = QrLoginPhase.Waiting,
        matrix = QrCodeEncoder.encode("https://nuvio.tv/link?code=NHGSVP").getOrThrow(),
        userCode = "NHGSVP",
        expiresAtMillis = System.currentTimeMillis() + 120_000,
    )

    private fun ComposeUiTest.press(key: Key) {
        onRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    private fun ComposeUiTest.assertTagFocused(tag: String) {
        val focused = onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .any { holdsFocus(it) }
        assertTrue(focused, "'$tag' does not hold focus")
    }

    /**
     * A text field's tag sits on the outer box; the node that can actually hold focus is the
     * BasicTextField child inside it. So focus has to be looked for through the whole subtree
     * rather than on the tagged node itself.
     */
    private fun holdsFocus(node: SemanticsNode): Boolean =
        node.config.getOrElse(SemanticsProperties.Focused) { false } ||
            node.children.any { holdsFocus(it) }
}
