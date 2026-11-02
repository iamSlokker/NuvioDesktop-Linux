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

// LinuxScreensaver is dim-only by design: it has no exit or power-off path.
internal actual val platformOffersScreensaverShutdown: Boolean
    get() = !isLinuxHost

internal actual val platformListsWindowsOnlyPlaybackRowsInSearch: Boolean
    get() = !isLinuxHost
