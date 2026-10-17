package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.*
import java.io.File
import kotlin.test.*

/** The profile-owned "Advanced (mpv)" rows must take effect on Linux too, which skips the Windows profile. */
class LinuxAdvancedProfileTest {
    private val base = PlayerSettingsUiState(desktopMpvConfigMode = DesktopMpvConfigMode.Off)
    private fun with(vararg overrides: Pair<String, String>) =
        base.copy(desktopMpvPropertyOverrides = mapOf(*overrides))

    @Test fun defaultsKeepTheBridgeBaselineAndLeaveInterpolationAlone() {
        assertEquals(listOf("deband-grain" to "0", "target-contrast" to "auto"), linuxAdvancedProfileProperties(base))
    }

    @Test fun filmGrainDisplayTypeAndInterpolationAreApplied() {
        val properties = linuxAdvancedProfileProperties(with(
            DESKTOP_FILM_GRAIN_OVERRIDE_KEY to DESKTOP_FILM_GRAIN_HEAVY,
            DESKTOP_DISPLAY_PANEL_OVERRIDE_KEY to DESKTOP_DISPLAY_PANEL_OLED,
            DESKTOP_INTERPOLATION_OVERRIDE_KEY to "yes",
        )).toMap()
        assertEquals("32", properties["deband-grain"])
        assertEquals("inf", properties["target-contrast"])
        assertEquals("display-resample", properties["video-sync"])
        assertEquals("yes", properties["interpolation"])
    }

    @Test fun interpolationStaysOffUnderAudioPassthrough() {
        val properties = linuxAdvancedProfileProperties(with(DESKTOP_INTERPOLATION_OVERRIDE_KEY to "yes")
            .copy(desktopAudioPassthroughEnabled = true)).toMap()
        assertEquals("no", properties["interpolation"])
        assertEquals("audio", properties["video-sync"])
    }

    @Test fun sharpenAppendsCasToTheShaderChainWithItsStrength() {
        val profile = assertNotNull(linuxShaderProfile(with(DESKTOP_SHARPEN_OVERRIDE_KEY to "2.0"), false, null))
        assertEquals("nuvio_cas_sharpness=0.8", profile.shaderOpts)
        assertTrue(File(profile.chain.replace("\\:", ":")).isFile, profile.chain)
        assertTrue(profile.chain.endsWith("Nuvio_CAS_Sharpen.glsl"), profile.chain)
    }

    @Test fun sharpenOffLeavesNoChainAndNoOpts() {
        val profile = assertNotNull(linuxShaderProfile(with(DESKTOP_SHARPEN_OVERRIDE_KEY to "0"), false, null))
        assertEquals("", profile.chain)
        assertEquals("", profile.shaderOpts)
    }

    @Test fun userOwnedShadersStillWinOverSharpen() {
        val owned = with(DESKTOP_SHARPEN_OVERRIDE_KEY to "1.0")
            .copy(desktopMpvConfigMode = DesktopMpvConfigMode.Full)
        assertNull(linuxShaderProfile(owned, false, null))
    }
}
