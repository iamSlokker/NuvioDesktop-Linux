package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsProperties
import com.nuvio.app.LiveDisplayTests
import com.nuvio.app.core.ui.AppTheme
import com.nuvio.app.core.ui.DesktopNavigationGestureBridge
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.core.ui.TextInputFocusTracker
import com.nuvio.app.core.ui.focusedControl
import com.nuvio.app.features.collection.Collection
import com.nuvio.app.features.collection.CollectionEditorRepository
import com.nuvio.app.features.collection.CollectionEditorScreen
import com.nuvio.app.features.collection.CollectionFolder
import com.nuvio.app.features.collection.CollectionRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
 * A live window test for the Collection editor, which is a Settings sub-screen rather than a page in
 * the Settings content list.
 *
 * It opens Settings > Collections the way a person does, activates the collection row, walks the
 * editor's controls with Down (and Right into the folder row's edit/delete icons), presses Escape,
 * then presses Down once and checks that focus landed on a Settings control again. Before the
 * sub-screen helper existed, Escape left the editor open and a closed editor left nothing focused,
 * so Settings navigation stayed dead afterwards.
 *
 * Focus is global, so this is opt-in like the other live tests:
 *
 *     NUVIO_RUN_LIVE_DISPLAY_TESTS=1 ./gradlew :composeApp:desktopTest -Pnuvio.focusTests \
 *         --tests "*SettingsSubScreenLiveTest*"
 *
 * Not covered: dialogs (121 controls), drag lists (Running order), collections edit screen.
 */
class SettingsSubScreenLiveTest {

