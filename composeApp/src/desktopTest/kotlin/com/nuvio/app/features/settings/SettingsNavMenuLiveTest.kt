package com.nuvio.app.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.nuvio.app.LiveDisplayTests
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.DesktopNavigationGestureBridge
import com.nuvio.app.core.ui.NuvioTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.io.Closeable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dropdown and multi-select menus on a real window, driven by synthetic AWT key events.
 *
 * The offscreen suite ([SettingsNavMenuKeyTest]) proves the menu's rules by injecting keys into the
 * popup's own root. That is not the path a person's keypress takes: AWT hands the event to the
 * window, Compose's skiko bridge converts it, and only then does the scene route it to the layer
 * that owns focus. A popup is its own scene layer, so the whole point of this test is that a real
 * keypress reaches it at all — and that the trigger takes the keys back afterwards.
 *
 * Focus is global, so while this runs keystrokes meant for another application land in its window.
 * It is therefore **opt-in**, like the other live tests:
 *
 *     NUVIO_RUN_LIVE_DISPLAY_TESTS=1 ./gradlew desktopTest -Pnuvio.focusTests \
 *         --tests "*SettingsNavMenuLiveTest*"
 *
 * Skipped rather than failed where a window cannot take focus, because a skip is honest and a green
 * fake is not.
 */
class SettingsNavMenuLiveTest {

    private val options = listOf(
        SettingsChoiceOption(1, "First"),
        SettingsChoiceOption(2, "Second"),
        SettingsChoiceOption(3, "Third"),
    )

