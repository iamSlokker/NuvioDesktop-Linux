package com.nuvio.app.features.p2p

import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.io.File

private val LinuxCaBundles = listOf(
    "/etc/ssl/certs/ca-certificates.crt", // Debian, Ubuntu, Arch, the Flatpak runtime
    "/etc/pki/tls/certs/ca-bundle.crt", // Fedora, Bazzite
    "/etc/ssl/ca-bundle.pem", // openSUSE
    "/etc/ssl/cert.pem", // Alpine, others
)

/**
 * HTTPS trackers need the system's certificates. The Windows engine reads the live store; on Linux
 * OpenSSL looks where its build says, and the AppImage's copy from Ubuntu finds nothing on Fedora.
 * Null off Linux, and when nothing is found, so the engine keeps its default.
 */
internal fun linuxSystemCaBundle(
    host: DesktopHostOs = DesktopHostOs.current,
    candidates: List<File> = LinuxCaBundles.map(::File),
): File? = if (host == DesktopHostOs.LINUX) candidates.firstOrNull(File::isFile) else null
