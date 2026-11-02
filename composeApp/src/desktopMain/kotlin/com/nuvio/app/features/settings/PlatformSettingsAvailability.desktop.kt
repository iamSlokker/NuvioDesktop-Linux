package com.nuvio.app.features.settings

import com.nuvio.app.features.player.desktop.DesktopHostOs

private val isLinuxHost: Boolean
    get() = DesktopHostOs.current == DesktopHostOs.LINUX

// configureDesktopRenderer (Main.kt) forces OpenGL on Linux and never reads the saved choice.
internal actual val platformOffersInterfaceRenderer: Boolean
    get() = !isLinuxHost

// DesktopHdrUiCompensationEffect is gated to DesktopHostOs.WINDOWS.
internal actual val platformOffersHdrUiCompensation: Boolean
    get() = !isLinuxHost

// Without a tray the close button has to end the app (Main.kt), so a switch would say otherwise.
internal actual val platformOffersCloseToTray: Boolean
    get() = !isLinuxHost || com.nuvio.app.core.ui.LinuxStatusNotifierTray.trayExists

// The @nuvio-low-vram marker is only sent to, and only parsed by, the Windows bridge.
internal actual val platformOffersLowVramMode: Boolean
    get() = !isLinuxHost

// logind's PowerOff; the Flatpak has no system bus, and giving it one would be a new permission.
internal actual val platformOffersScreensaverShutdown: Boolean
    get() = !isLinuxHost || com.nuvio.app.features.screensaver.LinuxPower.available

internal actual val platformOffersSvp: Boolean
    get() = !isLinuxHost

internal actual val platformListsWindowsOnlyPlaybackRowsInSearch: Boolean
    get() = !isLinuxHost

// PreshowAudioKeepAlive returns straight away on Linux.
internal actual val platformOffersAudioKeepAlive: Boolean
    get() = !isLinuxHost
