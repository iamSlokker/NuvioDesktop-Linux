package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import com.nuvio.app.LiveDisplayTests
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.focusedControl
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
 * The two entry paths the user reported: opening Settings from a plain tab (the homepage path) and
 * landing on a setting found through Settings Search.
 *
 * Search is exercised at its destination — the same `SettingsScrollAnchor` request the result click
 * makes — because the anchor is what decides whether the searched row really takes the keys.
 */
class SettingsEntryAndSearchLiveTest {

    @Test
    fun settingsOpensWithAWorkingFocusAndSurvivesRepeatedPageChanges() {
        requireLive()
        val requested = mutableStateOf<String?>(null)
        val focusManager = AtomicReference<FocusManager?>()
        val window = openWindow {
            focusManager.set(LocalFocusManager.current)
            SettingsScreen(
                modifier = Modifier.fillMaxSize(),
                requestedPageName = requested.value,
                onRequestedPageConsumed = { requested.value = null },
                onSwitchProfile = {},
                onNavigateToHome = {},
            )
        }.let { it }
        try {
            val manager = awaitManager(window, focusManager) ?: return
            // A plain entry (no requested page) must leave a control focused, not null. The page's
            // entry runs over the first frames, so poll rather than sampling once.
            assertTrue(
                awaitTrue(5_000) { manager.focusedControl() != null },
                "opening Settings left nothing focused",
            )

            // The entry runs over the first frames; let it settle before sampling the control.
            Thread.sleep(800)
            // Arrows must move focus immediately, with no click first.
            val before = manager.focusedControl()
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(600)
            val after = manager.focusedControl()
            assertNotNull(after, "Down after opening Settings left nothing focused")
            assertTrue(
                before == null || System.identityHashCode(after) != System.identityHashCode(before),
                "Down after opening Settings did not move focus away from the entry control",
            )

            repeat(3) {
                onEdt { requested.value = SettingsPage.Player.name }
                Thread.sleep(450)
                onEdt { requested.value = SettingsPage.Root.name }
                Thread.sleep(450)
                assertTrue(
                    awaitTrue(3_000) { manager.focusedControl() != null },
                    "a page change left focus null (cycle $it)",
                )
            }
        } finally {
            onEdt { window.isVisible = false; window.dispose() }
        }
    }

    @Test
    fun aSearchAnchorFocusesTheRowItNames() {
        requireLive()
        val requested = mutableStateOf<String?>(SettingsPage.Playback.name)
        val focusManager = AtomicReference<FocusManager?>()
        val window = openWindow {
            focusManager.set(LocalFocusManager.current)
            SettingsScreen(
                modifier = Modifier.fillMaxSize(),
                requestedPageName = requested.value,
                onRequestedPageConsumed = { requested.value = null },
                onSwitchProfile = {},
                onNavigateToHome = {},
            )
        }.let { it }
        try {
            val manager = awaitManager(window, focusManager) ?: return
            Thread.sleep(500)
            // The exact request the Volume Control search result makes (Video & Audio).
            SettingsScrollAnchor.request(SettingsScrollAnchor.searchKey("desktop-volume-control"))
            assertTrue(
                awaitTrue(4_000) { focusLabel(manager).contains("volume") },
                "the searched row did not take focus; saw '${focusLabel(manager)}'",
            )
        } finally {
            onEdt { window.isVisible = false; window.dispose() }
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun openWindow(content: @androidx.compose.runtime.Composable () -> Unit): ComposeWindow {
        val ref = AtomicReference<ComposeWindow?>()
        onEdt {
            val created = ComposeWindow()
            created.setSize(1200, 950)
            created.setContent { NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) { content() } }
            created.isVisible = true
            created.toFront()
            created.requestFocus()
            ref.set(created)
        }
        return ref.get() ?: error("window")
    }

    private fun awaitManager(window: ComposeWindow, ref: AtomicReference<FocusManager?>): FocusManager? {
        if (!awaitTrue(5_000) { window.isFocused } || awaitFocusOwner() == null) {
            println("SKIPPED: window never took focus")
            return null
        }
        return ref.get()
    }

    private fun ComposeWindow.pressKey(keyCode: Int) {
        // The standalone window can lose the compositor's focus between opening and the first
        // key, even though AWT still reports it focused.
        if (!isFocused) {
            toFront()
            requestFocus()
            Thread.sleep(300)
        }
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

        fun focusLabel(manager: FocusManager): String {
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
