package com.nuvio.app.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsFocused
import com.nuvio.app.core.ui.arrowKeyFocusNavigation
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.qr.QrCodeEncoder
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pairing card's refresh action, driven the way a keyboard or a pad drives it: focus moves to
 * the button and Enter — which a pad's Select synthesizes — presses it.
 *
 * It must be reachable while a code is still valid: waiting out the countdown because a scan will
 * not take is exactly when someone presses it.
 */
@OptIn(ExperimentalTestApi::class)
class QrLoginOptionsPageTest {

    private val waiting = QrLoginUiState(
        phase = QrLoginPhase.Waiting,
        matrix = QrCodeEncoder.encode("https://nuvio.tv/link?code=NHGSVP").getOrThrow(),
        userCode = "NHGSVP",
        expiresAtMillis = System.currentTimeMillis() + 120_000,
    )

    @Test
    fun `the refresh button is offered while a code is still valid`() = runComposeUiTest {
        var refreshCalls = 0
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.background(Color.Black)) {
                    QrLoginPanel(state = waiting, onRefresh = { refreshCalls++ })
                }
            }
        }

        onNodeWithTag(QrRefreshTag).assertIsDisplayed()
        onNodeWithTag(QrRefreshTag).assertIsEnabled()
        onNodeWithTag(QrRefreshTag).performClick()
        assertEquals(1, refreshCalls, "refresh must not be gated on the code having expired")
    }

    @Test
    fun `Enter on the focused refresh button replaces the code`() = runComposeUiTest {
        var refreshCalls = 0
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.background(Color.Black)) {
                    QrLoginPanel(state = waiting, onRefresh = { refreshCalls++ })
                }
            }
        }

        onNodeWithTag(QrRefreshTag).requestFocus()
        onNodeWithTag(QrRefreshTag).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()

        assertEquals(1, refreshCalls)
    }

    @Test
    fun `space also presses the focused refresh button`() = runComposeUiTest {
        var refreshCalls = 0
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.background(Color.Black)) {
                    QrLoginPanel(state = waiting, onRefresh = { refreshCalls++ })
                }
            }
        }

        onNodeWithTag(QrRefreshTag).requestFocus()
        onNodeWithTag(QrRefreshTag).performKeyInput { pressKey(Key.Spacebar) }
        waitForIdle()

        assertEquals(1, refreshCalls)
    }

    @Test
    fun `the refresh button reports that it can be focused`() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.background(Color.Black)) {
                    QrLoginPanel(state = waiting, onRefresh = {})
                }
            }
        }

        // A custom focusable surface: without FocusableState a keyboard user cannot reach it at all,
        // which is what "I cannot press refresh" looked like.
        onNodeWithTag(QrRefreshTag).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused))
    }

    @Test
    fun `the refresh button can be reached by arrow keys from another control`() = runComposeUiTest {
        var refreshCalls = 0
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(
                    Modifier
                        .background(Color.Black)
                        .arrowKeyFocusNavigation(),
                ) {
                    Column {
                        Text("above", Modifier.padding(8.dp).focusable().testTag("above"))
                        QrLoginPanel(state = waiting, onRefresh = { refreshCalls++ })
                    }
                }
            }
        }

        onNodeWithTag("above").requestFocus()
        onNodeWithTag("above").performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()

        // A pad's D-pad arrives as these very key events, so this covers the controller too.
        onNodeWithTag(QrRefreshTag).assertIsFocused()
        onNodeWithTag(QrRefreshTag).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `no second refresh is offered while a new code is being fetched`() = runComposeUiTest {
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Box(Modifier.background(Color.Black)) {
                    QrLoginPanel(state = waiting.copy(phase = QrLoginPhase.Starting), onRefresh = {})
                }
            }
        }

        onNodeWithTag(QrRefreshTag).assertIsNotEnabled()
    }
}
