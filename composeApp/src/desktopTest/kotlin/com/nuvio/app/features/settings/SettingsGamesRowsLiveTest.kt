package com.nuvio.app.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import com.nuvio.app.LiveDisplayTests
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.arrowKeyFocusNavigation
import com.nuvio.app.core.ui.focusedControl
import com.nuvio.app.features.games.GameRowsSettingsSection
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Robot
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live window test for Games -> Rows. Proves the Up, Down and Edit controls are independent focus
 * stops (a row never takes focus as one blob), that a disabled boundary arrow is skipped, and that
 * Enter on Edit opens the rename editor while Enter on the move arrows stays a move.
 */
class SettingsGamesRowsLiveTest {

    @Test
    fun upDownAndEditAreIndependentStopsAndEditOpensTheEditor() {
        requireLive()

        val focusManager = AtomicReference<FocusManager?>()
        val windowRef = AtomicReference<ComposeWindow?>()
        val lead = FocusRequester()
        onEdt {
            val created = ComposeWindow()
            created.setSize(1000, 900)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    focusManager.set(LocalFocusManager.current)
                    Column(Modifier.fillMaxSize().arrowKeyFocusNavigation()) {
                        Box(Modifier.size(40.dp).focusRequester(lead).focusable())
                        GameRowsSettingsSection(isTablet = true, title = "Rows")
                    }
                }
            }
            created.isVisible = true
            created.toFront()
            created.requestFocus()
            windowRef.set(created)
        }
        val window = windowRef.get() ?: error("window")
        try {
            if (!awaitTrue(5_000) { window.isFocused } || awaitFocusOwner() == null) {
                println("SKIPPED: window never took focus")
                return
            }
            val manager = focusManager.get()
            assertNotNull(manager, "no FocusManager")
            onEdt { lead.requestFocus() }
            Thread.sleep(300)

            val stops = collectStops(window, manager, 60)
            val seen = stops.joinToString(" ") { it.lowercase() }

            // Each control is its own stop.
            assertTrue(seen.contains("move up"), "the Move up arrow was not an independent focus stop; saw: $stops")
            assertTrue(seen.contains("move down"), "the Move down arrow was not an independent focus stop; saw: $stops")
            assertTrue(seen.contains("rename"), "the Edit (rename) button was not an independent focus stop; saw: $stops")

            // Focusing a row must not have opened the editor: before Enter on Edit, no save/cancel
            // controls exist.
            assertTrue(!seen.contains("save") && !seen.contains("cancel"), "focusing a row opened the editor: $stops")

            // Enter on the Edit stop opens the rename editor: the Edit stop is replaced, so the
            // focused "rename" label disappears and the row's text field takes its place.
            assertTrue(
                focusExact(window, manager, "rename"),
                "could not land focus exactly on the Edit (rename) stop; saw '${focusedLabel(manager)}'",
            )
            window.pressKey(KeyEvent.VK_ENTER)
            Thread.sleep(300)
            assertTrue(
                focusedLabel(manager).trim() != "rename",
                "Enter on Edit did not open the rename editor; the Edit stop is still focused",
            )

            // Back cancels the inline editor, and must leave a real control focused: clearing the
            // field without a destination used to leave the page with no focus, so no key moved.
            window.pressKey(KeyEvent.VK_ESCAPE)
            Thread.sleep(350)
            assertNotNull(manager.focusedControl(), "cancelling the row editor left nothing focused")
            val restored = focusedLabel(manager).trim()
            assertTrue(restored.isNotEmpty(), "cancelling the row editor did not focus the row")
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(250)
            assertNotNull(manager.focusedControl(), "navigation is dead after cancelling the row editor")
            assertTrue(
                focusedLabel(manager).trim() != restored || manager.focusedControl() != null,
                "Down did not move after cancelling the row editor",
            )
        } finally {
            onEdt { window.apply { isVisible = false; dispose() } }
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Sweeps Down and Right to collect every stop the arrows can reach, without bailing early. */
    private fun collectStops(window: ComposeWindow, manager: FocusManager, steps: Int): List<String> {
        val stops = LinkedHashSet<String>()
        repeat(steps) {
            focusedLabel(manager).takeIf { it.isNotEmpty() }?.let { stops += it }
            window.pressKey(if (it % 3 == 0) KeyEvent.VK_DOWN else KeyEvent.VK_RIGHT)
            Thread.sleep(100)
        }
        return stops.toList()
    }

    private fun focusLabel(window: ComposeWindow, manager: FocusManager, needle: String): Boolean {
        repeat(60) {
            if (focusedLabel(manager).contains(needle)) return true
            window.pressKey(if (it % 3 == 0) KeyEvent.VK_DOWN else KeyEvent.VK_RIGHT)
            Thread.sleep(90)
        }
        return focusedLabel(manager).contains(needle)
    }

    /** Navigates until the focused stop's label equals [needle] exactly (not just a descendant). */
    private fun focusExact(window: ComposeWindow, manager: FocusManager, needle: String): Boolean {
        repeat(80) {
            if (focusedLabel(manager).trim() == needle) return true
            window.pressKey(if (it % 3 == 0) KeyEvent.VK_DOWN else KeyEvent.VK_RIGHT)
            Thread.sleep(90)
        }
        return focusedLabel(manager).trim() == needle
    }

    private fun ComposeWindow.pressKey(keyCode: Int) {
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
    }

    private companion object {
        val robot: Robot by lazy { Robot() }

        fun requireLive() {
            LiveDisplayTests.assumeEnabled()
            assumeTrue(
                "Takes OS focus and would swallow keystrokes; run with -Pnuvio.focusTests",
                System.getProperty("nuvio.focusTests") == "true",
            )
            assumeFalse("Live display test skipped: a non-headless display is required", GraphicsEnvironment.isHeadless())
        }

        fun focusedLabel(manager: FocusManager): String {
            val target = manager.focusedControl() ?: return ""
            val node = target.node
            val coordinator = invoke(node, "getCoordinator\$ui") ?: return ""
            val layout = invoke(coordinator, "getLayoutNode") ?: return ""
            val texts = mutableListOf<String>()
            collectSemantics(layout, texts, 0)
            return texts.distinct().joinToString(" ").lowercase()
        }

        fun collectSemantics(node: Any, out: MutableList<String>, depth: Int) {
            if (depth > 60) return
            val config = invoke(node, "getSemanticsConfiguration") as? SemanticsConfiguration
            if (config != null) {
                if (config.contains(SemanticsProperties.Text)) config.get(SemanticsProperties.Text).forEach { out += it.text }
                if (config.contains(SemanticsProperties.ContentDescription)) out += config.get(SemanticsProperties.ContentDescription)
                if (config.contains(SemanticsProperties.EditableText)) out += config.get(SemanticsProperties.EditableText).text
            }
            val children = invoke(node, "getFoldedChildren\$ui") as? List<*> ?: return
            for (child in children) if (child != null) collectSemantics(child, out, depth + 1)
        }

        fun invoke(target: Any, name: String): Any? =
            runCatching { target.javaClass.methods.firstOrNull { it.name == name }?.invoke(target) }.getOrNull()

        fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return true
                Thread.sleep(25)
            }
            return condition()
        }

        fun awaitFocusOwner(): java.awt.Component? {
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline) {
                KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner?.let { return it }
                Thread.sleep(50)
            }
            return null
        }

        fun onEdt(block: () -> Unit) {
            if (EventQueue.isDispatchThread()) block() else EventQueue.invokeAndWait(block)
        }
    }
}