    @Test
    fun realKeysOpenTheMenuAndEnterCommitsTheHighlightedOption() {
        requireLive()

        val selected = AtomicInteger(1)
        val selectedState = mutableStateOf(1)
        val rowFocused = AtomicBoolean(false)
        val lead = FocusRequester()

        LiveMenuWindow {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    LeadStop(lead)
                    Box(Modifier.onFocusChanged { rowFocused.set(it.hasFocus) }) {
                        SettingsDropdownChoiceRow(
                            title = "A dropdown",
                            description = null,
                            options = options,
                            selectedValue = selectedState.value,
                            isTablet = true,
                            onSelected = { selected.set(it); selectedState.value = it },
                            modifier = Modifier,
                        )
                    }
                }
            }
        }.use { win ->
            if (!win.open(lead)) return skipNoWindow()

            win.press(KeyEvent.VK_DOWN)
            assertTrue(
                awaitTrue { rowFocused.get() },
                "Down did not carry focus from the lead box to the dropdown trigger",
            )

            win.press(KeyEvent.VK_ENTER)
            Thread.sleep(500)
            assertEquals(1, selected.get(), "opening the menu is not choosing an option")

            // Opened on "First" (the selection), so one Down reaches "Second".
            win.press(KeyEvent.VK_DOWN)
            win.press(KeyEvent.VK_ENTER)
            assertTrue(
                awaitTrue { selected.get() == 2 },
                "a real keypress did not reach the popup layer: saw ${selected.get()}",
            )
        }
    }

    @Test
    fun escapeClosesTheMenuWithoutChoosingAndHandsTheKeysBack() {
        requireLive()

        val selected = AtomicInteger(2)
        val selectedState = mutableStateOf(2)
        val rowFocused = AtomicBoolean(false)
        val lead = FocusRequester()
        val backs = BackRequestCounter()

        backs.use {
            LiveMenuWindow {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    Column {
                        LeadStop(lead)
                        Box(Modifier.onFocusChanged { rowFocused.set(it.hasFocus) }) {
                            SettingsDropdownChoiceRow(
                                title = "A dropdown",
                                description = null,
                                options = options,
                                selectedValue = selectedState.value,
                                isTablet = true,
                                onSelected = { selected.set(it); selectedState.value = it },
                                modifier = Modifier,
                            )
                        }
                    }
                }
            }.use { win ->
                if (!win.open(lead)) return skipNoWindow()

                win.press(KeyEvent.VK_DOWN)
                assertTrue(awaitTrue { rowFocused.get() }, "the trigger never took the keys")

                win.press(KeyEvent.VK_ENTER)
                Thread.sleep(500)
                win.press(KeyEvent.VK_DOWN)
                win.press(KeyEvent.VK_ESCAPE)
                Thread.sleep(500)

                assertEquals(2, selected.get(), "Escape chose an option")
                assertEquals(0, backs.value, "Escape leaked to the page behind as a back request")

                // The trigger must have the keys again: Select reopens the menu, and the arrows
                // then move inside it. If focus had been lost with the closed popup, nothing here
                // would happen and the selection would stay where it was.
                win.press(KeyEvent.VK_ENTER)
                Thread.sleep(500)
                win.press(KeyEvent.VK_DOWN)
                win.press(KeyEvent.VK_ENTER)
                assertTrue(
                    awaitTrue { selected.get() == 3 },
                    "the trigger did not take the keys back after Escape: saw ${selected.get()}",
                )
            }
        }
    }

    @Test
    fun aMultiSelectKeepsTickingAndStaysOpen() {
        requireLive()

        val tickedRef = ConcurrentHashMap.newKeySet<Int>().apply { add(1) }
        val tickedState: MutableState<Set<Int>> = mutableStateOf(setOf(1))
        val rowFocused = AtomicBoolean(false)
        val lead = FocusRequester()

        LiveMenuWindow {
            NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                Column {
                    LeadStop(lead)
                    Box(Modifier.onFocusChanged { rowFocused.set(it.hasFocus) }) {
                        SettingsMultiSelectRow(
                            title = "A multi-select",
                            description = null,
                            options = options,
                            selectedValues = tickedState.value,
                            emptyLabel = "None",
                            isTablet = true,
                            onToggle = { value ->
                                if (!tickedRef.add(value)) tickedRef.remove(value)
                                tickedState.value = tickedRef.toSet()
                            },
                            modifier = Modifier,
                        )
                    }
                }
            }
        }.use { win ->
            if (!win.open(lead)) return skipNoWindow()

            win.press(KeyEvent.VK_DOWN)
            assertTrue(awaitTrue { rowFocused.get() }, "the trigger never took the keys")

            win.press(KeyEvent.VK_ENTER)
            Thread.sleep(500)

            // Opened on the first ticked option; Down reaches "Second" and Enter ticks it. A tick
            // that fired twice — the key's release reaching the clickable again, say — would
            // untick it, so membership is the assertion.
            win.press(KeyEvent.VK_DOWN)
            win.press(KeyEvent.VK_ENTER)
            assertTrue(
                awaitTrue { 2 in tickedRef },
                "Enter did not tick the option it was on: ticked $tickedRef",
            )
            Thread.sleep(300)
            assertTrue(2 in tickedRef, "the tick fired twice and unticked itself")

            // Still open: the next Down and Enter reach "Third" without reopening anything.
            win.press(KeyEvent.VK_DOWN)
            win.press(KeyEvent.VK_ENTER)
            assertTrue(
                awaitTrue { 3 in tickedRef },
                "a multi-select closed after one tick instead of staying open: ticked $tickedRef",
            )

            win.press(KeyEvent.VK_ESCAPE)
            Thread.sleep(500)
            assertEquals(setOf(1, 2, 3), tickedRef, "Escape kept the ticks and changed nothing else")
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun requireLive() {
        LiveDisplayTests.assumeEnabled()
        assumeTrue(
            "Takes OS focus and would swallow keystrokes; run with -Pnuvio.focusTests",
            System.getProperty("nuvio.focusTests") == "true",
        )
        assumeTrue(
            "Live display test skipped: a non-headless display is required",
            !GraphicsEnvironment.isHeadless(),
        )
    }

    private fun skipNoWindow() {
        println("SKIPPED: the test window never took focus (locked session or focus-stealing prevention)")
    }

    /** The stop the person is on before arrowing down into the row. */
    @Composable
    private fun LeadStop(lead: FocusRequester) {
        Box(Modifier.size(60.dp).focusRequester(lead).settingsNavItem(onActivate = {}).focusable())
    }

    /**
     * A real window that posts key presses through the AWT system queue, the same route a physical
     * keypress takes.
     */
    private class LiveMenuWindow(private val content: @Composable () -> Unit) : Closeable {
        private lateinit var window: ComposeWindow

        /** Shows the window and focuses the lead stop; false where no window can take focus. */
        fun open(lead: FocusRequester): Boolean {
            onEdt {
                window = ComposeWindow().apply {
                    setSize(900, 600)
                    setContent { content() }
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }
            if (!awaitTrue { window.isFocused }) return false
            onEdt { runCatching { lead.requestFocus() } }
            // isFocused flips before AWT has assigned a focus owner, so the owner is waited for
            // separately rather than read straight after.
            return awaitFocusOwner() != null
        }

        fun press(keyCode: Int) {
            val source = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return
            val now = System.currentTimeMillis()
            val queue = Toolkit.getDefaultToolkit().systemEventQueue
            // What GamepadKeyInjector.send posts: press then release, no typed character.
            queue.postEvent(KeyEvent(source, KeyEvent.KEY_PRESSED, now, 0, keyCode, KeyEvent.CHAR_UNDEFINED))
            queue.postEvent(KeyEvent(source, KeyEvent.KEY_RELEASED, now, 0, keyCode, KeyEvent.CHAR_UNDEFINED))
        }

        override fun close() {
            onEdt { window.apply { isVisible = false; dispose() } }
        }

        private fun awaitFocusOwner(): java.awt.Component? {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let { return it }
                Thread.sleep(50)
            }
            return null
        }
    }

    /** Counts back requests reaching the app while the test runs. */
    private class BackRequestCounter : Closeable {
        private val count = AtomicInteger()
        private val scope = CoroutineScope(Dispatchers.Unconfined)
        private val job = scope.launch {
            DesktopNavigationGestureBridge.backRequests.collect { count.incrementAndGet() }
        }

        val value: Int get() = count.get()

        override fun close() = job.cancel()
    }

    private companion object {
        fun awaitTrue(timeoutMs: Long = 5_000, condition: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return true
                Thread.sleep(25)
            }
            return condition()
        }

        fun onEdt(block: () -> Unit) {
            if (EventQueue.isDispatchThread()) block() else EventQueue.invokeAndWait(block)
        }
    }
}
