package com.nuvio.app.core.storage

import com.nuvio.app.exitSoon
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.relaunchAfterExit

actual object PortableMode {
    // Linux installs (/opt, Flatpak, AppImage) are read-only and the relaunch helper is a .bat,
    // so the toggle could only fail there; Linux data stays under XDG_CONFIG_HOME.
    actual val isSupported: Boolean
        get() = DesktopHostOs.current != DesktopHostOs.LINUX && DesktopStorage.portableInstallRoot() != null

    actual val isEnabled: Boolean
        get() = DesktopStorage.isPortable

    actual fun setEnabledAndRestart(enabled: Boolean): Boolean {
        if (!DesktopStorage.setPortable(enabled)) return false
        DesktopStorage.flushAll()
        // The data moves at the next startup, so this process must actually stop.
        if (!relaunchAfterExit("relaunch-after-portable-switch.bat")) exitSoon()
        return true
    }
}
