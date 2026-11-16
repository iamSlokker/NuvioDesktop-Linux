package com.nuvio.app.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
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
 * The row types added after the first settings pass — the square switch, the dropdown trigger and
 * the number steppers — against a real offscreen focus tree.
 *
 * Offscreen, like the rest of the desktop suite: no window and no display are involved.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsNavRowsTest {

    @Test
    fun stepperStepWalksTheGridAndStopsAtTheEnds() {
        assertEquals(45.0, stepperStep(40.0, 5.0, -100.0, 100.0, 1))
        assertEquals(35.0, stepperStep(40.0, 5.0, -100.0, 100.0, -1))
        assertEquals(1.0, stepperStep(0.0, 1.0, 0.0, 500.0, 1))
        // At an end the value is unchanged, so the key falls through and the person can leave.
        assertEquals(100.0, stepperStep(100.0, 5.0, -100.0, 100.0, 1))
        assertEquals(-100.0, stepperStep(-100.0, 5.0, -100.0, 100.0, -1))
        // A value between two stops moves a whole step from where it is.
        assertEquals(46.0, stepperStep(41.0, 5.0, 0.0, 100.0, 1))
        // No direction, or a nonsense step, is not a move.
        assertEquals(40.0, stepperStep(40.0, 5.0, -100.0, 100.0, 0))
        assertEquals(40.0, stepperStep(40.0, 0.0, -100.0, 100.0, 1))
    }

    @Test
    fun selectFlipsASquareSwitchAndLeavesFocusOnIt() = runComposeUiTest {
        var checked = false
        var switchFocused = false
        var neighbourFocused = false
        val lead = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    Box(
                        Modifier
                            .size(60.dp)
                            .focusRequester(lead)
                            .settingsNavItem(onActivate = {})
                            .focusable(),
                    )
                    Box(
                        Modifier
                            .size(60.dp)
                            .onFocusChanged { switchFocused = it.hasFocus },
                    ) {
                        SettingsSquareSwitch(checked = checked, onCheckedChange = { checked = it })
                    }
                    Box(Modifier.size(60.dp).onFocusChanged { neighbourFocused = it.hasFocus }.focusable())
                }
            }
        }
        waitForIdle()
        runOnIdle { lead.requestFocus() }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(switchFocused, "the switch is its own keyboard stop")

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(checked, "Select flips the switch")
        assertTrue(switchFocused, "and leaves the keys on it")
        assertFalse(neighbourFocused)
    }

    @Test
    fun arrowsStepOffASquareSwitchToTheNextRow() = runComposeUiTest {
        var checked = false
        var switchFocused = false
        var neighbourFocused = false
        val lead = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    Box(
                        Modifier
                            .size(60.dp)
                            .focusRequester(lead)
                            .settingsNavItem(onActivate = {})
                            .focusable(),
                    )
                    Box(Modifier.size(60.dp).onFocusChanged { switchFocused = it.hasFocus }) {
                        SettingsSquareSwitch(checked = checked, onCheckedChange = { checked = it })
                    }
                    Box(Modifier.size(60.dp).onFocusChanged { neighbourFocused = it.hasFocus }.focusable())
                }
            }
        }
        waitForIdle()
        runOnIdle { lead.requestFocus() }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(switchFocused)

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(neighbourFocused, "Down leaves the switch for the row below it")
        assertFalse(switchFocused)
        assertFalse(checked, "an arrow is not a toggle")
    }

    @Test
    fun aControlInsideARowKeepsSelectAndTheRowStillMovesTheArrows() = runComposeUiTest {
        var checked = false
        var rowClicks = 0
        var switchFocused = false
        var belowFocused = false
        val switch = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    // A card that holds a switch, as the catalog and category rows do.
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .settingsNavItem(onActivate = { rowClicks += 1 })
                            .clickable { rowClicks += 1 },
                    ) {
                        Box(
                            Modifier
                                .size(60.dp)
                                .focusRequester(switch)
                                .onFocusChanged { switchFocused = it.hasFocus },
                        ) {
                            SettingsSquareSwitch(checked = checked, onCheckedChange = { checked = it })
                        }
                    }
                    Box(Modifier.size(60.dp).onFocusChanged { belowFocused = it.hasFocus }.focusable())
                }
            }
        }
        waitForIdle()
        runOnIdle { switch.requestFocus() }
        waitForIdle()
        assertTrue(switchFocused)

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertTrue(checked, "the switch takes Select, not the card around it")
        assertEquals(0, rowClicks, "the card must not act on a key its control took")

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(belowFocused, "an arrow the control leaves alone still leaves the card")
        assertFalse(switchFocused)
    }

    @Test
    fun anIconActionIsItsOwnStopAndSelectActivatesIt() = runComposeUiTest {
        var activated = 0
        var iconFocused = false
        var belowFocused = false
        val lead = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    Box(
                        Modifier
                            .size(60.dp)
                            .focusRequester(lead)
                            .settingsNavItem(onActivate = {})
                            .focusable(),
                    )
                    Row {
                        IconButton(
                            onClick = { activated += 1 },
                            modifier = Modifier
                                .settingsNavIconButton(onClick = { activated += 1 })
                                .onFocusChanged { iconFocused = it.hasFocus },
                        ) {
                            Icon(Icons.Rounded.Edit, contentDescription = null)
                        }
                    }
                    Box(Modifier.size(60.dp).onFocusChanged { belowFocused = it.hasFocus }.focusable())
                }
            }
        }
        waitForIdle()
        runOnIdle { lead.requestFocus() }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(iconFocused, "the icon action is its own keyboard stop below the row above it")

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, activated, "Select activates the focused icon action")
        assertTrue(iconFocused, "and leaves the keys on it")

        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(belowFocused, "an arrow walks off the icon action to the row below")
        assertFalse(iconFocused)
    }

    @Test
    fun selectOnADropdownRowOpensItWithoutChoosing() = runComposeUiTest {
        var selected = 1
        var triggerFocused = false
        val lead = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    Box(
                        Modifier
                            .size(60.dp)
                            .focusRequester(lead)
                            .settingsNavItem(onActivate = {})
                            .focusable(),
                    )
                    SettingsDropdownChoiceRow(
                        title = "A dropdown",
                        description = null,
                        options = listOf(
                            SettingsChoiceOption(1, "First"),
                            SettingsChoiceOption(2, "Second"),
                            SettingsChoiceOption(3, "Third"),
                        ),
                        selectedValue = selected,
                        isTablet = true,
                        onSelected = { selected = it },
                        modifier = Modifier.onFocusChanged { triggerFocused = it.hasFocus },
                    )
                }
            }
        }
        waitForIdle()
        runOnIdle { lead.requestFocus() }
        waitForIdle()
        onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
        assertTrue(triggerFocused, "the dropdown row is one keyboard stop")

        onRoot().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, selected, "opening the menu is not choosing from it")
        assertTrue(triggerFocused, "the row keeps the keys while its menu is open")
    }
}
