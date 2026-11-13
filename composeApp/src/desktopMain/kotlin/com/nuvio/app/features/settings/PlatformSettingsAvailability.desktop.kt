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

// The @nuvio-low-vram marker is sent to, and parsed by, the Windows and Linux bridges.
internal actual val platformOffersLowVramMode: Boolean
    get() = true

// logind's PowerOff; the Flatpak has no system bus, and giving it one would be a new permission.
internal actual val platformOffersScreensaverShutdown: Boolean
    get() = !isLinuxHost || com.nuvio.app.features.screensaver.LinuxPower.available

internal actual val platformDefaultMicrophoneLabel: String
    get() = if (isLinuxHost) "System default" else "Windows default"

// Linux's hid-playstation driver keeps the DualSense mic button to itself (it mutes the pad's mic).
internal actual val platformVoiceControllerHint: String
    get() = if (isLinuxHost) "or hold View on a controller" else "mic button on a DualSense, or hold View on an Xbox controller"

internal actual val platformOffersSvp: Boolean
    get() = !isLinuxHost

internal actual val platformListsWindowsOnlyPlaybackRowsInSearch: Boolean
    get() = !isLinuxHost

// PreshowAudioKeepAlive returns straight away on Linux.
internal actual val platformOffersAudioKeepAlive: Boolean
    get() = !isLinuxHost

// fork:linux - no Windows network prompt; ufw and firewalld are what can block the remote there.
internal actual val platformPhoneRemoteFirewallNote: String
    get() = if (isLinuxHost) {
        "Waiting for a phone. If a firewall is on (ufw, firewalld), allow TCP ports 47321 to 47330 and UDP 47320 on your home network."
    } else {
        "Waiting for a phone. Windows may ask once whether to allow Nuvio on private networks: allow it."
    }

internal actual val platformPhoneRemoteWakeNote: String
    get() = if (isLinuxHost) {
        " To wake this PC from the phone while it sleeps, turn on Wake-on-LAN for its network card (with NetworkManager: " +
            "nmcli connection modify <connection> 802-3-ethernet.wake-on-lan magic) and in the BIOS."
    } else {
        " To wake this PC from the phone while it sleeps, turn on Wake on Magic Packet for its network card " +
            "(Device Manager › the network adapter › Power Management and Advanced) and Wake-on-LAN in the BIOS."
    }
