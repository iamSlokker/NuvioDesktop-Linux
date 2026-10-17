package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopColorGrade
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.desktopColorGrade
import com.nuvio.app.features.player.presetGrade

/** The ordinary desktop picture settings, without HDR output, RTX, SVP or shader ownership. */
internal fun NativePlayerController.applyLinuxBaseVideoProfile(settings: PlayerSettingsUiState, isHdr: Boolean?) {
    // Until Linux has its own validated HDR-output path, only confirmed SDR receives SDR grading.
    val grade = if (isHdr == false) settings.desktopColorProfile.presetGrade(settings.desktopColorGrade())
        else DesktopColorGrade(0, 0, 0, 0)
    val customNames = desktopCustomMpvOptionNames(settings.desktopCustomMpvOptions)
    withVideoProfile {
        val properties = listOf("contrast" to grade.contrast, "brightness" to grade.brightness,
            "saturation" to grade.saturation, "gamma" to grade.gamma).map { (key, value) -> key to value.toString() } +
            linuxAdvancedProfileProperties(settings)
        for ((key, value) in properties) {
            if (shouldApplyNuvioRuntimeMpvProperty(settings.desktopMpvConfigMode, customNames, key)) {
                setMpvProperty(key, value)
            }
        }
        applyDesktopBufferPreset(settings.desktopBufferPreset)
    }
}

/**
 * The profile-owned "Advanced (mpv)" rows (see DesktopAdvancedVideoOptions.kt) on Linux: film grain,
 * the display type's SDR tone-mapping contrast and mpv's motion interpolation. Sharpen rides on the
 * shader chain instead (see linuxShaderProfile). The bridge's live-action baseline has no grain, and
 * Linux runs neither SVP nor the Windows HDR target modes on the main player.
 */
internal fun linuxAdvancedProfileProperties(settings: PlayerSettingsUiState): List<Pair<String, String>> {
    val overrides = settings.desktopMpvPropertyOverrides
    return listOf(
        "deband-grain" to desktopDebandGrain(overrides[DESKTOP_FILM_GRAIN_OVERRIDE_KEY], "0"),
        "target-contrast" to desktopTargetContrast(overrides[DESKTOP_DISPLAY_PANEL_OVERRIDE_KEY], "auto"),
    ) + desktopInterpolationProperties(
        choice = overrides[DESKTOP_INTERPOLATION_OVERRIDE_KEY],
        svpActive = false,
        audioPassthrough = settings.desktopAudioPassthroughEnabled,
    )
}
