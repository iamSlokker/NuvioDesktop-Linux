package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.DesktopMpvConfigMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import kotlin.test.*

/** Calls the real private option producer without constructing a player/window or loading JNI. */
class LinuxAudioPassthroughOptionsTest {
    private fun probe(case: String) = withHeadlessFixture {
        runHeadlessProbe(it, LinuxAudioPassthroughOptionsProbe::class.java, case)
    }

    @Test fun disabledByDefault() = probe("disabled")
    @Test fun enabledEmitsCodecsAndFallback() = probe("enabled")
    @Test fun disablingDoesNotCarryOldOptions() = probe("toggle")
    @Test fun fullModeRetainsUserOwnership() = probe("full")
    @Test fun addAndReplacePreserveCustomEntriesAndOrdering() = probe("custom")
    @Test fun preferenceSurvivesFreshProcesses() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxAudioPassthroughOptionsProbe::class.java, "write")
        runHeadlessProbe(it, LinuxAudioPassthroughOptionsProbe::class.java, "read")
        runHeadlessProbe(it, LinuxAudioPassthroughOptionsProbe::class.java, "disableWrite")
        runHeadlessProbe(it, LinuxAudioPassthroughOptionsProbe::class.java, "disabledRead")
    }
}

internal object LinuxAudioPassthroughOptionsProbe {
    private const val Codecs = "audio-spdif=ac3,dts,eac3,truehd,dts-hd,dts-hd-ma"

    @Suppress("UNCHECKED_CAST")
    private fun options(): List<String> = Class.forName(
        "com.nuvio.app.features.player.desktop.NativePlayerControllerKt",
    ).getDeclaredMethod("buildDesktopUserMpvOptions", Float::class.javaPrimitiveType).let {
        it.isAccessible = true
        it.invoke(null, 1f) as List<String>
    }

    private fun assertEnabled() {
        val options = options()
        assertEquals(1, options.count { it == Codecs })
        assertEquals(1, options.count { it == "audio-fallback-to-null=yes" })
    }

    private fun assertDisabled() = assertTrue(options().none {
        it.startsWith("audio-spdif=") || it.startsWith("audio-fallback-to-null=")
    })

    @JvmStatic fun main(args: Array<String>) {
        PlayerSettingsRepository.ensureLoaded()
        when (args[1]) {
            "disabled", "disabledRead" -> {
                assertFalse(PlayerSettingsRepository.uiState.value.desktopAudioPassthroughEnabled)
                assertDisabled()
            }
            "enabled", "toggle", "write" -> {
                PlayerSettingsRepository.setDesktopAudioPassthroughEnabled(true)
                val before = PlayerSettingsRepository.uiState.value
                assertEnabled()
                assertEquals(before, PlayerSettingsRepository.uiState.value)
                if (args[1] == "toggle") {
                    PlayerSettingsRepository.setDesktopAudioPassthroughEnabled(false)
                    assertDisabled()
                }
            }
            "read" -> {
                assertTrue(PlayerSettingsRepository.uiState.value.desktopAudioPassthroughEnabled)
                assertEnabled()
            }
            "disableWrite" -> PlayerSettingsRepository.setDesktopAudioPassthroughEnabled(false)
            "full", "custom" -> {
                PlayerSettingsRepository.setDesktopAudioPassthroughEnabled(true)
                val custom = "# fixture\naudio-spdif=\naudio-fallback-to-null=no"
                PlayerSettingsRepository.setDesktopCustomMpvOptions(custom)
                val modes = if (args[1] == "full") listOf(DesktopMpvConfigMode.Full)
                    else listOf(DesktopMpvConfigMode.Add, DesktopMpvConfigMode.Replace)
                modes.forEach { mode ->
                    PlayerSettingsRepository.setDesktopMpvConfigMode(mode)
                    val before = PlayerSettingsRepository.uiState.value
                    val options = options()
                    assertEquals(mode != DesktopMpvConfigMode.Full, Codecs in options)
                    assertEquals(mode != DesktopMpvConfigMode.Full, "audio-fallback-to-null=yes" in options)
                    assertTrue("@nuvio-user:audio-spdif=" in options)
                    assertTrue("@nuvio-user:audio-fallback-to-null=no" in options)
                    if (mode != DesktopMpvConfigMode.Full)
                        assertTrue(options.indexOf(Codecs) < options.indexOf("@nuvio-user:audio-spdif="))
                    assertEquals(before, PlayerSettingsRepository.uiState.value)
                }
            }
            else -> error("Unknown probe")
        }
        DesktopStorage.flushAll()
    }
}
