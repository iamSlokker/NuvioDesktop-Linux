package com.nuvio.app.features.party

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The party code field is auto-focused on the Watch Party page; with an empty code, Back must belong
 * to the page so it can leave Watch Party instead of trapping the keys in the field.
 */
@OptIn(ExperimentalTestApi::class)
class PartyCodeBackTest {

    @Test
    fun backOnAnEmptyCodeFieldRequestsBack() = runComposeUiTest {
        var backs = 0
        val requester = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                CodeField(
                    code = "",
                    onCodeChange = {},
                    onDone = {},
                    focusRequester = requester,
                    enabled = true,
                    onBack = { backs += 1 },
                )
            }
        }
        waitForIdle()
        runOnIdle { requester.requestFocus() }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertEquals(1, backs, "Back on an empty code field goes to the page, not the field")
    }

    @Test
    fun backOnANonEmptyCodeFieldStaysInTheField() = runComposeUiTest {
        var backs = 0
        val requester = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                CodeField(
                    code = "ABC",
                    onCodeChange = {},
                    onDone = {},
                    focusRequester = requester,
                    enabled = true,
                    onBack = { backs += 1 },
                )
            }
        }
        waitForIdle()
        runOnIdle { requester.requestFocus() }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()
        assertEquals(0, backs, "Back on a non-empty code field deletes, not exits")
    }
}
