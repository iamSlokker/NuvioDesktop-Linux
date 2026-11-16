package com.nuvio.app.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dropdown and multi-select menus driven by the keyboard, against the real components.
 *
 * Offscreen, like the rest of the desktop suite. The menu is a `Popup`, so it composes as a second
 * root: the keys are injected into that root, which is exactly the situation the app is in when
 * the popup layer owns them.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsNavMenuKeyTest {

    private val options = listOf(
        SettingsChoiceOption(1, "First"),
        SettingsChoiceOption(2, "Second"),
        SettingsChoiceOption(3, "Third"),
    )

    /** The popup's root, which is the second one once a menu is open. */
    private fun ComposeUiTest.menuRoot() = onAllNodes(isRoot())[1]

    private fun ComposeUiTest.pressInMenu(key: Key) {
        menuRoot().performKeyInput { pressKey(key) }
        waitForIdle()
    }

    /** Focuses the row's trigger from a lead box above it, as a person arrowing down would. */
    private fun ComposeUiTest.focusTrigger(lead: FocusRequester) {
        runOnIdle { lead.requestFocus() }
        waitForIdle()
        onAllNodes(isRoot())[0].performKeyInput { pressKey(Key.DirectionDown) }
        waitForIdle()
    }

    private fun ComposeUiTest.pressOnPage(key: Key) {
        onAllNodes(isRoot())[0].performKeyInput { pressKey(key) }
        waitForIdle()
    }

    @Composable
    private fun Dropdown(lead: FocusRequester, selected: Int, onSelected: (Int) -> Unit) {
        NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
            Column {
                Box(Modifier.size(60.dp).focusRequester(lead).settingsNavItem(onActivate = {}).focusable())
                SettingsDropdownChoiceRow(
                    title = "A dropdown",
                    description = null,
                    options = options,
                    selectedValue = selected,
                    isTablet = true,
                    onSelected = onSelected,
                    modifier = Modifier,
                )
            }
        }
    }

    @Test
    fun theMenuOpensOnTheSelectedOptionAndEnterCommitsIt() = runComposeUiTest {
        var selected = 2
        val lead = FocusRequester()
        setContent { Dropdown(lead, selected) { selected = it } }
        waitForIdle()
        focusTrigger(lead)

        // Select on the trigger opens the menu.
        pressOnPage(Key.Enter)
        assertEquals(2, selected, "opening is not choosing")
        assertEquals(2, onAllNodes(isRoot()).fetchSemanticsNodes().size, "the menu is up")

        // It opened on "Second" (the selection), not on "First": one Down therefore reaches
        // "Third". If it had opened on the first option this would land on "Second".
        pressInMenu(Key.DirectionDown)
        pressInMenu(Key.Enter)
        assertEquals(3, selected, "the menu opened on the selected option, not the first")
        assertEquals(1, onAllNodes(isRoot()).fetchSemanticsNodes().size, "a single-select menu closes")
    }

    @Test
    fun theArrowsMoveAndEnterCommitsTheOptionTheyLandOn() = runComposeUiTest {
        var selected = 1
        val lead = FocusRequester()
        setContent { Dropdown(lead, selected) { selected = it } }
        waitForIdle()
        focusTrigger(lead)
        pressOnPage(Key.Enter)

        // Opened on "First"; one Down reaches "Second".
        pressInMenu(Key.DirectionDown)
        assertEquals(1, selected, "moving the highlight is not choosing")
        pressInMenu(Key.Enter)
        assertEquals(2, selected, "Enter commits the option the arrows landed on")
    }

    @Test
    fun theArrowsClampAtBothEnds() = runComposeUiTest {
        var selected = 1
        val lead = FocusRequester()
        setContent { Dropdown(lead, selected) { selected = it } }
        waitForIdle()
        focusTrigger(lead)
        pressOnPage(Key.Enter)

        // Already on the first option: Up must not wrap round to the last.
        pressInMenu(Key.DirectionUp)
        pressInMenu(Key.Enter)
        assertEquals(1, selected, "Up at the top stays on the first option")
    }

    @Test
    fun escapeClosesTheMenuAndChoosesNothing() = runComposeUiTest {
        var selected = 2
        val lead = FocusRequester()
        setContent { Dropdown(lead, selected) { selected = it } }
        waitForIdle()
        focusTrigger(lead)
        pressOnPage(Key.Enter)
        pressInMenu(Key.DirectionDown)

        pressInMenu(Key.Escape)
        assertEquals(2, selected, "Escape chooses nothing")
        assertEquals(1, onAllNodes(isRoot()).fetchSemanticsNodes().size, "Escape closes the menu")
    }

    @Test
    fun leftAndRightDoNothingInsideTheMenu() = runComposeUiTest {
        var selected = 1
        val lead = FocusRequester()
        setContent { Dropdown(lead, selected) { selected = it } }
        waitForIdle()
        focusTrigger(lead)
        pressOnPage(Key.Enter)

        pressInMenu(Key.DirectionRight)
        pressInMenu(Key.DirectionLeft)
        assertEquals(1, selected, "Left and Right neither choose nor move the highlight")
        assertEquals(2, onAllNodes(isRoot()).fetchSemanticsNodes().size, "and they do not close the menu")
        // The highlight never moved, so Enter still commits the option it opened on.
        pressInMenu(Key.Enter)
        assertEquals(1, selected)
    }

    @Test
    fun enterOnAMultiSelectTicksAndStaysOpen() = runComposeUiTest {
        val ticked = linkedSetOf(1)
        val lead = FocusRequester()
        setContent {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    Box(Modifier.size(60.dp).focusRequester(lead).settingsNavItem(onActivate = {}).focusable())
                    SettingsMultiSelectRow(
                        title = "A multi-select",
                        description = null,
                        options = options,
                        selectedValues = ticked,
                        emptyLabel = "None",
                        isTablet = true,
                        onToggle = { value ->
                            if (!ticked.add(value)) ticked.remove(value)
                        },
                        modifier = Modifier,
                    )
                }
            }
        }
        waitForIdle()
        focusTrigger(lead)
        pressOnPage(Key.Enter)

        // Opened on the first ticked option; Down reaches "Second" and Enter ticks it.
        pressInMenu(Key.DirectionDown)
        pressInMenu(Key.Enter)
        assertTrue(2 in ticked, "Enter ticks the option it is on")
        assertEquals(2, onAllNodes(isRoot()).fetchSemanticsNodes().size, "a multi-select menu stays open")

        // A second tick, still in the same visit.
        pressInMenu(Key.DirectionDown)
        pressInMenu(Key.Enter)
        assertTrue(3 in ticked, "the menu is still usable after ticking")
        assertEquals(2, onAllNodes(isRoot()).fetchSemanticsNodes().size)

        pressInMenu(Key.Escape)
        assertEquals(1, onAllNodes(isRoot()).fetchSemanticsNodes().size, "Escape closes it")
    }
}
