package com.nuvio.app.core.build

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.nio.file.Files
import java.nio.file.Path

/**
 * Whether this process runs inside the sealed Flatpak, the one package where the host's folders,
 * programs and browsers are out of reach. Some features have to say so or go through a portal.
 */
internal object LinuxFlatpakSandbox {
    val isActive: Boolean by lazy {
        DesktopHostOs.current == DesktopHostOs.LINUX && detect(System.getenv()) { Files.isRegularFile(it) }
    }

    internal fun detect(environment: Map<String, String>, isRegularFile: (Path) -> Boolean): Boolean =
        !environment["FLATPAK_ID"].isNullOrBlank() || isRegularFile(Path.of("/.flatpak-info"))
}
