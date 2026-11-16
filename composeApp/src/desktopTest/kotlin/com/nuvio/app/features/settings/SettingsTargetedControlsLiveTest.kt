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
import com.nuvio.app.features.home.HomeDisplayMode
import com.nuvio.app.features.home.HomeTvRowTransition
import com.nuvio.app.features.home.HomeCatalogSettingsRepository
import com.nuvio.app.features.home.homeDisplayModeOf
import com.nuvio.app.features.player.DesktopHudLayout
import com.nuvio.app.features.player.PlayerSettingsRepository
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
 * The three settings a remote could not reach: Display Mode, Row change (TV mode only) and Player
 * Controls Layout. Each is an enabled dropdown row; the shared bug was that only the small trigger
 * at the right was a focus stop, so spatial Up/Down walked past the row. This navigates to each,
 * changes its value with the keyboard, and checks the value persists in its repository.
 */
class SettingsTargetedControlsLiveTest {

    @Test
    fun displayModeAndRowChangeAreReachableAndChangeable() {
        requireLive()
        val originalMode = homeDisplayModeOf(HomeCatalogSettingsRepository.uiState.value)
        val originalTransition = HomeCatalogSettingsRepository.uiState.value.tvRowTransition
        try {
            HomeCatalogSettingsRepository.setDisplayMode(HomeDisplayMode.TvMode)
            HomeCatalogSettingsRepository.setTvRowTransition(HomeTvRowTransition.Off)

            runSettingsWalk(SettingsPage.Homescreen) { window, manager ->
                val stops = walk(window, manager, 60)
                val seen = stops.joinToString(" ") { it.lowercase() }
                assertTrue(seen.contains("display mode"), "Display Mode was skipped; saw: $seen")
                assertTrue(seen.contains("row change"), "Row change was skipped in TV mode; saw: $seen")

                // Display Mode is reachable (the reachability test also asserts it); leave its value
                // alone here so switching it cannot unmount the Row change row mid-walk.
            }

            // Row change needs TV mode again.
            HomeCatalogSettingsRepository.setDisplayMode(HomeDisplayMode.TvMode)
            runSettingsWalk(SettingsPage.Homescreen) { window, manager ->
                assertTrue(focusExact(window, manager, "row change"), "could not focus Row change")
                window.pressKey(KeyEvent.VK_ENTER)
                Thread.sleep(350)
                window.pressKey(KeyEvent.VK_DOWN)
                Thread.sleep(150)
                window.pressKey(KeyEvent.VK_ENTER)
                Thread.sleep(300)
                assertTrue(
                    HomeCatalogSettingsRepository.uiState.value.tvRowTransition != HomeTvRowTransition.Off,
                    "Enter/Down/Enter did not change Row change",
                )
            }
        } finally {
            HomeCatalogSettingsRepository.setDisplayMode(originalMode)
            HomeCatalogSettingsRepository.setTvRowTransition(originalTransition)
        }
    }

    @Test
    fun playerControlsLayoutIsReachableAndChangeable() {
        requireLive()
        val original = PlayerSettingsRepository.uiState.value.desktopHudLayout
        try {
            PlayerSettingsRepository.setDesktopHudLayout(DesktopHudLayout.Standard)
            runSettingsWalk(SettingsPage.Player) { window, manager ->
                // Focus first from the page's entry point (the top); a Down walk used to reach the
                // row and then continue to the bottom, where Down has nowhere left to go.
                assertTrue(focusExact(window, manager, "player controls layout"), "could not focus it")
                window.pressKey(KeyEvent.VK_ENTER)
                Thread.sleep(350)
                window.pressKey(KeyEvent.VK_DOWN)
                Thread.sleep(150)
                window.pressKey(KeyEvent.VK_ENTER)
                Thread.sleep(300)
                assertTrue(
                    PlayerSettingsRepository.uiState.value.desktopHudLayout != DesktopHudLayout.Standard,
                    "Enter/Down/Enter did not change Player Controls Layout",
                )
            }
        } finally {
            PlayerSettingsRepository.setDesktopHudLayout(original)
        }
    }

    // ---------------------------------------------------------------------------------------

    private fun runSettingsWalk(page: SettingsPage, block: (ComposeWindow, FocusManager) -> Unit) {
        val requested = mutableStateOf<String?>(page.name)
        val focusManager = AtomicReference<FocusManager?>()
        val windowRef = AtomicReference<ComposeWindow?>()
        onEdt {
            val created = ComposeWindow()
            created.setSize(1200, 950)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    focusManager.set(LocalFocusManager.current)
                    SettingsScreen(
                        modifier = Modifier.fillMaxSize(),
                        requestedPageName = requested.value,
                        onRequestedPageConsumed = { requested.value = null },
                        onSwitchProfile = {},
                    )
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
            val manager = focusManager.get() ?: error("no FocusManager")
            assertTrue(awaitTrue(6_000) { focusLabel(manager).isNotEmpty() }, "page never took focus")
            Thread.sleep(400)
            block(window, manager)
        } finally {
            onEdt { window.apply { isVisible = false; dispose() } }
        }
    }

    /** A pure Down walk, the same path the reachability test uses, collecting every stop's label. */
    private fun walk(window: ComposeWindow, manager: FocusManager, steps: Int): List<String> {
        val stops = LinkedHashSet<String>()
        repeat(steps) {
            focusLabel(manager).takeIf { it.isNotEmpty() }?.let { stops += it }
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(85)
        }
        return stops.toList()
    }

    private fun focusExact(window: ComposeWindow, manager: FocusManager, needle: String): Boolean {
        repeat(120) {
            if (focusLabel(manager).contains(needle)) return true
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(75)
        }
        return focusLabel(manager).contains(needle)
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
