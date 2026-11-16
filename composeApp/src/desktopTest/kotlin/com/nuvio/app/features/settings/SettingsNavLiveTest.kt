package com.nuvio.app.features.settings

import androidx.compose.ui.awt.ComposeWindow
import com.nuvio.app.App
import com.nuvio.app.LiveDisplayTests
import com.nuvio.app.core.auth.AuthStorage
import com.nuvio.app.core.perf.PerfTelemetry
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.library.LibraryQuickAccess
import com.nuvio.app.features.library.LibraryQuickAccessTarget
import com.nuvio.app.features.navbar.NavBarEntry
import com.nuvio.app.features.navbar.NavBarNavigation
import com.nuvio.app.features.profiles.ProfileStorage
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue
import java.awt.EventQueue
import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.KeyEvent
import java.io.Closeable
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Back and arrow focus on the real Settings screen, driven by synthetic AWT key events.
 *
 * [SettingsNavMenuLiveTest] proves the same route for one row inside a small test composition. This
 * one composes the actual app: the gate, the tab host, the Settings screen and its category
 * sidebar. The point is not that the rules are right — the offscreen suites cover those — but that a
 * real keypress reaches the screen that owns focus and that the app-level back ladder then acts on
 * it.
 *
 * The selected tab is observed through `(PerfScreen)` telemetry, the only desktop-side record of it
 * (`NativeTabBridge.publishSelectedTab` is a no-op here). The test starts telemetry itself, so the
 * app's own logging switches are untouched.
 *
 * `Main.kt`'s window-level key handler is not part of this composition. The back ladder is still
 * reached for both keys asserted here, because the Settings screen turns Back into a gesture-bridge
 * request itself; a key the window handler owns (Backspace away from Settings) is out of scope.
 *
 * Focus is global, so while this runs keystrokes meant for another application land in its window.
 * It is therefore **opt-in**, like the other live tests:
 *
 *     NUVIO_RUN_LIVE_DISPLAY_TESTS=1 ./gradlew :composeApp:desktopTest -Pnuvio.focusTests \
 *         --tests "*SettingsNavLiveTest*"
 *
 * Skipped rather than failed where the app cannot be brought up or the window cannot take focus,
 * because a skip is honest and a green fake is not.
 */
class SettingsNavLiveTest {

    @Test
    fun escapeWalksTheSettingsFocusLadderBeforeItLeavesTheTab() {
        requireLive()
        installSignedInFixture()

        LiveAppWindow().use { win ->
            if (!win.open()) return skipNoWindow()
            if (!win.awaitMain()) return skipUnreachable(win.screen())

            assumeTrue("The Library tab never opened", win.openLibrary())
            assumeTrue("The Settings screen never opened", win.openSettings())
            val enteredFrom = win.screen()

            // Back inside Settings walks focus outward — page to category, category to the nav bar —
            // and only the bar leaves for the tab Settings was opened from. The first press is always
            // consumed by Settings, whatever holds the keys.
            win.press(KeyEvent.VK_ESCAPE)
            Thread.sleep(350)
            assertTrue(
                win.screen() == enteredFrom,
                "the first Escape left Settings instead of walking the focus ladder " +
                    "(was on '$enteredFrom', now on '${win.screen()}')",
            )

            var left = false
            for (attempt in 1..4) {
                win.press(KeyEvent.VK_ESCAPE)
                Thread.sleep(350)
                if (!win.screen().startsWith("Settings")) {
                    left = true
                    break
                }
            }
            assertTrue(
                left,
                "Escape never left Settings for the tab it was opened from (still on '${win.screen()}')",
            )
        }
    }

    @Test
    fun downSailsThroughTheSidebarAndBackReachesTheBar() {
        requireLive()
        installSignedInFixture()

        LiveAppWindow().use { win ->
            if (!win.open()) return skipNoWindow()
            if (!win.awaitMain()) return skipUnreachable(win.screen())

            assumeTrue("The Settings screen never opened", win.openSettings())
            val settingsScreen = win.screen()

            // Down moves through the category list and never opens a page on its own. Repeating it
            // also covers the frames before the page's root asks for the keys.
            repeat(3) {
                win.press(KeyEvent.VK_DOWN)
                Thread.sleep(250)
            }
            assertTrue(
                win.screen() == settingsScreen,
                "moving through the categories opened a page or left Settings (now on '${win.screen()}')",
            )

            // Back from the categories goes to the nav bar, then from the bar out of Settings.
            var left = false
            for (attempt in 1..6) {
                win.press(KeyEvent.VK_ESCAPE)
                Thread.sleep(350)
                if (!win.screen().startsWith("Settings")) {
                    left = true
                    break
                }
            }
            assertTrue(left, "Back from the category list never reached the bar and left Settings")
        }
    }

