package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.LinuxHdrSessionSettings

@Composable
internal actual fun PlatformHdrSettingsRows(isTablet: Boolean) {
    if (DesktopHostOs.current != DesktopHostOs.LINUX) return
    val enabled by LinuxHdrSessionSettings.enabledState.collectAsState()
    SettingsGroupDivider(isTablet = isTablet)
    SettingsSwitchRow(
        title = "Experimental HDR (Wayland)",
        description = "Plays PQ/HLG HDR video in a separate fullscreen Wayland window when the " +
            "compositor, GPU and display report HDR10 support. Unverified on HDR hardware; any " +
            "failure returns to the normal player. Uses a minimal on-screen display instead of " +
            "the full player controls.",
        checked = enabled,
        isTablet = isTablet,
        onCheckedChange = LinuxHdrSessionSettings::setEnabled,
    )
}