    @Test
    fun collectionEditorWalksAndEscapeReturnsFocusToSettings() {
        requireLive()

        val originalCollections = CollectionRepository.collections.value
        val fixture = Collection(
            id = "focus-fixture",
            title = "Focus Fixture",
            folders = listOf(CollectionFolder(id = "focus-folder", title = "Focus Folder")),
        )
        CollectionRepository.setCollections(
            listOf(fixture, Collection(id = "focus-fixture-2", title = "Second Fixture")),
        )

        val requestedPage = mutableStateOf<String?>(SettingsPage.Collections.name)
        val showEditor = mutableStateOf(false)
        val editorCollectionId = mutableStateOf<String?>(null)
        val focusManager = AtomicReference<FocusManager?>()
        val windowRef = AtomicReference<ComposeWindow?>()

        // The app's Back path: a row's Back asks the navigation bridge, which asks Settings. The
        // sub-screen registers with SettingsBackBridge, so this mirrors what App.kt does.
        val backScope = CoroutineScope(Dispatchers.Unconfined)
        val backJob = backScope.launch {
            DesktopNavigationGestureBridge.backRequests.collect { SettingsBackBridge.handle() }
        }

        onEdt {
            val created = ComposeWindow()
            created.setSize(1100, 950)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    focusManager.set(LocalFocusManager.current)
                    // The app keeps the Settings tab's state while the editor route is on top, so
                    // returning lands on the Collections page rather than back at Root.
                    val settingsState = rememberSaveableStateHolder()
                    if (showEditor.value) {
                        CollectionEditorScreen(
                            collectionId = editorCollectionId.value,
                            onBack = {
                                CollectionEditorRepository.clear()
                                showEditor.value = false
                            },
                        )
                    } else {
                        settingsState.SaveableStateProvider("settings") {
                            SettingsScreen(
                                modifier = Modifier.fillMaxSize(),
                                requestedPageName = requestedPage.value,
                                onRequestedPageConsumed = { requestedPage.value = null },
                                onOpenCollectionEditor = { id ->
                                    editorCollectionId.value = id
                                    showEditor.value = true
                                },
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
        val window = windowRef.get() ?: error("The test window was not created")

        try {
            if (!awaitTrue(5_000) { window.isFocused } || awaitFocusOwner() == null) {
                println("SKIPPED: the test window never took focus (locked session or focus-stealing prevention)")
                return
            }
            val manager = focusManager.get()
            assertNotNull(manager, "The composition never exposed a FocusManager")

            // 1. Settings > Collections, then walk to the fixture row and open the editor.
            assertTrue(
                openCollectionEditor(window, manager),
                "The collection row never took focus in the Settings page",
            )
            assertTrue(awaitTrue(6_000) { showEditor.value }, "Enter on the collection row did not open the editor")
            assertTrue(
                awaitTrue(6_000) { focusText(manager).contains("name") },
                "The editor did not focus its first control (Name); saw '${focusText(manager)}'",
            )

            // 2. Walk the editor controls.
            val stops = collectEditorStops(window, manager)
            val seen = stops.joinToString(" | ") { "${it.label} ${it.rowLabel.orEmpty()}" }
            listOf(
                "Name",
                "Backdrop image",
                "Pin Above Catalogs",
                "View Mode",
                "Show \"All\" Tab",
                "Focus Folder",
                "Edit",
                "Delete",
            ).forEach { expected ->
                assertTrue(
                    normalizeSubScreenLabel(seen).contains(normalizeSubScreenLabel(expected)),
                    "Editor control '$expected' was not a focus stop; walked: $seen",
                )
            }

            // 3. Escape closes the editor, and one Down moves focus in Settings again.
            window.pressKey(KeyEvent.VK_ESCAPE)
            assertTrue(
                awaitTrue(4_000) { !showEditor.value },
                "Escape did not close the collection editor; focus stayed on '${focusText(manager)}'",
            )
            Thread.sleep(500)

            val before = awaitSubScreenObservation(manager, 2_000)
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(250)
            val after = settle(manager, awaitSubScreenObservation(manager, 2_000))
            assertNotNull(after, "Down after closing the editor reached no control: Settings navigation is dead")
            assertTrue(
                before == null || after.id != before.id,
                "Down after closing the editor did not move focus; still on '${after.label}'",
            )
            val afterText = normalizeSubScreenLabel("${after.label} ${after.rowLabel.orEmpty()}")
            assertTrue(
                afterText.contains("fixture") || afterText.contains("collections"),
                "Down after closing the editor did not land on a Settings control; saw '${after.label}'",
            )

            // 4. Save and the header's back arrow close the editor by calling onBack directly rather
            // than through the Escape helper. That path used to dispose the editor without giving
            // the keys back, leaving Settings navigation dead.
            onEdt {
                editorCollectionId.value = fixture.id
                showEditor.value = true
            }
            assertTrue(awaitTrue(6_000) { showEditor.value }, "the editor did not reopen")
            Thread.sleep(400)
            onEdt { showEditor.value = false } // what Save / the header back arrow does
            assertTrue(awaitTrue(4_000) { !showEditor.value }, "the editor did not close")
            Thread.sleep(600)
            val restored = awaitSubScreenObservation(manager, 3_000)
            assertNotNull(restored, "closing the editor with Save/back left no focused control")
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(250)
            assertNotNull(
                awaitSubScreenObservation(manager, 2_000),
                "Settings navigation is dead after closing the editor with Save/back",
            )
        } finally {
            backJob.cancel()
            onEdt { window.apply { isVisible = false; dispose() } }
            CollectionEditorRepository.clear()
            CollectionRepository.setCollections(originalCollections)
        }
    }

    @Test
    fun escapeFromTheNameFieldLeavesTheFieldThenClosesTheEditor() {
        requireLive()

        val originalCollections = CollectionRepository.collections.value
        val fixture = Collection(
            id = "focus-fixture",
            title = "Focus Fixture",
            folders = listOf(CollectionFolder(id = "focus-folder", title = "Focus Folder")),
        )
        CollectionRepository.setCollections(
            listOf(fixture, Collection(id = "focus-fixture-2", title = "Second Fixture")),
        )

        val requestedPage = mutableStateOf<String?>(SettingsPage.Collections.name)
        val showEditor = mutableStateOf(false)
        val editorCollectionId = mutableStateOf<String?>(null)
        val focusManager = AtomicReference<FocusManager?>()
        val windowRef = AtomicReference<ComposeWindow?>()

        val backScope = CoroutineScope(Dispatchers.Unconfined)
        val backJob = backScope.launch {
            DesktopNavigationGestureBridge.backRequests.collect { SettingsBackBridge.handle() }
        }

        onEdt {
            val created = ComposeWindow()
            created.setSize(1100, 950)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    focusManager.set(LocalFocusManager.current)
                    val settingsState = rememberSaveableStateHolder()
                    if (showEditor.value) {
                        CollectionEditorScreen(
                            collectionId = editorCollectionId.value,
                            onBack = {
                                CollectionEditorRepository.clear()
                                showEditor.value = false
                            },
                        )
                    } else {
                        settingsState.SaveableStateProvider("settings") {
                            SettingsScreen(
                                modifier = Modifier.fillMaxSize(),
                                requestedPageName = requestedPage.value,
                                onRequestedPageConsumed = { requestedPage.value = null },
                                onOpenCollectionEditor = { id ->
                                    editorCollectionId.value = id
                                    showEditor.value = true
                                },
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
            val manager = focusManager.get()
            assertNotNull(manager, "no FocusManager")

            assertTrue(openCollectionEditor(window, manager), "the collection row never took focus")
            assertTrue(awaitTrue(6_000) { showEditor.value }, "Enter did not open the editor")

            // The editor opens on the Name field: the first Escape ends the edit and leaves the
            // field; the second Escape closes the editor.
            assertTrue(
                awaitTrue(5_000) { TextInputFocusTracker.active.value },
                "the editor did not open with the Name field focused (text input inactive); focus='${focusText(manager)}'",
            )

            window.pressKey(KeyEvent.VK_ESCAPE)
            assertTrue(
                awaitTrue(3_000) { !TextInputFocusTracker.active.value },
                "the first Escape did not leave the Name field",
            )
            assertTrue(showEditor.value, "the first Escape closed the editor instead of ending the edit")
            assertNotNull(manager.focusedControl(), "leaving the Name field stranded focus")

            window.pressKey(KeyEvent.VK_ESCAPE)
            assertTrue(
                awaitTrue(4_000) { !showEditor.value },
                "the second Escape did not close the collection editor",
            )
            Thread.sleep(500)
            val after = settle(manager, awaitSubScreenObservation(manager, 2_000))
            assertNotNull(after, "closing the editor left nothing focused")
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(250)
            val moved = settle(manager, awaitSubScreenObservation(manager, 2_000))
            assertNotNull(moved, "Down after closing the editor reached no control")
            assertTrue(
                after == null || moved == null || moved.id != after.id,
                "Down after closing the editor did not move focus",
            )
        } finally {
            backJob.cancel()
            onEdt { window.apply { isVisible = false; dispose() } }
            CollectionEditorRepository.clear()
            CollectionRepository.setCollections(originalCollections)
        }
    }

    @Test
    fun escapeClosesTheFolderEditorThenTheEditor() {
        requireLive()

        val originalCollections = CollectionRepository.collections.value
        val fixture = Collection(
            id = "focus-fixture",
            title = "Focus Fixture",
            folders = listOf(CollectionFolder(id = "focus-folder", title = "Focus Folder")),
        )
        CollectionRepository.setCollections(listOf(fixture))

        val requestedPage = mutableStateOf<String?>(SettingsPage.Collections.name)
        val showEditor = mutableStateOf(false)
        val editorCollectionId = mutableStateOf<String?>(null)
        val focusManager = AtomicReference<FocusManager?>()
        val windowRef = AtomicReference<ComposeWindow?>()

        val backScope = CoroutineScope(Dispatchers.Unconfined)
        val backJob = backScope.launch {
            DesktopNavigationGestureBridge.backRequests.collect { SettingsBackBridge.handle() }
        }

        onEdt {
            val created = ComposeWindow()
            created.setSize(1100, 950)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    focusManager.set(LocalFocusManager.current)
                    val settingsState = rememberSaveableStateHolder()
                    if (showEditor.value) {
                        CollectionEditorScreen(
                            collectionId = editorCollectionId.value,
                            onBack = {
                                CollectionEditorRepository.clear()
                                showEditor.value = false
                            },
                        )
                    } else {
                        settingsState.SaveableStateProvider("settings") {
                            SettingsScreen(
                                modifier = Modifier.fillMaxSize(),
                                requestedPageName = requestedPage.value,
                                onRequestedPageConsumed = { requestedPage.value = null },
                                onOpenCollectionEditor = { id ->
                                    editorCollectionId.value = id
                                    showEditor.value = true
                                },
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
            val manager = focusManager.get()
            assertNotNull(manager, "no FocusManager")

            assertTrue(openCollectionEditor(window, manager), "the collection row never took focus")
            assertTrue(awaitTrue(6_000) { showEditor.value }, "Enter did not open the editor")

            // Walk down to the folder row, Right into its Edit icon, Enter opens the folder editor.
            var current = settle(manager, awaitSubScreenObservation(manager, 6_000)) ?: return
            for (step in 0 until 40) {
                if (combined(current).contains("focus folder")) break
                window.pressKey(KeyEvent.VK_DOWN)
                Thread.sleep(140)
                current = settle(manager, awaitSubScreenObservation(manager, 800)) ?: current
            }
            assertTrue(combined(current).contains("focus folder"), "the folder row never took focus")
            window.pressKey(KeyEvent.VK_RIGHT)
            Thread.sleep(250)
            val onRight = focusText(manager)
            window.pressKey(KeyEvent.VK_ENTER)
            assertTrue(
                awaitTrue(4_000) { CollectionEditorRepository.uiState.value.showFolderEditor },
                "Enter on the folder's Edit did not open the folder editor; after Right focus='$onRight'",
            )
            Thread.sleep(400)
            val inFolderEditor = focusText(manager)
            val typing = TextInputFocusTracker.active.value

            // The folder editor opens on its Name field: the first Escape ends the edit, the second
            // closes the folder editor; the collection editor stays open throughout.
            window.pressKey(KeyEvent.VK_ESCAPE)
            Thread.sleep(300)
            window.pressKey(KeyEvent.VK_ESCAPE)
            assertTrue(
                awaitTrue(3_000) { !CollectionEditorRepository.uiState.value.showFolderEditor },
                "Escape did not close the folder editor; in-editor focus='$inFolderEditor' typing=$typing; " +
                    "after two Escapes focus='${focusText(manager)}'",
            )
            assertTrue(showEditor.value, "one Escape leaked past the folder editor and closed the collection editor")

            // Closing the folder editor returns focus to the editor's first control (the Name
            // field). Wait for that restore to land (as a user would), then the editor also takes
            // two Escapes: leave the field, then close.
            assertTrue(
                awaitTrue(4_000) { manager.focusedControl() != null },
                "closing the folder editor left nothing focused",
            )
            window.pressKey(KeyEvent.VK_ESCAPE)
            Thread.sleep(300)
            window.pressKey(KeyEvent.VK_ESCAPE)
            assertTrue(awaitTrue(4_000) { !showEditor.value }, "Escape did not close the collection editor")
        } finally {
            backJob.cancel()
            onEdt { window.apply { isVisible = false; dispose() } }
            CollectionEditorRepository.clear()
            CollectionRepository.setCollections(originalCollections)
        }
    }

    @Test
    fun folderListKeyboardReorderMovesAFolderAndCancels() {
        requireLive()

        val originalCollections = CollectionRepository.collections.value
        val first = CollectionFolder(id = "folder-a", title = "Alpha Folder")
        val second = CollectionFolder(id = "folder-b", title = "Beta Folder")
        val fixture = Collection(
            id = "reorder-fixture",
            title = "Reorder Fixture",
            folders = listOf(first, second),
        )
        CollectionRepository.setCollections(listOf(fixture))
        CollectionEditorRepository.initialize("reorder-fixture")

        val focusManager = AtomicReference<FocusManager?>()
        val windowRef = AtomicReference<ComposeWindow?>()
        onEdt {
            val created = ComposeWindow()
            created.setSize(1000, 900)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    focusManager.set(LocalFocusManager.current)
                    CollectionEditorScreen(collectionId = "reorder-fixture", onBack = {})
                }
            }
            created.isVisible = true
            created.toFront()
            created.requestFocus()
            windowRef.set(created)
        }
        val window = windowRef.get() ?: error("The test window was not created")

        try {
            if (!awaitTrue(5_000) { window.isFocused } || awaitFocusOwner() == null) {
                println("SKIPPED: the test window never took focus (locked session or focus-stealing prevention)")
                return
            }
            val manager = focusManager.get()
            assertNotNull(manager, "The composition never exposed a FocusManager")

            // Walk Down from the first control until the first folder row holds the keys.
            var current = settle(manager, awaitSubScreenObservation(manager, 6_000)) ?: return
            for (step in 0 until 30) {
                if (combined(current).contains("alpha folder")) break
                window.pressKey(KeyEvent.VK_DOWN)
                Thread.sleep(140)
                current = settle(manager, awaitSubScreenObservation(manager, 800)) ?: current
            }
            assertTrue(
                combined(current).contains("alpha folder"),
                "The first folder row never took focus; saw '${combined(current)}'",
            )

            // Select picks it up, Down moves it below Beta, Select drops.
            window.pressKey(KeyEvent.VK_ENTER)
            Thread.sleep(150)
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(150)
            window.pressKey(KeyEvent.VK_ENTER)
            Thread.sleep(300)

            assertTrue(
                awaitTrue(3_000) {
                    CollectionEditorRepository.uiState.value.folders.map { it.id } == listOf("folder-b", "folder-a")
                },
                "Dropping the picked-up folder did not move it: saw " +
                    CollectionEditorRepository.uiState.value.folders.map { it.id },
            )
        } finally {
            onEdt { window.apply { isVisible = false; dispose() } }
            CollectionEditorRepository.clear()
            CollectionRepository.setCollections(originalCollections)
        }
    }

    // ---------------------------------------------------------------------------------------

    /** Walks Settings to the fixture collection row and presses Enter on it. */
    private fun openCollectionEditor(window: ComposeWindow, manager: FocusManager): Boolean {
        var current = settle(manager, awaitSubScreenObservation(manager, 6_000)) ?: return false
        repeat(30) {
            if (combined(current).contains("focus fixture")) {
                window.pressKey(KeyEvent.VK_ENTER)
                return true
            }
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(140)
            current = settle(manager, awaitSubScreenObservation(manager, 800)) ?: current
        }
        return false
    }

    /** Records every Down stop, entering a folder row with Right to reach its edit/delete icons. */
    private fun collectEditorStops(window: ComposeWindow, manager: FocusManager): List<SubScreenObservation> {
        val stops = mutableListOf<SubScreenObservation>()
        var current = settle(manager, awaitSubScreenObservation(manager, 3_000)) ?: return stops
        var folderIconsVisited = false
        var idle = 0
        repeat(40) {
            stops += current
            if (!folderIconsVisited && combined(current).contains("focus folder")) {
                folderIconsVisited = true
                repeat(2) {
                    window.pressKey(KeyEvent.VK_RIGHT)
                    Thread.sleep(160)
                    settle(manager, awaitSubScreenObservation(manager, 800))?.let { stops += it }
                }
            }
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(160)
            val next = settle(manager, awaitSubScreenObservation(manager, 800))
            if (next == null || next.id == current.id) {
                idle += 1
                if (idle >= 2) return@repeat
            } else {
                idle = 0
                current = next
            }
        }
        return stops
    }

    // ---------------------------------------------------------------------------------------

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

        fun focusText(manager: FocusManager): String =
            focusSubScreenObservation(manager)?.let { combined(it) } ?: ""

        fun combined(observation: SubScreenObservation): String =
            normalizeSubScreenLabel("${observation.label} ${observation.rowLabel.orEmpty()}")

        fun focusSubScreenObservation(manager: FocusManager): SubScreenObservation? {
            val target = manager.focusedControl() ?: return null
            val modifierNode = target.node
            val coordinator = invoke(modifierNode, "getCoordinator\$ui") ?: return null
            val layoutNode = invoke(coordinator, "getLayoutNode") ?: return null
            val texts = mutableListOf<String>()
            collectSemantics(layoutNode, texts, 0)
            return SubScreenObservation(
                id = System.identityHashCode(target),
                label = texts.distinct().joinToString(" | "),
                rowLabel = rowLabel(layoutNode, texts),
            )
        }

        fun rowLabel(node: Any, ownTexts: List<String>): String? {
            val ownWords = normalizeSubScreenLabel(ownTexts.distinct().joinToString(" "))
                .split(' ')
                .filter { it.isNotEmpty() }
                .toSet()
            var current: Any = node
            repeat(5) {
                current = invoke(current, "getParent\$ui") ?: return null
                val texts = mutableListOf<String>()
                collectSemantics(current, texts, 0)
                val joined = texts.distinct().joinToString(" | ")
                val words = normalizeSubScreenLabel(joined).split(' ').filter { it.isNotEmpty() }.toSet()
                if ((words - ownWords - setOf("on", "off", "selected", "configured", "not")).isNotEmpty()) {
                    return joined
                }
            }
            return null
        }

        fun collectSemantics(node: Any, out: MutableList<String>, depth: Int) {
            if (depth > 60) return
            val config = invoke(node, "getSemanticsConfiguration") as? SemanticsConfiguration
            if (config != null) {
                if (config.contains(SemanticsProperties.Text)) {
                    config.get(SemanticsProperties.Text).forEach { out += it.text }
                }
                if (config.contains(SemanticsProperties.ContentDescription)) {
                    out += config.get(SemanticsProperties.ContentDescription)
                }
                if (config.contains(SemanticsProperties.EditableText)) {
                    out += config.get(SemanticsProperties.EditableText).text
                }
            }
            val children = invoke(node, "getFoldedChildren\$ui") as? List<*> ?: return
            for (child in children) if (child != null) collectSemantics(child, out, depth + 1)
        }

        fun invoke(target: Any, name: String): Any? =
            runCatching { target.javaClass.methods.firstOrNull { it.name == name }?.invoke(target) }.getOrNull()

        fun awaitSubScreenObservation(manager: FocusManager, timeoutMs: Long): SubScreenObservation? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                focusSubScreenObservation(manager)?.let { return it }
                Thread.sleep(25)
            }
            return focusSubScreenObservation(manager)
        }

        fun settle(manager: FocusManager, first: SubScreenObservation?): SubScreenObservation? {
            var current = first ?: return null
            var stablePolls = 0
            repeat(20) {
                Thread.sleep(80)
                val again = focusSubScreenObservation(manager)
                if (again != null && again.id == current.id) {
                    stablePolls += 1
                    if (stablePolls >= 3) return current
                } else if (again != null) {
                    current = again
                    stablePolls = 0
                }
            }
            return current
        }

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

    private fun ComposeWindow.pressKey(keyCode: Int) {
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
    }
}

private fun normalizeSubScreenLabel(value: String): String =
    value.lowercase()
        .replace('\u2019', '\'')
        .replace(Regex("[^a-z0-9&]+"), " ")
        .trim()

private data class SubScreenObservation(
    val id: Int,
    val label: String,
    val rowLabel: String?,
)