    // ---------------------------------------------------------------------------------------

    /**
     * A signed-in anonymous account with one unpinned profile, and the startup overlays that would
     * otherwise own the keys dismissed. Written before the app composes: `AuthRepository` reads the
     * account at its first `initialize()`, and the overlays decide at their first evaluation.
     */
    private fun installSignedInFixture() {
        AuthStorage.saveAnonymousUserId(FIXTURE_USER)
        ProfileStorage.savePayload(
            buildJsonObject {
                put("userId", FIXTURE_USER)
                put("activeProfileIndex", 1)
                put("hasEverSelectedProfile", true)
                put("rememberLastProfileEnabled", false)
                put(
                    "profiles",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("user_id", FIXTURE_USER)
                                put("profile_index", 1)
                                put("name", "Fixture")
                            },
                        )
                    },
                )
            }.toString(),
        )
        // The store names and keys of FirstRunWizardStorage and ApiKeysOnboardingStorage, both
        // private to their desktop implementations. Either overlay takes focus and would answer the
        // assertions itself.
        DesktopStorage.store("nuvio_first_run_wizard").putBoolean("eligible", false)
        DesktopStorage.store("nuvio_api_keys_onboarding").putBoolean("dismissed_permanently", true)
        // The tab is read off `(PerfScreen)`; nothing else on desktop records it.
        PerfTelemetry.start("SettingsNavLiveTest")
    }

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

    private fun skipUnreachable(screen: String) {
        println("SKIPPED: the app never reached its main screen in the harness (screen='$screen')")
    }

    /** The app in a real window, keys posted through the AWT system queue. */
    private class LiveAppWindow : Closeable {
        private lateinit var window: ComposeWindow

        fun screen(): String = PerfTelemetry.currentScreen

        /** Shows the app and waits for the window to take the keys; false where it cannot. */
        fun open(): Boolean {
            onEdt {
                // Sized in dp, not pixels. The Settings layout switches to the category sidebar at
                // 768dp, and Compose's density follows the screen's scale, so a fixed pixel size
                // would land in the phone layout on a scaled desktop.
                val scale = uiScale()
                window = ComposeWindow().apply {
                    setSize(
                        (WINDOW_WIDTH_DP * scale).toInt(),
                        (WINDOW_HEIGHT_DP * scale).toInt(),
                    )
                    setContent { App() }
                    isVisible = true
                    toFront()
                    requestFocus()
                }
            }
            if (!awaitTrue(10_000) { window.isFocused }) return false
            return awaitFocusOwner() != null
        }

        /** Waits for the gate to open on Home. */
        fun awaitMain(): Boolean = awaitTrue(MAIN_SCREEN_TIMEOUT_MS) { screen() == "Home" }

        fun awaitScreen(name: String): Boolean = awaitTrue { screen() == name }

        /**
         * Switches to the Library tab. Retried: the request is a non-replaying emission, and one
         * sent before the tab host has started collecting is simply gone.
         */
        fun openLibrary(): Boolean = retryUntil {
            NavBarNavigation.request(NavBarEntry.Library)
            awaitTrue(1_500) { screen() == "Library" }
        }

        /**
         * Opens Settings through the Library's own shortcut (right-click > Local Library settings),
         * the one way into the tab that needs no click on the bar. Retried for the same reason as
         * [openLibrary]; it waits for the requested page, not just the tab, so the page the
         * sidebar starts on is known.
         */
        fun openSettings(): Boolean = retryUntil {
            LibraryQuickAccess.open(LibraryQuickAccessTarget.LocalLibrarySettings)
            awaitTrue(1_500) { screen() == "Settings/LocalLibrary" }
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

        private fun retryUntil(attempt: () -> Boolean): Boolean {
            for (attemptNumber in 1..20) {
                if (attempt()) return true
            }
            return false
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

    private companion object {
        private const val FIXTURE_USER = "settings-nav-live-fixture"

        // Wide enough for the 768dp tablet breakpoint that draws the category sidebar, and tall
        // enough for the whole list: LocalLibrary, the page the shortcut opens, is row 19 of 22.
        private const val WINDOW_WIDTH_DP = 1200
        private const val WINDOW_HEIGHT_DP = 1600
        private const val MAIN_SCREEN_TIMEOUT_MS = 90_000L

        /** The screen's scale factor, which is the density Compose lays the window out at. */
        fun uiScale(): Float {
            val scale = runCatching {
                GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
            }.getOrDefault(1.0)
            return if (scale > 0.0) scale.toFloat() else 1f
        }

        fun awaitTrue(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
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
