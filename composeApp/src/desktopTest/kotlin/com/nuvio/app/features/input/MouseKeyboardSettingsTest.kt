package com.nuvio.app.features.input

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real settings/storage in isolated child JVMs; no controller, windows or native loading. */
class MouseKeyboardSettingsTest {
    @Test fun freshInstallKeepsMouseKeyboardDisabledWithoutEnablingController() = withHeadlessFixture {
        runHeadlessProbe(it, MouseKeyboardSettingsProbe::class.java, "defaults")
    }

    @Test fun mouseSettingSurvivesFreshJvmAndDisablingRemovesOnlyItsOwnKey() = withHeadlessFixture {
        runHeadlessProbe(it, MouseKeyboardSettingsProbe::class.java, "write")
        runHeadlessProbe(it, MouseKeyboardSettingsProbe::class.java, "read-and-disable")
        runHeadlessProbe(it, MouseKeyboardSettingsProbe::class.java, "read-disabled")
    }

    @Test fun controllerResetPreservesIndependentMouseKeyboardSetting() = withHeadlessFixture {
        runHeadlessProbe(it, MouseKeyboardSettingsProbe::class.java, "reset-controller")
        runHeadlessProbe(it, MouseKeyboardSettingsProbe::class.java, "read-after-reset")
    }
}

internal object MouseKeyboardSettingsProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0]).toAbsolutePath()
        assertTrue(DesktopStorage.rootDir.startsWith(root))
        assertTrue(DesktopStorage.accountDir.startsWith(root))
        ensureGamepadSettingsLoaded()
        val store = DesktopStorage.store("nuvio_gamepad")
        val mouseKey = ProfileScopedKey.of("osk_mouse_enabled")
        val otherProfileKey = ProfileScopedKey.of("osk_mouse_enabled", 2)
        when (args[1]) {
            "defaults" -> {
                assertFalse(mouseKeyboardState().value)
                assertFalse(gamepadEnabledState().value)
                assertFalse(gamepadConnectedState().value)
                assertNull(store.getBoolean(mouseKey))
            }
            "write" -> {
                store.putBoolean(otherProfileKey, true)
                setMouseKeyboard(true)
                assertTrue(mouseKeyboardState().value)
                assertEquals(true, store.getBoolean(mouseKey))
                assertFalse(gamepadEnabledState().value)
                assertFalse(gamepadConnectedState().value)
                assertEquals(GamepadSettingsRepository.DEFAULT_DEAD_ZONE, gamepadDeadZoneState().value)
                assertEquals(GamepadSettingsRepository.DEFAULT_REPEAT_INTERVAL_MS, gamepadRepeatIntervalState().value)
            }
            "read-and-disable" -> {
                assertTrue(mouseKeyboardState().value)
                setMouseKeyboard(false)
                assertFalse(mouseKeyboardState().value)
                assertNull(store.getBoolean(mouseKey))
                assertEquals(true, store.getBoolean(otherProfileKey))
            }
            "read-disabled" -> {
                assertFalse(mouseKeyboardState().value)
                assertNull(store.getBoolean(mouseKey))
                assertEquals(true, store.getBoolean(otherProfileKey))
            }
            "reset-controller" -> {
                setMouseKeyboard(true)
                setGamepadEnabled(true)
                setGamepadDeadZone(0.50f)
                setGamepadRepeatIntervalMs(200)
                resetGamepadSettings()
                assertTrue(mouseKeyboardState().value)
                assertEquals(true, store.getBoolean(mouseKey))
                assertFalse(gamepadEnabledState().value)
                assertEquals(GamepadSettingsRepository.DEFAULT_DEAD_ZONE, gamepadDeadZoneState().value)
                assertEquals(GamepadSettingsRepository.DEFAULT_REPEAT_INTERVAL_MS, gamepadRepeatIntervalState().value)
            }
            "read-after-reset" -> {
                assertTrue(mouseKeyboardState().value)
                assertFalse(gamepadEnabledState().value)
                assertEquals(GamepadSettingsRepository.DEFAULT_DEAD_ZONE, gamepadDeadZoneState().value)
                assertEquals(GamepadSettingsRepository.DEFAULT_REPEAT_INTERVAL_MS, gamepadRepeatIntervalState().value)
            }
            else -> error(args[1])
        }
        DesktopStorage.flushAll()
    }
}
