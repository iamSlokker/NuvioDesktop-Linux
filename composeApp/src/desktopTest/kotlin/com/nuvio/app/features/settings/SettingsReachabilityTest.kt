package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.key
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
import org.junit.Rule
import org.junit.rules.ErrorCollector
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Robot
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test

/**
 * Walks every settings page on a real window with real AWT Down keys and checks that the
 * literal-labelled, ungated, non-dialog controls in `docs/settings-control-checklist.md` are
 * focus stops in the page's content.
 *
 * The window hosts the real [SettingsScreen], so the page content, the tablet layout
 * (`LocalSettingsPage`/`LocalSettingsSectionCards`), the sidebar and the focus routing are the
 * app's own. Pages are selected through `requestedPageName`; after the page change the screen
 * focuses its first content control, exactly as it does when a person opens the page.
 *
 * Scope and honest limits:
 *  - Controls marked `inside dialog` are inventoried but not opened: a dialog is a separate focus
 *    owner and needs its opener activated first.
 *  - Controls marked `greyed out ...` or `depends on integration` are inventoried but not asserted.
 *    A disabled row is meant to be skipped by focus, and an integration row needs a configured
 *    service before it draws.
 *  - Rows whose count depends on user data are checked by their representative row, not per item.
 *  - Action icons and drag handles are not part of the Down walk: they sit inside a row and are
 *    reached with Right. Their independent Right reachability is covered by
 *    [SettingsSliderReachabilityTest]; this test leaves them out of its assertion list.
 *
 * Not covered: dialogs (121 controls), drag lists (Running order), collections edit screen.
 *  - On desktop, Plugins is drawn inside Addons, Meta Screen inside Appearance, Continue Watching
 *    inside Homescreen and Downloads inside Auto Downloads. Their expected controls are folded into
 *    the host page's expectation, so one walk covers both.
 *  - Notifications is disabled by `AppFeaturePolicy` on desktop and cannot be opened.
 *
 * Focus is global, so while this runs keystrokes meant for another application land in this window.
 * It is therefore opt-in:
 *
 *     NUVIO_RUN_LIVE_DISPLAY_TESTS=1 ./gradlew :composeApp:desktopTest -Pnuvio.focusTests \
 *         --tests "*SettingsReachabilityTest*"
 *
 * Every missing control is reported through [ErrorCollector], so one run names all unreachable
 * controls rather than stopping at the first. Order in the checklist is source order; the walk
 * records the actual focus order, so a control is counted reachable when it appears at or after
 * the previous match, or anywhere earlier (shared/helper rows can compose in a different order).
 */
class SettingsReachabilityTest {

    @get:Rule
    val errors = ErrorCollector()

    private var skippedTotal = 0
    private var passedTotal = 0
    private var failedTotal = 0

