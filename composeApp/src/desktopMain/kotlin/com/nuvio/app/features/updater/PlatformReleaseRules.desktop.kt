package com.nuvio.app.features.updater

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalUriHandler
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.updates_linux_appimage_saved
import nuvio.composeapp.generated.resources.updates_linux_deb_install_failed
import nuvio.composeapp.generated.resources.updates_linux_deb_saved
import nuvio.composeapp.generated.resources.updates_linux_external_message
import nuvio.composeapp.generated.resources.updates_linux_flatpak_message
import nuvio.composeapp.generated.resources.updates_linux_installed
import nuvio.composeapp.generated.resources.updates_linux_installing
import nuvio.composeapp.generated.resources.updates_linux_open_release_page
import nuvio.composeapp.generated.resources.updates_linux_reason_cancelled
import nuvio.composeapp.generated.resources.updates_linux_reason_failed
import nuvio.composeapp.generated.resources.updates_linux_reason_missing_tool
import nuvio.composeapp.generated.resources.updates_linux_restart_manually
import nuvio.composeapp.generated.resources.updates_linux_restart_now
import nuvio.composeapp.generated.resources.updates_open_download
import org.jetbrains.compose.resources.stringResource

// Windows and macOS keep the upstream repository and portable-ZIP rules (null).
internal actual val platformReleaseRules: PlatformReleaseRules?
    get() = if (LinuxUpdater.isActive) LinuxReleaseRules else null

internal fun desktopPlatformReleaseRules(osName: String?): PlatformReleaseRules? =
    if (isLinuxOsName(osName)) LinuxReleaseRules else null

@Composable
internal actual fun platformUpdatePrompt(
    update: AppUpdate?,
    downloadedPath: String?,
    isDownloading: Boolean,
): PlatformUpdatePrompt? {
    if (!LinuxUpdater.isActive || update == null) return null
    val updater = LinuxUpdater.current
    val status by updater.status.collectAsState()
    val uriHandler = LocalUriHandler.current

    if (!updater.format.updatesInApp) {
        val releasePage = update.releaseUrl
            ?.takeIf { it.startsWith("${LinuxReleaseRules.projectUrl}/") }
            ?: "${LinuxReleaseRules.projectUrl}/releases/latest"
        return PlatformUpdatePrompt(
            message = stringResource(
                if (updater.format == LinuxInstallFormat.Flatpak) {
                    Res.string.updates_linux_flatpak_message
                } else {
                    Res.string.updates_linux_external_message
                },
            ),
            actionLabel = stringResource(Res.string.updates_linux_open_release_page),
            action = { runCatching { uriHandler.openUri(releasePage) } },
        )
    }

    val current = status
    if (current is LinuxUpdateStatus.Installing) {
        val installing = stringResource(Res.string.updates_linux_installing)
        return PlatformUpdatePrompt(message = installing, actionLabel = installing)
    }
    if (downloadedPath == null || isDownloading) return null

    return when (current) {
        is LinuxUpdateStatus.Installed -> PlatformUpdatePrompt(
            message = stringResource(Res.string.updates_linux_installed),
            actionLabel = stringResource(Res.string.updates_linux_restart_now),
            action = updater::restart,
        )
        is LinuxUpdateStatus.RestartFailed -> PlatformUpdatePrompt(
            message = stringResource(Res.string.updates_linux_restart_manually),
            actionLabel = stringResource(Res.string.updates_linux_restart_now),
            action = updater::restart,
        )
        is LinuxUpdateStatus.SavedForManualInstall -> {
            val folder = current.file.parent.toString()
            val message = when {
                current.format == LinuxInstallFormat.AppImage ->
                    stringResource(Res.string.updates_linux_appimage_saved, folder)
                current.reason == null -> stringResource(Res.string.updates_linux_deb_saved, folder)
                else -> stringResource(
                    Res.string.updates_linux_deb_install_failed,
                    debInstallReason(current.reason),
                    folder,
                )
            }
            PlatformUpdatePrompt(
                message = message,
                detail = if (current.format == LinuxInstallFormat.Deb) {
                    "sudo apt install ./${current.file.fileName}"
                } else {
                    null
                },
                actionLabel = stringResource(Res.string.updates_open_download),
                action = { updater.openDownload(current.file) },
            )
        }
        else -> null
    }
}

@Composable
private fun debInstallReason(reason: DebInstallResult): String = when (reason) {
    is DebInstallResult.Unavailable -> stringResource(Res.string.updates_linux_reason_missing_tool, reason.tool)
    DebInstallResult.Cancelled -> stringResource(Res.string.updates_linux_reason_cancelled)
    is DebInstallResult.Failed -> stringResource(Res.string.updates_linux_reason_failed, reason.exitCode)
    DebInstallResult.Installed -> ""
}
