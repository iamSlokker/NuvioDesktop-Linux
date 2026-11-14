package com.nuvio.app.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A confirmation dialog has to be answerable from a couch: the actions take the keys when it opens,
 * the arrows walk between them, and Enter picks the one holding them. Before this the dialog opened
 * with nothing focused and Compose moves focus on Tab only, so Cancel / Sign out / Sign out and erase
 * could not be reached with a remote or the arrow keys at all.
 *
 * Runs offscreen through the desktop Compose test harness: no window, no display, no pointer.
 */
@OptIn(ExperimentalTestApi::class)
class StatusModalActionFocusTest {

    private val erase = "Sign out and erase"
    private val signOut = "Sign out"
    private val cancel = "Cancel"

    private fun modal(
        onConfirm: () -> Unit = {},
        onDismiss: () -> Unit = {},
        onSecondary: () -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit = {
        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                NuvioStatusModal(
                    title = "Sign out of Nuvio?",
                    message = "Your library stays on the server.",
                    isVisible = true,
                    confirmText = signOut,
                    dismissText = cancel,
                    onConfirm = onConfirm,
                    onDismiss = onDismiss,
                    secondaryText = erase,
                    onSecondary = onSecondary,
                )
            }
        }
    }

    @Test
    fun `the safe action takes the keys when the dialog opens`() = runDesktopComposeUiTest(900, 700) {
        setContent(modal())
        waitForIdle()
        // Cancel, not the destructive button: every caller of this dialog changes something.
        onNodeWithText(cancel).assertIsFocused()
    }

    @Test
    fun `the arrows walk cancel to erase to sign out`() = runDesktopComposeUiTest(900, 700) {
        setContent(modal())
        waitForIdle()

        onNodeWithText(cancel).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithText(erase).assertIsFocused()

        onNodeWithText(erase).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithText(signOut).assertIsFocused()

        // And back again, so the row is walkable in both directions.
        onNodeWithText(signOut).performKeyInput { pressKey(Key.DirectionLeft) }
        waitForIdle()
        onNodeWithText(erase).assertIsFocused()
    }

    @Test
    fun `enter activates the action that holds the keys`() = runDesktopComposeUiTest(900, 700) {
        var cancelled = 0
        var erased = 0
        var signedOut = 0
        setContent(modal(
            onConfirm = { signedOut += 1 },
            onDismiss = { cancelled += 1 },
            onSecondary = { erased += 1 },
        ))
        waitForIdle()

        onNodeWithText(erase).performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        onNodeWithText(erase).assertIsFocused()
        onNodeWithText(erase).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(1, erased, "Enter runs the focused action, not another one")
        assertEquals(0, cancelled)
        assertEquals(0, signedOut)
    }

    @Test
    fun `a ring marks the action the keys are on, and only for key input`() {
        assertTrue(statusActionRingVisible(hasFocus = true, keyboardInput = true))
        // A pointer click focuses a button too; a ring there would claim a selection nobody made.
        assertFalse(statusActionRingVisible(hasFocus = true, keyboardInput = false))
        assertFalse(statusActionRingVisible(hasFocus = false, keyboardInput = true))
        assertFalse(statusActionRingVisible(hasFocus = false, keyboardInput = false))
    }

    @Test
    fun `escape answers the dialog with its dismiss action`() = runDesktopComposeUiTest(900, 700) {
        var cancelled = 0
        var signedOut = 0
        setContent(modal(onConfirm = { signedOut += 1 }, onDismiss = { cancelled += 1 }))
        waitForIdle()

        onNodeWithText(cancel).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()

        assertEquals(1, cancelled)
        assertEquals(0, signedOut)
    }
}