    @Test
    fun everyAssertedControlIsReachableWithDown() {
        LiveDisplayTests.assumeEnabled()
        assumeTrue(
            "Takes OS focus and would swallow keystrokes; run with -Pnuvio.focusTests",
            System.getProperty("nuvio.focusTests") == "true",
        )
        assumeFalse("Live display test skipped: a non-headless display is required", GraphicsEnvironment.isHeadless())

        val requested = mutableStateOf<String?>(SettingsPage.Root.name)
        val focusManager = AtomicReference<FocusManager?>()
        val windowRef = AtomicReference<ComposeWindow?>()
        onEdt {
            val created = ComposeWindow()
            created.setSize(1000, 900)
            created.setContent {
                NuvioTheme(darkTheme = true, appTheme = AppTheme.CRIMSON) {
                    focusManager.set(LocalFocusManager.current)
                    // A new key per requested page rebuilds the screen, so the content
                    // LazyColumn starts at the top instead of keeping the previous page's
                    // scroll offset. The app resets scroll through its own entry points; this
                    // test reaches the page through requestedPageName and must do the same.
                    key(requested.value) {
                        SettingsScreen(
                            modifier = Modifier.fillMaxSize(),
                            requestedPageName = requested.value,
                            // The app passes a real handler; without it the Account page omits
                            // its Switch Profile row and the walk would call that row unreachable.
                            onSwitchProfile = {},
                        )
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
            if (manager == null) {
                errors.addError(AssertionError("The Settings composition never exposed a FocusManager"))
                return
            }

            for (expected in expectedPages) {
                if (expected.controls.isEmpty()) {
                    println("PAGE ${expected.name}: skipped (no asserted controls)")
                    continue
                }
                if (expected.page == SettingsPage.Addons) {
                    if (!openAddonsFromSidebar(window, manager, requested)) {
                        failedTotal += 1
                        errors.addError(
                            AssertionError(
                                "Unreachable page: Addons never opened from the Settings category list; " +
                                    "perf page stayed at '$perfSettingsPage'",
                            ),
                        )
                        continue
                    }
                } else {
                    onEdt { requested.value = expected.page.name }
                    if (!awaitTrue(8_000) { perfSettingsPage == expected.page.name }) {
                        failedTotal += 1
                        errors.addError(
                            AssertionError(
                                "Unreachable page: ${expected.name} (${expected.page.name}) never opened; " +
                                    "perf page stayed at '$perfSettingsPage'",
                            ),
                        )
                        continue
                    }
                }

                // The page change focuses the first content control on the next frames. Waiting for a
                // focus target rather than a fixed sleep keeps the first stop from being missed.
                awaitTrue(3_000) { focusObservation(manager) != null }
                Thread.sleep(500)
                val stops = collectFocusStops(window, manager)
                verifyPage(expected, stops)
            }
            println("SUMMARY skipped=$skippedTotal passed=$passedTotal failed=$failedTotal")
        } finally {
            onEdt { window.apply { isVisible = false; dispose() } }
        }
    }

    /**
     * Opens Addons the way a person does: return to a page with a sidebar row, move Left into
     * the category list, walk it with Down/Up until the Addons row has focus, activate it, then
     * move Right into the page content if the page-change focus did not already land there.
     */
    private fun openAddonsFromSidebar(
        window: ComposeWindow,
        manager: FocusManager,
        requested: androidx.compose.runtime.MutableState<String?>,
    ): Boolean {
        onEdt { requested.value = SettingsPage.Root.name }
        if (!awaitTrue(8_000) { perfSettingsPage == SettingsPage.Root.name }) return false
        awaitTrue(3_000) { focusObservation(manager) != null }
        Thread.sleep(300)

        // Left twice: the first leaves the content, the second makes sure a child control did
        // not keep the keys.
        repeat(2) {
            window.pressKey(KeyEvent.VK_LEFT)
            Thread.sleep(150)
            settle(manager, awaitObservation(manager, 800))
        }

        fun sidebarLabel(): String =
            normalizeLabel(focusObservation(manager)?.label.orEmpty())

        fun isAddonsRow(): Boolean = sidebarLabel() == "addons"

        for (direction in listOf(KeyEvent.VK_DOWN, KeyEvent.VK_UP)) {
            for (step in 0 until 80) {
                if (isAddonsRow()) {
                    window.pressKey(KeyEvent.VK_ENTER)
                    if (awaitTrue(6_000) { perfSettingsPage == SettingsPage.Addons.name }) {
                        awaitTrue(3_000) { focusObservation(manager) != null }
                        // If focus stayed on the category row, Right enters the content. The
                        // page-change effect normally does this on its own.
                        if (isAddonsRow()) {
                            window.pressKey(KeyEvent.VK_RIGHT)
                            Thread.sleep(250)
                            settle(manager, awaitObservation(manager, 800))
                        }
                        Thread.sleep(300)
                        return true
                    }
                    return false
                }
                window.pressKey(direction)
                Thread.sleep(120)
                settle(manager, awaitObservation(manager, 700))
            }
        }
        return false
    }

    // ---------------------------------------------------------------------------------------

    private fun verifyPage(expected: PageExpectation, stops: List<Observation>) {
        if (expected.controls.isEmpty()) {
            println("PAGE ${expected.name}: no asserted controls; ${stops.size} focus stops")
            return
        }

        var cursor = 0
        var reached = 0
        var skipped = 0
        for (control in expected.controls) {
            if (control.skipped) {
                skipped += 1
                skippedTotal += 1
                continue
            }
            var found = -1
            for (index in cursor until stops.size) {
                if (matches(stops[index], control)) {
                    found = index
                    break
                }
            }
            if (found < 0) {
                // Shared/helper rows can compose in a different order than the source. A control
                // that was focused before the previous one is still reachable, so look backwards
                // before calling it unreachable.
                for (index in 0 until cursor) {
                    if (matches(stops[index], control)) {
                        found = index
                        break
                    }
                }
            }
            if (found < 0) {
                failedTotal += 1
                errors.addError(
                    AssertionError(
                        "Unreachable control: ${expected.name} / ${control.label} " +
                            "(${control.file}:${control.line}, ${control.type}) was not a focus stop. " +
                            "Walked ${stops.size} stops: " +
                            stops.take(10).joinToString { it.label.take(40) },
                    ),
                )
            } else {
                reached += 1
                passedTotal += 1
                cursor = maxOf(cursor, found + 1)
            }
        }
        println("PAGE ${expected.name}: reached $reached/${expected.controls.size - skipped} asserted controls, skipped $skipped, in ${stops.size} focus stops")
        if (reached < expected.controls.size) {
            println("  STOPS ${expected.name}: " + stops.joinToString(" || ") { it.label.take(70) })
        }
    }

    private fun matches(observation: Observation, control: ExpectedControl): Boolean {
        val needle = normalizeLabel(control.label)
        if (needle.isEmpty()) return false
        // The focus target can be an inner value node ("Auto", "Balanced"). Match against the
        // enclosing row text as well as the focused node's own text, so the row title is what
        // decides reachability.
        val haystacks = listOfNotNull(observation.label, observation.rowLabel)
        return haystacks.any { raw ->
            val haystack = normalizeLabel(raw)
            if (haystack.isEmpty()) return@any false
            if (haystack.contains(needle)) return@any true
            val words = needle.split(' ').filter { it.length > 2 }
            words.isNotEmpty() && words.all { haystack.contains(it) }
        }
    }

    private fun collectFocusStops(window: ComposeWindow, manager: FocusManager): List<Observation> {
        val stops = mutableListOf<Observation>()
        var initial = awaitObservation(manager, 2_000)
        if (initial == null) {
            // A page with no entry point yet may still be asking for the keys; a Down from the
            // content root puts them on the first stop.
            repeat(3) {
                window.pressKey(KeyEvent.VK_DOWN)
                Thread.sleep(250)
                if (initial == null) initial = focusObservation(manager)
            }
        }
        if (initial == null) return stops
        stops += initial
        var lastId = initial.id
        var idle = 0
        while (idle < 8 && stops.size < 500) {
            window.pressKey(KeyEvent.VK_DOWN)
            Thread.sleep(120)
            val changed = awaitObservation(manager, 1_200)?.takeIf { it.id != lastId }
            // The move starts a bring-into-view animation. Pressing again before it settles makes
            // the next Down land two rows down, which would under-report reachability, so wait
            // until the focused node has stayed put for a few polls before the next key.
            val stable = settle(manager, changed)
            if (stable == null || stable.id == lastId) {
                idle += 1
                Thread.sleep(300)
            } else {
                stops += stable
                lastId = stable.id
                idle = 0
                Thread.sleep(150)
            }
        }
        return stops
    }

    private fun ComposeWindow.pressKey(keyCode: Int) {
        // Robot makes the X server deliver the key, so Compose sees the same event path as a
        // physical keypress rather than a synthetic event posted straight at the focus owner.
        robot.keyPress(keyCode)
        robot.keyRelease(keyCode)
    }

    // ---------------------------------------------------------------------------------------

    private companion object {
        val robot: Robot by lazy { Robot() }

        fun focusObservation(manager: FocusManager): Observation? {
            val target = manager.focusedControl() ?: return null
            val modifierNode = target.node
            val coordinator = invoke(modifierNode, "getCoordinator\$ui") ?: return null
            val layoutNode = invoke(coordinator, "getLayoutNode") ?: return null
            val texts = mutableListOf<String>()
            collectSemantics(layoutNode, texts, 0)
            return Observation(
                id = System.identityHashCode(target),
                label = texts.distinct().joinToString(" | "),
                rowLabel = rowLabel(layoutNode, texts),
                type = semanticsType(layoutNode),
            )
        }

        fun rowLabel(node: Any, ownTexts: List<String>): String? {
            val ownWords = normalizeLabel(ownTexts.distinct().joinToString(" "))
                .split(' ')
                .filter { it.isNotEmpty() }
                .toSet()
            var current: Any = node
            repeat(5) {
                current = invoke(current, "getParent\$ui") ?: return null
                val texts = mutableListOf<String>()
                collectSemantics(current, texts, 0)
                val joined = texts.distinct().joinToString(" | ")
                val words = normalizeLabel(joined).split(' ').filter { it.isNotEmpty() }.toSet()
                val added = words - ownWords - setOf("on", "off", "selected", "configured", "not")
                if (added.isNotEmpty()) return joined
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

        fun semanticsType(node: Any): String {
            val config = invoke(node, "getSemanticsConfiguration") as? SemanticsConfiguration ?: return "row"
            return when {
                config.contains(SemanticsProperties.ToggleableState) -> "switch"
                config.contains(SemanticsProperties.ProgressBarRangeInfo) -> "slider"
                config.contains(SemanticsProperties.EditableText) -> "text"
                config.contains(SemanticsProperties.Selected) -> "choice"
                else -> "row"
            }
        }


        fun invoke(target: Any, name: String): Any? =
            runCatching { target.javaClass.methods.firstOrNull { it.name == name }?.invoke(target) }.getOrNull()

        fun awaitObservation(manager: FocusManager, timeoutMs: Long): Observation? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                focusObservation(manager)?.let { return it }
                Thread.sleep(25)
            }
            return focusObservation(manager)
        }

        fun settle(manager: FocusManager, first: Observation?): Observation? {
            var current = first ?: return null
            var stablePolls = 0
            repeat(20) {
                Thread.sleep(80)
                val again = focusObservation(manager)
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
}

private fun normalizeLabel(value: String): String =
    value.lowercase()
        .replace('\u2019', '\'')
        .replace(Regex("[^a-z0-9&]+"), " ")
        .trim()

private data class Observation(
    val id: Int,
    val label: String,
    val rowLabel: String?,
    val type: String,
)

private data class ExpectedControl(
    val label: String,
    val file: String,
    val line: Int,
    val type: String,
    val skipped: Boolean = false,
)

private data class PageExpectation(
    val name: String,
    val page: SettingsPage,
    val controls: List<ExpectedControl>,
)

private val expectedPages: List<PageExpectation> = listOf(
    PageExpectation(
        name = "Root",
        page = SettingsPage.Root,
        controls = listOf(
            ExpectedControl("Addons", "SettingsRootPage.kt", 204, "button"),
            ExpectedControl("Collections", "SettingsRootPage.kt", 212, "button"),
            ExpectedControl("Local Library", "SettingsRootPage.kt", 220, "button"),
            ExpectedControl("Games", "SettingsRootPage.kt", 228, "button"),
            ExpectedControl("Live TV", "SettingsRootPage.kt", 236, "button"),
            ExpectedControl("Auto Downloads", "SettingsRootPage.kt", 245, "button"),
            ExpectedControl("Continue Watching", "SettingsRootPage.kt", 254, "button"),
            ExpectedControl("Detail Page", "SettingsRootPage.kt", 262, "button"),
            ExpectedControl("Downloads", "SettingsRootPage.kt", 271, "button"),
            ExpectedControl("Home Layout", "SettingsRootPage.kt", 280, "button"),
            ExpectedControl("Integrations", "SettingsRootPage.kt", 288, "button"),
            ExpectedControl("Layout", "SettingsRootPage.kt", 296, "button"),
            ExpectedControl("Video & Audio", "SettingsRootPage.kt", 314, "button"),
            ExpectedControl("Player", "SettingsRootPage.kt", 322, "button"),
            ExpectedControl("Random Play", "SettingsRootPage.kt", 330, "button"),
            ExpectedControl("Discover", "SettingsRootPage.kt", 338, "button"),
            ExpectedControl("Plugins", "SettingsRootPage.kt", 347, "button"),
            ExpectedControl("Streams", "SettingsRootPage.kt", 356, "button"),
        ),
    ),
    PageExpectation(
        name = "Account",
        page = SettingsPage.Account,
        controls = listOf(
            ExpectedControl("Run setup wizard", "AccountSettingsPage.kt", 181, "button"),
            ExpectedControl("Account", "AccountSettingsPage.kt", 193, "section header"),
            ExpectedControl("Switch Profile", "AccountSettingsPage.kt", 216, "button"),
            ExpectedControl("Synchronization", "AccountSettingsPage.kt", 261, "section header"),
            ExpectedControl("Appearance", "AccountSettingsPage.kt", 267, "switch"),
            ExpectedControl("Home catalogs", "AccountSettingsPage.kt", 279, "switch"),
            ExpectedControl("Stream display", "AccountSettingsPage.kt", 295, "switch"),
            ExpectedControl("Debrid", "AccountSettingsPage.kt", 307, "switch"),
            ExpectedControl("Metadata services", "AccountSettingsPage.kt", 319, "switch"),
            ExpectedControl("Content preferences", "AccountSettingsPage.kt", 331, "switch"),
            ExpectedControl("Trakt preferences", "AccountSettingsPage.kt", 343, "switch"),
            ExpectedControl("Notifications", "AccountSettingsPage.kt", 355, "switch"),
            ExpectedControl("Settings backup", "AccountSettingsPage.kt", 376, "section header"),
            ExpectedControl("Back up settings", "AccountSettingsPage.kt", 382, "button"),
            ExpectedControl("Back up settings and credentials", "AccountSettingsPage.kt", 398, "button"),
            ExpectedControl("Import settings", "AccountSettingsPage.kt", 417, "button"),
            ExpectedControl("STARTUP", "AccountSettingsPage.kt", 436, "section header"),
            ExpectedControl("Remember Last Profile", "AccountSettingsPage.kt", 441, "switch"),
        ),
    ),
    PageExpectation(
        name = "Supporters & Contributors",
        page = SettingsPage.SupportersContributors,
        controls = listOf(
            ExpectedControl("Retry", "SupportersContributorsPage.kt", 793, "button"),
        ),
    ),
    PageExpectation(
        name = "Licenses & Attributions",
        page = SettingsPage.LicensesAttributions,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Watch Party",
        page = SettingsPage.WatchParty,
        controls = listOf(
            ExpectedControl("Party code", "PartyScreen.kt", 265, "text", skipped = true),
        ),
    ),
    PageExpectation(
        name = "Video & Audio",
        page = SettingsPage.Playback,
        controls = listOf(
            ExpectedControl("HDR Mode", "PlaybackSettingsPage.kt", 1105, "choice"),
            ExpectedControl("Steady frame pacing (VRR)", "PlaybackSettingsPage.kt", 1238, "switch"),
            ExpectedControl("Low VRAM Mode", "PlaybackSettingsPage.kt", 1248, "choice"),
            ExpectedControl("Playback Buffer", "PlaybackSettingsPage.kt", 1258, "choice"),
            ExpectedControl("Seek bar thumbnails", "PlaybackSettingsPage.kt", 1268, "choice"),
            ExpectedControl("Audio passthrough (bitstream)", "PlaybackSettingsPage.kt", 1278, "switch"),
            ExpectedControl("Audio channels", "PlaybackSettingsPage.kt", 1288, "choice"),
            ExpectedControl("Dialogue boost (downmix)", "PlaybackSettingsPage.kt", 1303, "choice"),
            ExpectedControl("Anime", "PlaybackSettingsPage.kt", 1323, "section header"),
            ExpectedControl("Shader Library", "PlaybackSettingsPage.kt", 1416, "button"),
            ExpectedControl("Advanced", "PlaybackSettingsPage.kt", 1440, "section header"),
            ExpectedControl("MPV configuration mode", "PlaybackSettingsPage.kt", 1447, "choice"),
            ExpectedControl("MPV Options", "PlaybackSettingsPage.kt", 1458, "button"),
            ExpectedControl("Verbose MPV Logging", "PlaybackSettingsPage.kt", 1480, "switch"),
            ExpectedControl("Experimental HDR (Wayland)", "PlatformHdrSettings.desktop.kt", 14, "switch"),
        ),
    ),
    PageExpectation(
        name = "Player",
        page = SettingsPage.Player,
        controls = listOf(
            ExpectedControl("Player UI Scale", "PlaybackSettingsPage.kt", 807, "slider"),
            ExpectedControl("Bottom Row Icon Scale", "PlaybackSettingsPage.kt", 819, "slider"),
            ExpectedControl("Loading Overlay", "PlaybackSettingsPage.kt", 832, "switch"),
            ExpectedControl("Player", "PlaybackSettingsPage.kt", 842, "choice"),
            ExpectedControl("External Player App", "PlaybackSettingsPage.kt", 856, "button", skipped = true),
            ExpectedControl("Forward Subtitles to External Player", "PlaybackSettingsPage.kt", 870, "switch", skipped = true),
            ExpectedControl("Default Speed", "PlaybackSettingsPage.kt", 881, "slider"),
            ExpectedControl("Seek Step", "PlaybackSettingsPage.kt", 893, "slider"),
            ExpectedControl("Fine Speed Increments", "PlaybackSettingsPage.kt", 907, "switch"),
            ExpectedControl("Speed Toggle Range", "PlaybackSettingsPage.kt", 915, "slider"),
            ExpectedControl("Show Controls on Mouse Move", "PlaybackSettingsPage.kt", 935, "switch"),
            ExpectedControl("Sources Edge Notch", "PlaybackSettingsPage.kt", 952, "choice"),
            ExpectedControl("Open Notches On Hover", "PlaybackSettingsPage.kt", 968, "switch"),
            ExpectedControl("In-Player Notifications", "PlaybackSettingsPage.kt", 988, "choice"),
            ExpectedControl("Player Controls Layout", "PlaybackSettingsPage.kt", 1021, "choice"),
            ExpectedControl("Minimal Controls Pills", "PlaybackSettingsPage.kt", 1033, "switch"),
            ExpectedControl("Seek Bar Handle", "PlaybackSettingsPage.kt", 1042, "switch"),
            ExpectedControl("Controls Vignette", "PlaybackSettingsPage.kt", 1050, "switch"),
            ExpectedControl("Always Show Playback Clock", "PlaybackSettingsPage.kt", 1069, "switch"),
            ExpectedControl("Player Volume Control", "PlaybackSettingsPage.kt", 1077, "switch"),
            ExpectedControl("Playback Info Panel", "PlaybackSettingsPage.kt", 1085, "switch"),
            ExpectedControl("SUBTITLE AND AUDIO", "PlaybackSettingsPage.kt", 1492, "section header"),
            ExpectedControl("Strip SDH annotations", "PlaybackSettingsPage.kt", 1608, "switch"),
            ExpectedControl("Automatic subtitle sync", "PlaybackSettingsPage.kt", 1617, "switch"),
            ExpectedControl("Prefer Addon Subtitles", "PlaybackSettingsPage.kt", 1654, "switch"),
            ExpectedControl("Reject Subtitle Tracks", "PlaybackSettingsPage.kt", 1669, "dropdown"),
            ExpectedControl("SUBTITLE RENDERING", "PlaybackSettingsPage.kt", 1722, "section header"),
            ExpectedControl("SKIP SEGMENTS", "PlaybackSettingsPage.kt", 2596, "section header"),
            ExpectedControl("Skip Intro", "PlaybackSettingsPage.kt", 2601, "switch"),
            ExpectedControl("Auto-accept skip prompt", "PlaybackSettingsPage.kt", 2609, "dropdown"),
            ExpectedControl("Anime Skip", "PlaybackSettingsPage.kt", 2637, "switch"),
            ExpectedControl("AnimeSkip Client ID", "PlaybackSettingsPage.kt", 2651, "button"),
            ExpectedControl("Enable Intro Submission", "PlaybackSettingsPage.kt", 2672, "switch"),
            ExpectedControl("SkipDB API Key", "PlaybackSettingsPage.kt", 2686, "button", skipped = true),
            ExpectedControl("IntroDB API Key", "PlaybackSettingsPage.kt", 2705, "button", skipped = true),
            ExpectedControl("NEXT EPISODE", "PlaybackSettingsPage.kt", 2729, "section header"),
            ExpectedControl("Binge Mode", "PlaybackSettingsPage.kt", 2734, "switch"),
            ExpectedControl("Preload Next Episode Sources", "PlaybackSettingsPage.kt", 2744, "switch", skipped = true),
            ExpectedControl("Prefer Binge Group (Next Episode)", "PlaybackSettingsPage.kt", 2754, "switch"),
            ExpectedControl("Reuse Binge Group", "PlaybackSettingsPage.kt", 2763, "switch"),
            ExpectedControl("Skip Filler Episodes", "PlaybackSettingsPage.kt", 2773, "switch"),
            ExpectedControl("Show Filler Episodes", "PlaybackSettingsPage.kt", 2782, "switch"),
            ExpectedControl("Next Episode Threshold Mode", "PlaybackSettingsPage.kt", 2791, "dropdown"),
        ),
    ),
    PageExpectation(
        name = "Random Play",
        page = SettingsPage.RandomPlay,
        controls = listOf(
            ExpectedControl("Show Random Play on Home", "RandomPlaySettingsPage.kt", 22, "switch"),
            ExpectedControl("CONTENT TYPES", "RandomPlaySettingsPage.kt", 62, "section header"),
            ExpectedControl("RATING FILTER", "RandomPlaySettingsPage.kt", 86, "section header"),
            ExpectedControl("ALLOWED GENRES", "RandomPlaySettingsPage.kt", 115, "section header"),
        ),
    ),
    PageExpectation(
        name = "Discover",
        page = SettingsPage.Discover,
        controls = listOf(
            ExpectedControl("Enable AI Rows", "DiscoverAiSettingsSection.kt", 478, "switch"),
            ExpectedControl("Season Recaps", "DiscoverAiSettingsSection.kt", 499, "switch"),
            ExpectedControl("API Key", "DiscoverAiSettingsSection.kt", 558, "text"),
            ExpectedControl("Base URL", "DiscoverAiSettingsSection.kt", 569, "text"),
            ExpectedControl("Model", "DiscoverAiSettingsSection.kt", 579, "text"),
            ExpectedControl("Your Rows", "DiscoverSettingsPage.kt", 174, "section header"),
            ExpectedControl("Row Actions", "DiscoverSettingsPage.kt", 193, "section header"),
            ExpectedControl("Row Settings", "DiscoverSettingsPage.kt", 265, "section header"),
            ExpectedControl("Because You Watched rows", "DiscoverSettingsPage.kt", 325, "slider"),
            ExpectedControl("Trending In rows", "DiscoverSettingsPage.kt", 341, "slider"),
            ExpectedControl("Hide Watched In Recommendations", "DiscoverSettingsPage.kt", 359, "switch"),
            ExpectedControl("Excluded Genres", "DiscoverSettingsPage.kt", 376, "section header"),
            ExpectedControl("Excluded Genres", "DiscoverSettingsPage.kt", 382, "switch"),
            ExpectedControl("Genres", "DiscoverSettingsPage.kt", 1265, "dropdown"),
            ExpectedControl("Import a list…", "DiscoverSettingsPage.kt", 1321, "button"),
            ExpectedControl("Custom row", "DiscoverSettingsPage.kt", 2097, "button"),
        ),
    ),
    PageExpectation(
        name = "Streams",
        page = SettingsPage.Streams,
        controls = listOf(
            ExpectedControl("P2P Streaming", "PlaybackSettingsPage.kt", 2009, "switch"),
            ExpectedControl("Hide torrent stats", "PlaybackSettingsPage.kt", 2023, "switch"),
            ExpectedControl("Upload to peers", "PlaybackSettingsPage.kt", 2059, "switch"),
            ExpectedControl("STREAM SELECTION", "PlaybackSettingsPage.kt", 2180, "section header"),
            ExpectedControl("Prepare Streams In Advance", "PlaybackSettingsPage.kt", 2185, "dropdown"),
            ExpectedControl("Prepared Results Expire After", "PlaybackSettingsPage.kt", 2208, "dropdown", skipped = true),
            ExpectedControl("Prepare Playback Links Too", "PlaybackSettingsPage.kt", 2238, "switch", skipped = true),
            ExpectedControl("Keep Continue Watching Sources", "PlaybackSettingsPage.kt", 2251, "switch"),
            ExpectedControl("Reuse Last Link", "PlaybackSettingsPage.kt", 2260, "switch"),
            ExpectedControl("Last Link Cache Duration", "PlaybackSettingsPage.kt", 2269, "dropdown", skipped = true),
            ExpectedControl("Automatic Stream Failover", "PlaybackSettingsPage.kt", 2281, "switch"),
            ExpectedControl("Failover Start Timeout", "PlaybackSettingsPage.kt", 2290, "choice", skipped = true),
            ExpectedControl("Rate Limit Recovery", "PlaybackSettingsPage.kt", 2311, "choice"),
            ExpectedControl("First Reconnect Wait", "PlaybackSettingsPage.kt", 2335, "dropdown"),
            ExpectedControl("Second Reconnect Wait", "PlaybackSettingsPage.kt", 2344, "dropdown"),
            ExpectedControl("Show Source When Paused", "PlaybackSettingsPage.kt", 2355, "switch"),
            ExpectedControl("STREAM AUTO-PLAY", "PlaybackSettingsPage.kt", 2368, "section header"),
            ExpectedControl("Auto Stream Selection", "PlaybackSettingsPage.kt", 2373, "choice"),
            ExpectedControl("Apply To Next Episode", "PlaybackSettingsPage.kt", 2385, "switch"),
            ExpectedControl("Regex Pattern", "PlaybackSettingsPage.kt", 2401, "button", skipped = true),
            ExpectedControl("Wait time for addons before selecting.", "PlaybackSettingsPage.kt", 2446, "slider"),
            ExpectedControl("Auto-play Source Scope", "PlaybackSettingsPage.kt", 2469, "choice"),
            ExpectedControl("Allowed Addons", "PlaybackSettingsPage.kt", 2485, "dropdown"),
            ExpectedControl("Allowed Plugins", "PlaybackSettingsPage.kt", 2506, "dropdown"),
            ExpectedControl("Stream scoring", "StreamsSettingsPage.kt", 104, "section header"),
            ExpectedControl("Stream scoring", "StreamsSettingsPage.kt", 109, "button"),
            ExpectedControl("Fusion Style", "StreamsSettingsPage.kt", 127, "section header"),
            ExpectedControl("Size badges", "StreamsSettingsPage.kt", 132, "switch"),
            ExpectedControl("Badge position", "StreamsSettingsPage.kt", 141, "choice"),
            ExpectedControl("Fusion badge URLs", "StreamsSettingsPage.kt", 153, "button"),
            ExpectedControl("Show in player", "StreamsSettingsPage.kt", 161, "switch"),
            ExpectedControl("Player badge pack", "StreamsSettingsPage.kt", 178, "choice"),
            ExpectedControl("Display", "StreamsSettingsPage.kt", 206, "section header"),
            ExpectedControl("Addon logo", "StreamsSettingsPage.kt", 211, "switch"),
        ),
    ),
    PageExpectation(
        name = "Local Library",
        page = SettingsPage.LocalLibrary,
        controls = listOf(
            ExpectedControl("Preferred play action", "SettingsLocalLibraryPage.kt", 275, "dropdown"),
            ExpectedControl("Folders", "SettingsLocalLibraryPage.kt", 314, "section header"),
            ExpectedControl("Catalogs", "SettingsLocalLibraryPage.kt", 505, "section header"),
            ExpectedControl("Add", "SettingsLocalLibraryPage.kt", 546, "text"),
            ExpectedControl("No catalogs yet. Create one, then assign titles to it below.", "SettingsLocalLibraryPage.kt", 633, "choice", skipped = true),
            ExpectedControl("Rename", "SettingsLocalLibraryPage.kt", 722, "text"),
            ExpectedControl("Cancel", "SettingsLocalLibraryPage.kt", 758, "choice", skipped = true),
            ExpectedControl("Follows the library setting: %1\$s", "SettingsLocalLibraryPage.kt", 1224, "choice", skipped = true),
        ),
    ),
    PageExpectation(
        name = "Games",
        page = SettingsPage.Games,
        controls = listOf(
            ExpectedControl("Show in navigation", "GamesSettingsPage.kt", 58, "switch"),
            ExpectedControl("Game library", "GamesSettingsPage.kt", 74, "section header"),
            ExpectedControl("Metadata", "GamesSettingsPage.kt", 93, "section header"),
            ExpectedControl("Metadata source", "GamesSettingsPage.kt", 105, "choice"),
            ExpectedControl("Twitch Client ID", "GamesSettingsPage.kt", 133, "text"),
            ExpectedControl("Twitch Client Secret", "GamesSettingsPage.kt", 145, "text"),
            ExpectedControl("Presentation", "GamesSettingsPage.kt", 166, "section header"),
            ExpectedControl("Backdrop", "GamesSettingsPage.kt", 174, "choice"),
            ExpectedControl("Launching", "GamesSettingsPage.kt", 196, "section header"),
            ExpectedControl("Close Nuvio after starting a game", "GamesSettingsPage.kt", 204, "switch"),
            ExpectedControl("Clear logos", "GamesSettingsPage.kt", 226, "section header"),
            ExpectedControl("SteamGridDB API key", "GamesSettingsPage.kt", 234, "text"),
        ),
    ),
    PageExpectation(
        name = "IPTV",
        page = SettingsPage.Iptv,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Auto Downloads",
        page = SettingsPage.AutoDownloads,
        controls = listOf(
            ExpectedControl("Automatic downloads", "LibraryDownloadsSettingsScreen.kt", 183, "switch"),
            ExpectedControl("Check interval", "LibraryDownloadsSettingsScreen.kt", 193, "choice"),
            ExpectedControl("Check when Nuvio starts", "LibraryDownloadsSettingsScreen.kt", 204, "switch"),
            ExpectedControl("Pause downloads while playing", "LibraryDownloadsSettingsScreen.kt", 214, "switch"),
            ExpectedControl("Take Whole Season Packs", "LibraryDownloadsSettingsScreen.kt", 227, "switch"),
            ExpectedControl("Post-release delay", "LibraryDownloadsSettingsScreen.kt", 237, "choice"),
            ExpectedControl("Simultaneous downloads", "LibraryDownloadsSettingsScreen.kt", 259, "choice"),
            ExpectedControl("Download bandwidth", "LibraryDownloadsSettingsScreen.kt", 270, "choice"),
            ExpectedControl("Maximum episode size", "LibraryDownloadsSettingsScreen.kt", 294, "choice"),
            ExpectedControl("Maximum movie size", "LibraryDownloadsSettingsScreen.kt", 306, "choice"),
            ExpectedControl("Monitored", "LibraryDownloadsSettingsScreen.kt", 338, "section header"),
            ExpectedControl("Activity", "LibraryDownloadsSettingsScreen.kt", 391, "section header"),
            ExpectedControl("Clear activity history", "LibraryDownloadsSettingsScreen.kt", 401, "button", skipped = true),
        ),
    ),
    PageExpectation(
        name = "Stream Scoring",
        page = SettingsPage.StreamScoring,
        controls = listOf(
            ExpectedControl("Stream scoring", "StreamScoringSettingsPage.kt", 158, "switch"),
            ExpectedControl("Apply scoring to", "StreamScoringSettingsPage.kt", 174, "section header"),
            ExpectedControl("Add an HTPC source tab", "StreamScoringSettingsPage.kt", 227, "switch"),
            ExpectedControl("Setup", "StreamScoringSettingsPage.kt", 261, "section header"),
            ExpectedControl("Reset to preset", "StreamScoringSettingsPage.kt", 347, "button", skipped = true),
            ExpectedControl("Minimum score", "StreamScoringSettingsPage.kt", 447, "section header"),
            ExpectedControl("Preview", "StreamScoringSettingsPage.kt", 563, "section header"),
            ExpectedControl("Preferred size band", "StreamScoringSettingsPage.kt", 776, "section header"),
        ),
    ),
    PageExpectation(
        name = "Keyboard Shortcuts",
        page = SettingsPage.KeyboardShortcuts,
        controls = listOf(
            ExpectedControl("Move focus", "KeyboardShortcutsPage.kt", 185, "choice"),
            ExpectedControl("Hold select for actions", "KeyboardShortcutsPage.kt", 200, "switch"),
            ExpectedControl("Hold select for actions", "KeyboardShortcutsPage.kt", 231, "section header"),
            ExpectedControl("Escape goes windowed first", "KeyboardShortcutsPage.kt", 240, "switch"),
            ExpectedControl("Escape goes windowed first", "KeyboardShortcutsPage.kt", 286, "choice"),
            ExpectedControl("On-screen keyboard", "KeyboardShortcutsPage.kt", 361, "section header"),
            ExpectedControl("Show for mouse", "KeyboardShortcutsPage.kt", 363, "switch"),
            ExpectedControl("Controller", "KeyboardShortcutsPage.kt", 373, "section header"),
            ExpectedControl("Gamepad control", "KeyboardShortcutsPage.kt", 379, "switch"),
            ExpectedControl("Controller · Buttons", "KeyboardShortcutsPage.kt", 415, "section header"),
        ),
    ),
    PageExpectation(
        name = "Appearance",
        page = SettingsPage.Appearance,
        controls = listOf(
            ExpectedControl("App UI Scale", "AppearanceSettingsPage.kt", 243, "slider"),
            ExpectedControl("Also Apply to Details Screen", "AppearanceSettingsPage.kt", 254, "switch"),
            ExpectedControl("App Font", "AppearanceSettingsPage.kt", 265, "button"),
            ExpectedControl("App Font Size", "AppearanceSettingsPage.kt", 277, "slider", skipped = true),
            ExpectedControl("Start in windowed mode", "AppearanceSettingsPage.kt", 292, "switch"),
            ExpectedControl("Close to notification area", "AppearanceSettingsPage.kt", 302, "switch", skipped = true),
            ExpectedControl("AMOLED Black", "AppearanceSettingsPage.kt", 313, "switch"),
            ExpectedControl("Liquid Glass", "AppearanceSettingsPage.kt", 323, "switch", skipped = true),
            ExpectedControl("Desktop Navigation", "AppearanceSettingsPage.kt", 334, "choice"),
            ExpectedControl("Discover tab", "AppearanceSettingsPage.kt", 354, "switch"),
            ExpectedControl("Always Show Top Bar", "AppearanceSettingsPage.kt", 367, "switch"),
            ExpectedControl("App Language", "AppearanceSettingsPage.kt", 381, "choice"),
            ExpectedControl("Poster Card Style", "AppearanceSettingsPage.kt", 420, "section header"),
            ExpectedControl("Card Depth", "AppearanceSettingsPage.kt", 457, "section header"),
            ExpectedControl("Card Depth", "AppearanceSettingsPage.kt", 529, "text"),
            ExpectedControl("Gradient direction", "AppearanceSettingsPage.kt", 709, "choice", skipped = true),
            ExpectedControl("Card surface", "AppearanceSettingsPage.kt", 754, "text", skipped = true),
            ExpectedControl("Card surface", "AppearanceSettingsPage.kt", 789, "choice", skipped = true),
            ExpectedControl("Navigation order", "NavigationOrderDialog.kt", 192, "button"),
            ExpectedControl("Width", "PosterCustomizationSettingsPage.kt", 121, "choice"),
            ExpectedControl("Corner Radius", "PosterCustomizationSettingsPage.kt", 133, "choice"),
            ExpectedControl("Focused Poster Highlight", "PosterCustomizationSettingsPage.kt", 143, "choice"),
            ExpectedControl("Landscape Posters", "PosterCustomizationSettingsPage.kt", 153, "switch"),
            ExpectedControl("Hide labels", "PosterCustomizationSettingsPage.kt", 212, "switch"),
            ExpectedControl("Poster action preview", "PosterCustomizationSettingsPage.kt", 221, "switch"),
            ExpectedControl("Watchlist badge", "PosterCustomizationSettingsPage.kt", 229, "switch"),
            ExpectedControl("Card depth", "PosterCustomizationSettingsPage.kt", 286, "switch"),
            ExpectedControl("Edge", "PosterCustomizationSettingsPage.kt", 295, "slider"),
            ExpectedControl("Sheen", "PosterCustomizationSettingsPage.kt", 306, "slider"),
            ExpectedControl("Edge coverage", "PosterCustomizationSettingsPage.kt", 317, "slider"),
            ExpectedControl("Apply to %1\$s", "PosterCustomizationSettingsPage.kt", 335, "switch"),
            ExpectedControl("Detail Page", "MetaScreenSettingsPage.kt", 155, "section header"),
            ExpectedControl("Details background", "MetaScreenSettingsPage.kt", 160, "choice"),
            ExpectedControl("Hero Trailer Playback", "MetaScreenSettingsPage.kt", 183, "choice"),
            ExpectedControl("Wait before playing", "MetaScreenSettingsPage.kt", 217, "choice", skipped = true),
            ExpectedControl("Play with sound", "MetaScreenSettingsPage.kt", 227, "switch", skipped = true),
            ExpectedControl("Trailer background", "MetaScreenSettingsPage.kt", 236, "choice", skipped = true),
            ExpectedControl("Discovery Badges", "MetaScreenSettingsPage.kt", 262, "switch"),
            ExpectedControl("Blur Unwatched Episodes", "MetaScreenSettingsPage.kt", 271, "switch"),
            ExpectedControl("IMDb episode ratings", "MetaScreenSettingsPage.kt", 280, "choice"),
            ExpectedControl("Overall rating", "MetaScreenSettingsPage.kt", 303, "switch"),
            ExpectedControl("Overall rating", "MetaScreenSettingsPage.kt", 312, "choice"),
            ExpectedControl("Cast Episode Counts", "MetaScreenSettingsPage.kt", 326, "choice"),
            ExpectedControl("Cast Episode Counts", "MetaScreenSettingsPage.kt", 388, "choice", skipped = true),
        ),
    ),
    PageExpectation(
        name = "Screensaver",
        page = SettingsPage.Screensaver,
        controls = listOf(
            ExpectedControl("Enable screensaver", "ScreensaverSettingsPage.kt", 61, "switch"),
            ExpectedControl("Active during playback", "ScreensaverSettingsPage.kt", 92, "switch"),
        ),
    ),
    PageExpectation(
        name = "Voice Control",
        page = SettingsPage.VoiceControl,
        controls = listOf(
            ExpectedControl("+ Add a phrase", "VoiceCommandsDialog.kt", 160, "text", skipped = true),
            ExpectedControl("Voice commands", "VoiceControlSettingsPage.kt", 53, "section header"),
            ExpectedControl("Voice commands", "VoiceControlSettingsPage.kt", 58, "switch"),
            ExpectedControl("Push to talk", "VoiceControlSettingsPage.kt", 71, "button", skipped = true),
            ExpectedControl("Commands", "VoiceControlSettingsPage.kt", 102, "button"),
            ExpectedControl("Edit commands", "VoiceControlSettingsPage.kt", 112, "button"),
            ExpectedControl("End of command", "VoiceControlSettingsPage.kt", 141, "section header", skipped = true),
            ExpectedControl("Models folder", "VoiceControlSettingsPage.kt", 163, "button"),
            ExpectedControl("Open folder", "VoiceControlSettingsPage.kt", 168, "button"),
            ExpectedControl("Open folder", "VoiceControlSettingsPage.kt", 176, "section header"),
            ExpectedControl("Microphone", "VoiceControlSettingsPage.kt", 184, "dropdown"),
            ExpectedControl("\${model.label} · \${megabytes(model.sizeBytes)} MB", "VoiceControlSettingsPage.kt", 212, "button", skipped = true),
        ),
    ),
    PageExpectation(
        name = "Preshow",
        page = SettingsPage.Preshow,
        controls = listOf(
            ExpectedControl("What plays, and in what order", "PreshowSettingsPage.kt", 96, "button"),
            ExpectedControl("Keep audio output awake", "PreshowSettingsPage.kt", 115, "switch", skipped = true),
            ExpectedControl("Trailers", "PreshowSettingsPage.kt", 154, "section header"),
            ExpectedControl("Number of trailers", "PreshowSettingsPage.kt", 184, "section header", skipped = true),
            ExpectedControl("Rating system", "PreshowSettingsPage.kt", 187, "choice"),
            ExpectedControl("Rating system", "PreshowSettingsPage.kt", 203, "section header"),
            ExpectedControl("Previously on cards", "PreshowSettingsPage.kt", 210, "switch"),
            ExpectedControl("Only after a break", "PreshowSettingsPage.kt", 226, "switch", skipped = true),
            ExpectedControl("Break length", "PreshowSettingsPage.kt", 235, "choice", skipped = true),
            ExpectedControl("Break length", "PreshowSettingsPage.kt", 251, "section header", skipped = true),
            ExpectedControl("Post-show cards", "PreshowSettingsPage.kt", 260, "switch"),
            ExpectedControl("Theme songs", "PreshowSettingsPage.kt", 280, "section header"),
            ExpectedControl("Theme song during trivia", "PreshowSettingsPage.kt", 288, "switch"),
            ExpectedControl("Theme song on details screen", "PreshowSettingsPage.kt", 296, "switch"),
            ExpectedControl("Theme song volume", "PreshowSettingsPage.kt", 304, "choice"),
            ExpectedControl("Theme song volume", "PreshowSettingsPage.kt", 317, "section header"),
            ExpectedControl("Local folder", "PreshowSettingsPage.kt", 321, "text"),
            ExpectedControl("Local folder", "PreshowSettingsPage.kt", 341, "text"),
        ),
    ),
    PageExpectation(
        name = "Music",
        page = SettingsPage.Music,
        controls = listOf(
            ExpectedControl("Show Music", "MusicSettingsPage.kt", 92, "switch"),
            ExpectedControl("Reduce motion", "MusicSettingsPage.kt", 104, "switch"),
            ExpectedControl("Artwork and details", "MusicSettingsPage.kt", 116, "section header"),
            ExpectedControl("Fanart.tv API key", "MusicSettingsPage.kt", 132, "text"),
            ExpectedControl("TheAudioDB API key", "MusicSettingsPage.kt", 143, "text"),
            ExpectedControl("Last.fm API key", "MusicSettingsPage.kt", 154, "text"),
            ExpectedControl("Music addon link (Nebula)", "MusicSettingsPage.kt", 165, "text"),
            ExpectedControl("Second music addon link", "MusicSettingsPage.kt", 176, "text"),
            ExpectedControl("Third music addon link", "MusicSettingsPage.kt", 187, "text"),
            ExpectedControl("Prefer the addon when it has the album", "MusicSettingsPage.kt", 199, "switch", skipped = true),
            ExpectedControl("Build \"Where next?\" automatically", "MusicSettingsPage.kt", 208, "switch"),
            ExpectedControl("Word-by-word lyrics", "MusicSettingsPage.kt", 222, "section header"),
            ExpectedControl("Unison", "MusicSettingsPage.kt", 237, "switch"),
            ExpectedControl("AMLL TTML DB", "MusicSettingsPage.kt", 245, "switch"),
            ExpectedControl("Netease Cloud Music", "MusicSettingsPage.kt", 253, "switch"),
            ExpectedControl("LRCMux", "MusicSettingsPage.kt", 261, "switch"),
            ExpectedControl("LyricsPlus", "MusicSettingsPage.kt", 269, "switch"),
            ExpectedControl("Playback", "MusicSettingsPage.kt", 281, "section header"),
            ExpectedControl("Keep audio output awake for music", "MusicSettingsPage.kt", 290, "switch", skipped = true),
            ExpectedControl("Skip non-music parts", "MusicSettingsPage.kt", 303, "switch"),
            ExpectedControl("Download", "MusicSettingsPage.kt", 348, "button"),
        ),
    ),
    PageExpectation(
        name = "Advanced",
        page = SettingsPage.Advanced,
        controls = listOf(
            ExpectedControl("Performance logging", "AdvancedSettingsPage.kt", 88, "switch"),
            ExpectedControl("STARTUP", "AdvancedSettingsPage.kt", 105, "section header"),
            ExpectedControl("Remember Last Profile", "AdvancedSettingsPage.kt", 110, "switch"),
            ExpectedControl("CACHE", "AdvancedSettingsPage.kt", 122, "section header"),
            ExpectedControl("Clear Continue Watching Cache", "AdvancedSettingsPage.kt", 133, "button"),
            ExpectedControl("Image Cache Size", "AdvancedSettingsPage.kt", 177, "dropdown"),
            ExpectedControl("Metadata Cache", "AdvancedSettingsPage.kt", 191, "dropdown"),
            ExpectedControl("Memory Use", "AdvancedSettingsPage.kt", 208, "dropdown"),
            ExpectedControl("Home Catalog Cache", "AdvancedSettingsPage.kt", 230, "dropdown"),
            ExpectedControl("Prewarm Artwork", "AdvancedSettingsPage.kt", 269, "switch"),
            ExpectedControl("Prewarm Posters", "AdvancedSettingsPage.kt", 279, "switch"),
            ExpectedControl("Prewarm Logos", "AdvancedSettingsPage.kt", 295, "switch"),
            ExpectedControl("Prewarm Backdrops", "AdvancedSettingsPage.kt", 311, "switch"),
            ExpectedControl("Prewarm Inside Collections", "AdvancedSettingsPage.kt", 335, "switch", skipped = true),
        ),
    ),
    PageExpectation(
        name = "Content Discovery",
        page = SettingsPage.ContentDiscovery,
        controls = listOf(
            ExpectedControl("Addons", "ContentDiscoverySettingsPage.kt", 45, "button"),
            ExpectedControl("Plugins", "ContentDiscoverySettingsPage.kt", 53, "button"),
            ExpectedControl("Downloads", "ContentDiscoverySettingsPage.kt", 62, "button"),
            ExpectedControl("HOME", "ContentDiscoverySettingsPage.kt", 74, "section header"),
            ExpectedControl("Home Layout", "ContentDiscoverySettingsPage.kt", 79, "button"),
            ExpectedControl("Detail Page", "ContentDiscoverySettingsPage.kt", 86, "button"),
            ExpectedControl("Collections", "ContentDiscoverySettingsPage.kt", 93, "button"),
        ),
    ),
    PageExpectation(
        name = "Addons",
        page = SettingsPage.Addons,
        controls = listOf(
            ExpectedControl("Add Addon", "AddonsScreen.kt", 490, "button"),
            ExpectedControl("Search addons", "AddonsScreen.kt", 501, "text"),
            ExpectedControl("Order", "AddonsScreen.kt", 515, "dropdown"),
            ExpectedControl("Active filter", "AddonsScreen.kt", 562, "choice"),
            ExpectedControl("Catalogs filter", "AddonsScreen.kt", 563, "choice"),
            ExpectedControl("Configurable filter", "AddonsScreen.kt", 564, "choice"),
            ExpectedControl("Enable plugins globally", "PluginsSettingsScreen.kt", 283, "switch"),
            ExpectedControl("Group streams by repository", "PluginsSettingsScreen.kt", 312, "switch"),
            ExpectedControl("Skip duplicate scrapers", "PluginsSettingsScreen.kt", 341, "switch"),
            ExpectedControl("Repository URL", "PluginsSettingsScreen.kt", 350, "text"),
        ),
    ),
    PageExpectation(
        name = "Homescreen",
        page = SettingsPage.Homescreen,
        controls = listOf(
            ExpectedControl("Show Continue Watching", "ContinueWatchingSettingsPage.kt", 173, "switch"),
            ExpectedControl("Separate Next Up Row", "ContinueWatchingSettingsPage.kt", 204, "switch"),
            ExpectedControl("Separate Upcoming Row", "ContinueWatchingSettingsPage.kt", 213, "switch"),
            ExpectedControl("Up Next From Furthest Episode", "ContinueWatchingSettingsPage.kt", 222, "switch"),
            ExpectedControl("Selecting an item", "ContinueWatchingSettingsPage.kt", 231, "choice"),
            ExpectedControl("Source shortcut", "ContinueWatchingSettingsPage.kt", 253, "choice"),
            ExpectedControl("Resume prompt on launch", "ContinueWatchingSettingsPage.kt", 278, "switch"),
            ExpectedControl("Show Unaired Next Up Episodes", "ContinueWatchingSettingsPage.kt", 287, "switch"),
            ExpectedControl("Relative Air Countdown", "ContinueWatchingSettingsPage.kt", 296, "switch"),
            ExpectedControl("Prefer Episode Thumbnails in Continue Watching", "ContinueWatchingSettingsPage.kt", 305, "switch"),
            ExpectedControl("Blur Unwatched in Continue Watching", "ContinueWatchingSettingsPage.kt", 315, "switch"),
            ExpectedControl("Sort Order", "ContinueWatchingSettingsPage.kt", 325, "choice"),
            ExpectedControl("Clear Continue Watching Cache", "ContinueWatchingSettingsPage.kt", 352, "button"),
            ExpectedControl("Clear Continue Watching Cache", "ContinueWatchingSettingsPage.kt", 428, "choice"),
            ExpectedControl("Continue Watching source", "ContinueWatchingSettingsPage.kt", 632, "choice"),
            ExpectedControl("Continue Watching window", "ContinueWatchingSettingsPage.kt", 674, "choice", skipped = true),
            ExpectedControl("Remove imported watched history", "ContinueWatchingSettingsPage.kt", 745, "button", skipped = true),
            ExpectedControl("Withdraw imported history", "ContinueWatchingSettingsPage.kt", 769, "button", skipped = true),
            ExpectedControl("Cancel", "ContinueWatchingSettingsPage.kt", 785, "button", skipped = true),
            ExpectedControl("Seed Up Next from Nuvio Sync", "ContinueWatchingSettingsPage.kt", 806, "switch", skipped = true),
            ExpectedControl("Search", "ConfidentSearchSettingsSection.kt", 24, "section header"),
            ExpectedControl("Open confident matches directly", "ConfidentSearchSettingsSection.kt", 29, "switch"),
            ExpectedControl("Display Mode", "HomescreenSettingsPage.kt", 152, "section header"),
            ExpectedControl("Backdrop crossfade", "HomescreenSettingsPage.kt", 220, "slider"),
            ExpectedControl("Row change", "HomescreenSettingsPage.kt", 242, "choice", skipped = true),
            ExpectedControl("Full backdrop", "HomescreenSettingsPage.kt", 256, "switch", skipped = true),
            ExpectedControl("Ambient background", "HomescreenSettingsPage.kt", 269, "switch", skipped = true),
            ExpectedControl("Row jump dots", "HomescreenSettingsPage.kt", 282, "switch", skipped = true),
            ExpectedControl("Smooth scrolling", "HomescreenSettingsPage.kt", 311, "switch"),
            ExpectedControl("Hover preview", "HomescreenSettingsPage.kt", 326, "switch"),
            ExpectedControl("Use See more arrows", "HomescreenSettingsPage.kt", 340, "switch"),
            ExpectedControl("Number catalog rows", "HomescreenSettingsPage.kt", 351, "switch"),
            ExpectedControl("Animate collection GIFs on focus only", "HomescreenSettingsPage.kt", 362, "switch"),
            ExpectedControl("Show catalog provider", "HomescreenSettingsPage.kt", 373, "switch"),
            ExpectedControl("Home Layout", "HomescreenSettingsPage.kt", 465, "section header"),
            ExpectedControl("Show Hero Section", "HomescreenSettingsPage.kt", 470, "switch"),
            ExpectedControl("Hide Unreleased Content", "HomescreenSettingsPage.kt", 537, "switch"),
            ExpectedControl("Hide Watched Content", "HomescreenSettingsPage.kt", 546, "switch"),
            ExpectedControl("Hide Catalog Underline", "HomescreenSettingsPage.kt", 556, "switch"),
            ExpectedControl("Shuffle Rows On Title Click", "HomescreenSettingsPage.kt", 566, "switch"),
            ExpectedControl("Hero Catalogs", "HomescreenSettingsPage.kt", 586, "section header", skipped = true),
            ExpectedControl("No home catalogs", "HomescreenSettingsPage.kt", 615, "section header"),
        ),
    ),
    PageExpectation(
        name = "Collections",
        page = SettingsPage.Collections,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Integrations",
        page = SettingsPage.Integrations,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "TMDB Enrichment",
        page = SettingsPage.TmdbEnrichment,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "MDBList Ratings",
        page = SettingsPage.MdbListRatings,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "QualiCache",
        page = SettingsPage.QualiCache,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Poster Service",
        page = SettingsPage.PosterService,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Debrid",
        page = SettingsPage.Debrid,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Trakt",
        page = SettingsPage.TraktAuthentication,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Simkl",
        page = SettingsPage.SimklAuthentication,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Yamtrack",
        page = SettingsPage.YamtrackAuthentication,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "YouTube Account",
        page = SettingsPage.YouTubeAccount,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Seekr",
        page = SettingsPage.Seekr,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Lights",
        page = SettingsPage.Lights,
        controls = emptyList(),
    ),
    PageExpectation(
        name = "Discord Presence",
        page = SettingsPage.DiscordPresence,
        controls = emptyList(),
    ),
)
