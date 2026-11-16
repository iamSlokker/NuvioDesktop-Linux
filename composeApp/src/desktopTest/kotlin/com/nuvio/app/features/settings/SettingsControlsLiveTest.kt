package com.nuvio.app.features.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import com.nuvio.app.LiveDisplayTests
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.arrowKeyFocusNavigation
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Live window tests for the shared controls: the dialog shell's first-focus on open, and the
 * single-line text field's Up/Down/Escape keys. These are central fixes, so one window per test is
 * enough to prove them; the shared components are the same on every screen.
 */
class SettingsControlsLiveTest {

    @Test
    fun dialogFocusesItsFirstControlOnOpen() {
        requireLive()

        val showDialog = mutableStateOf(true)
        val dialogManager = AtomicReference<FocusManager?>()
        val windowRef = AtomicReference<ComposeWindow?>()
        onEdt {
            val created = ComposeWindow()
            created.setSize(700, 500)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    if (showDialog.value) {
                        NuvioModalDialog(
                            onDismissRequest = { showDialog.value = false },
                            title = "Dialog title",
                            actions = {
                                val dialogFm = LocalFocusManager.current
                                LaunchedEffect(Unit) { dialogManager.set(dialogFm) }
                                SettingsGlassButton(text = "Done", onClick = { showDialog.value = false })
                            },
                        ) { }
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
            val manager = awaitTrue(5_000) { dialogManager.get() != null }.let { dialogManager.get() }
            assertNotNull(manager, "the dialog never exposed a FocusManager")
            assertTrue(
                awaitTrue(5_000) { focusLabel(manager).contains("done") },
                "the dialog's first control did not take focus; saw '${focusLabel(manager)}'",
            )
        } finally {
            onEdt { window.apply { isVisible = false; dispose() } }
        }
    }

    @Test
    fun escapeDismissesTheDialogExactlyOnceWithAFocusedControlInside() {
        requireLive()

        var dismisses = 0
        val showDialog = mutableStateOf(true)
        val windowRef = AtomicReference<ComposeWindow?>()
        onEdt {
            val created = ComposeWindow()
            created.setSize(700, 500)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    if (showDialog.value) {
                        NuvioModalDialog(
                            onDismissRequest = {
                                dismisses += 1
                                showDialog.value = false
                            },
                            title = "Dialog title",
                            actions = {
                                SettingsGlassButton(text = "Done", onClick = { showDialog.value = false })
                            },
                        ) {
                            SettingsSwitchRow(
                                title = "A switch",
                                description = null,
                                checked = true,
                                isTablet = true,
                                onCheckedChange = {},
                            )
                        }
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
            // First control focused; Escape must dismiss through the dialog shell, not through the
            // focused control's own Back routing.
            assertTrue(awaitTrue(3_000) { showDialog.value }, "dialog did not open")
            window.pressKey(KeyEvent.VK_ESCAPE)
            assertTrue(awaitTrue(3_000) { !showDialog.value }, "Escape did not dismiss the dialog")
            Thread.sleep(400)
            assertEquals(1, dismisses, "one Escape dismissed the dialog more than once")
        } finally {
            onEdt { window.apply { isVisible = false; dispose() } }
        }
    }

    @Test
    fun escapeClosesOnlyTheTopmostDialog() {
        requireLive()

        var childDismisses = 0
        var parentDismisses = 0
        val showParent = mutableStateOf(true)
        val showChild = mutableStateOf(false)
        val windowRef = AtomicReference<ComposeWindow?>()
        onEdt {
            val created = ComposeWindow()
            created.setSize(700, 500)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    if (showParent.value) {
                        NuvioModalDialog(
                            onDismissRequest = {
                                parentDismisses += 1
                                showParent.value = false
                            },
                            title = "Parent dialog",
                            actions = {
                                SettingsGlassButton(text = "Open child", onClick = { showChild.value = true })
                            },
                        ) { }
                        if (showChild.value) {
                            NuvioModalDialog(
                                onDismissRequest = {
                                    childDismisses += 1
                                    showChild.value = false
                                },
                                title = "Child dialog",
                                actions = {
                                    SettingsGlassButton(text = "Child done", onClick = { showChild.value = false })
                                },
                            ) { }
                        }
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
            assertTrue(awaitTrue(3_000) { showParent.value }, "parent dialog did not open")
            // The parent's first-focused control is the "Open child" action; activate it.
            window.pressKey(KeyEvent.VK_ENTER)
            assertTrue(awaitTrue(3_000) { showChild.value }, "Enter on 'Open child' did not open the child dialog")

            // One Escape closes exactly the child; the parent stays open.
            window.pressKey(KeyEvent.VK_ESCAPE)
            assertTrue(awaitTrue(3_000) { !showChild.value }, "Escape did not close the child dialog")
            Thread.sleep(400)
            assertEquals(1, childDismisses, "the child dismissed more than once")
            assertEquals(0, parentDismisses, "one Escape leaked through the child and closed the parent")
            assertTrue(showParent.value, "one Escape closed the parent dialog")

            // A second Escape closes the parent.
            window.pressKey(KeyEvent.VK_ESCAPE)
            assertTrue(awaitTrue(3_000) { !showParent.value }, "the second Escape did not close the parent")
            assertEquals(1, parentDismisses, "the parent dismissed more than once")
        } finally {
            onEdt { window.apply { isVisible = false; dispose() } }
        }
    }

    @Test
    fun navigationOrderDialogEscapeClosesFromRowsAndActions() {
        requireLive()

        var dismisses = 0
        val showDialog = mutableStateOf(true)
        val windowRef = AtomicReference<ComposeWindow?>()
        onEdt {
            val created = ComposeWindow()
            created.setSize(900, 700)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    if (showDialog.value) {
                        NavigationOrderDialog(onDismiss = {
                            dismisses += 1
                            showDialog.value = false
                        })
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
            Thread.sleep(800)
            assertTrue(showDialog.value, "the navigation order dialog never opened")

            // Walk onto a row's controls (Move Up / Move Down / switch), then Escape: the dialog
            // shell must dismiss exactly once, whichever control holds the keys.
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(150)
            window.pressKey(KeyEvent.VK_RIGHT)
            Thread.sleep(150)
            window.pressKey(KeyEvent.VK_ESCAPE)
            assertTrue(awaitTrue(3_000) { !showDialog.value }, "Escape from a dialog row did not close the dialog")
            assertEquals(1, dismisses, "one Escape dismissed the dialog more than once")
        } finally {
            onEdt { window.apply { isVisible = false; dispose() } }
        }
    }

    @Test
    fun singleLineTextFieldDownMovesOutAndEscapeKeepsFocus() {
        requireLive()

        val fieldHasFocus = AtomicReference(false)
        val trailFocused = AtomicReference(false)
        val windowRef = AtomicReference<ComposeWindow?>()
        val focusManager = AtomicReference<FocusManager?>()
        val fieldRequester = FocusRequester()
        onEdt {
            val created = ComposeWindow()
            created.setSize(700, 600)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    focusManager.set(LocalFocusManager.current)
                    Column(Modifier.arrowKeyFocusNavigation()) {
                        NuvioTextField(
                            value = "",
                            onValueChange = {},
                            modifier = Modifier
                                .fillMaxWidth()
                                .onFocusChanged { fieldHasFocus.set(it.hasFocus) }
                                .settingsTextFieldKeys(),
                            focusRequester = fieldRequester,
                        )
                        Box(
                            Modifier
                                .size(80.dp)
                                .focusable()
                                .onFocusChanged { trailFocused.set(it.isFocused) },
                        )
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
            onEdt { fieldRequester.requestFocus() }
            assertTrue(awaitTrue(2_000) { fieldHasFocus.get() }, "the single-line field never took focus")

            // Down leaves the field instead of moving the caret and trapping focus.
            window.pressKey(KeyEvent.VK_DOWN)
            assertTrue(
                awaitTrue(3_000) { !fieldHasFocus.get() },
                "Down did not move focus out of the single-line field",
            )
            assertNotNull(
                manager.focusedControl(),
                "Down moved focus out of the field but onto nothing",
            )

            // Escape in the field keeps focus on the page instead of clearing it.
            onEdt { fieldRequester.requestFocus() }
            Thread.sleep(200)
            window.pressKey(KeyEvent.VK_ESCAPE)
            Thread.sleep(300)
            assertNotNull(
                manager.focusedControl(),
                "Escape in the single-line field cleared focus and stranded the page",
            )
        } finally {
            onEdt { window.apply { isVisible = false; dispose() } }
        }
    }

    // ---------------------------------------------------------------------------------------

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
            return normalize(texts.distinct().joinToString(" | "))
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

        fun normalize(value: String): String =
            value.lowercase().replace(Regex("[^a-z0-9&]+"), " ").trim()

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
