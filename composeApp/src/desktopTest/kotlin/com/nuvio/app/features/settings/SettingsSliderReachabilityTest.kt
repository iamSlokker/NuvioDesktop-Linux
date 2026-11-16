package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reproduces the reported "App UI Scale / App Font Size are skipped" and "row actions unreachable"
 * defects against a real offscreen focus tree.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsSliderReachabilityTest {

    @Test
    fun appUiScaleSliderIsReachedByDownAndAdjustsOnSelect() = runComposeUiTest {
        var scale = 0
        val lead = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    // The control above the slider: a full-width row, as the section heading is.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .focusRequester(lead)
                            .settingsNavItem(onActivate = {})
                            .focusable(),
                    )
                    SettingsSliderRow(
                        title = "App UI Scale",
                        value = scale,
                        valueText = "$scale%",
                        valueTextForValue = { "$it%" },
                        valueRange = -50..50,
                        step = 5,
                        isTablet = true,
                        onValueChange = { scale = it },
                    )
                    SettingsSwitchRow(
                        title = "Also Apply to Details",
                        description = null,
                        checked = false,
                        isTablet = true,
                        onCheckedChange = {},
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { lead.requestFocus() }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(scale != 0, "Down must land on the App UI Scale slider, whose Select then nudges it")
    }

    @Test
    fun iconActionsInsideANavRowAreIndependentStops() = runComposeUiTest {
        var editClicks = 0
        var deleteClicks = 0
        var rowClicks = 0
        var upFocused = false
        var editFocused = false
        var deleteFocused = false
        val row = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(row)
                            .settingsNavItem(onActivate = { rowClicks += 1 })
                            .clickable { rowClicks += 1 },
                    ) {
                        Row {
                            IconButton(
                                onClick = { editClicks += 1 },
                                modifier = Modifier
                                    .settingsNavIconButton(onClick = { editClicks += 1 })
                                    .onFocusChanged { upFocused = it.hasFocus },
                            ) {
                                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null)
                            }
                            IconButton(
                                onClick = { editClicks += 1 },
                                modifier = Modifier
                                    .settingsNavIconButton(onClick = { editClicks += 1 })
                                    .onFocusChanged { editFocused = it.hasFocus },
                            ) {
                                Icon(Icons.Rounded.Edit, contentDescription = null)
                            }
                            IconButton(
                                onClick = { deleteClicks += 1 },
                                modifier = Modifier
                                    .settingsNavIconButton(onClick = { deleteClicks += 1 })
                                    .onFocusChanged { deleteFocused = it.hasFocus },
                            ) {
                                Icon(Icons.Rounded.Delete, contentDescription = null)
                            }
                        }
                    }
                }
            }
        }
        waitForIdle()
        runOnIdle { row.requestFocus() }
        waitForIdle()

        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertTrue(upFocused, "Right from the row reaches the first icon action")
        assertEquals(0, rowClicks, "moving to an action must not activate the row")
        assertEquals(0, editClicks)

        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertTrue(editFocused, "Right walks between actions")
        assertFalse(upFocused)
        assertEquals(0, editClicks, "moving between actions must not activate them")

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, editClicks, "Select activates the focused action")
        assertEquals(0, deleteClicks)
        assertEquals(0, rowClicks, "the row must not act on a key its control took")

        onRoot().performKeyInput { pressKey(Key.DirectionRight) }
        waitForIdle()
        assertTrue(deleteFocused, "Right reaches the last action")
        assertFalse(editFocused)

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, deleteClicks, "Select activates the delete action independently")
    }
}
