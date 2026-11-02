package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopColorGrade
import com.nuvio.app.features.player.DesktopHdrMode
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.desktopColorGrade
import com.nuvio.app.features.player.desktopHdrTargetMpvProperties
import com.nuvio.app.features.player.presetGrade

/**
 * Windows' rule for the SDR-tuned colour presets (forceNeutral in applyDesktopVideoProfile): they suit
 * an SDR source, or an HDR one being tonemapped on purpose, and crush shadows on anything else.
 */
internal fun linuxColorGradeApplies(hdrMode: DesktopHdrMode, isHdr: Boolean?): Boolean =
    isHdr == false || hdrMode == DesktopHdrMode.AlwaysTonemap

/**
 * The output target of the embedded player. It draws on an X11 surface, which has no HDR path, so
 * Always Passthrough has nothing to ask for here and takes Auto's targets. Real passthrough is the
 * Experimental HDR (Wayland) session, which picks up the same setting.
 */
internal fun linuxEmbeddedHdrTargetProperties(settings: PlayerSettingsUiState): List<Pair<String, String>> =
    desktopHdrTargetMpvProperties(
        hdrMode = if (settings.desktopHdrMode == DesktopHdrMode.AlwaysPassthrough) DesktopHdrMode.Auto else settings.desktopHdrMode,
        displayPanel = settings.desktopMpvPropertyOverrides[DESKTOP_DISPLAY_PANEL_OVERRIDE_KEY],
    )

/** The info panel's HDR row for the embedded player: HDR video always reaches it tone mapped. */
internal fun linuxEmbeddedHdrInfoLabel(isHdr: Boolean?): String? =
    if (isHdr == true) "HDR Tone Mapped to SDR" else null

/** The pill for a mode change, saying so when the choice cannot be honoured in this window. */
internal fun linuxHdrModePillValue(mode: DesktopHdrMode, label: String, experimentalHdrEnabled: Boolean): String =
    if (mode == DesktopHdrMode.AlwaysPassthrough && !experimentalHdrEnabled) {
        "$label: needs Experimental HDR (Wayland)"
    } else {
        label
    }

/** The ordinary desktop picture settings, without RTX, SVP or shader ownership. */
internal fun NativePlayerController.applyLinuxBaseVideoProfile(settings: PlayerSettingsUiState, isHdr: Boolean?) {
    val grade = if (linuxColorGradeApplies(settings.desktopHdrMode, isHdr)) {
        settings.desktopColorProfile.presetGrade(settings.desktopColorGrade())
    } else {
        DesktopColorGrade(0, 0, 0, 0)
    }
    val customNames = desktopCustomMpvOptionNames(settings.desktopCustomMpvOptions)
    withVideoProfile {
        val properties = listOf("contrast" to grade.contrast, "brightness" to grade.brightness,
            "saturation" to grade.saturation, "gamma" to grade.gamma).map { (key, value) -> key to value.toString() } +
            linuxEmbeddedHdrTargetProperties(settings) + linuxAdvancedProfileProperties(settings)
        for ((key, value) in properties) {
            if (shouldApplyNuvioRuntimeMpvProperty(settings.desktopMpvConfigMode, customNames, key)) {
                setMpvProperty(key, value)
            }
        }
        applyDesktopBufferPreset(settings.desktopBufferPreset)
    }
}

/**
 * The profile-owned "Advanced (mpv)" rows (see DesktopAdvancedVideoOptions.kt) on Linux: film grain
 * and mpv's motion interpolation. The display type goes through linuxEmbeddedHdrTargetProperties and
 * Sharpen rides on the shader chain (see linuxShaderProfile). The bridge's live-action baseline has
 * no grain, and Linux does not run SVP.
 */
internal fun linuxAdvancedProfileProperties(settings: PlayerSettingsUiState): List<Pair<String, String>> {
    val overrides = settings.desktopMpvPropertyOverrides
    return listOf(
        "deband-grain" to desktopDebandGrain(overrides[DESKTOP_FILM_GRAIN_OVERRIDE_KEY], "0"),
    ) + desktopInterpolationProperties(
        choice = overrides[DESKTOP_INTERPOLATION_OVERRIDE_KEY],
        svpActive = false,
        audioPassthrough = settings.desktopAudioPassthroughEnabled,
    )
}
