package com.nuvio.app.features.updater

import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.nio.file.Files
import java.nio.file.Path

/*
 * Flatpak installs update through the Flatpak portal (org.freedesktop.portal.Flatpak), never by
 * downloading the release's .flatpak file: the portal pulls the new commit from the remote the app
 * was installed from and verifies it with that remote's GPG key. Installs made from the signed
 * release bundle or the .flatpakref follow the project's Flatpak repository; older bundle installs
 * have no update remote and are pointed at the one-time migration commands instead.
 */

internal sealed interface FlatpakUpdateResult {
    data object Updated : FlatpakUpdateResult

    /** The install's remote offered nothing newer (not published there yet, or no remote at all). */
    data object NothingNewer : FlatpakUpdateResult

    data class Failed(val reason: FlatpakUpdateFailure, val message: String) : FlatpakUpdateResult
}

internal enum class FlatpakUpdateFailure {
    /** The user denied the portal's "Update Nuvio?" dialog, now or earlier. */
    Denied,

    /** The new version asks for more sandbox permissions, which only `flatpak update` may grant. */
    NewPermissions,
    Other,
}

internal fun interface FlatpakPortalUpdater {
    /** Blocks until the portal finishes; [onProgress] receives 0..100. */
    fun update(onProgress: (percent: Int) -> Unit): FlatpakUpdateResult
}

/** Called from native code on the updating thread. */
internal fun interface LinuxFlatpakProgressCallback {
    fun onProgress(op: Int, ops: Int, progress: Int)
}

internal object LinuxFlatpakPortalNative {
    /** Returns `[status, D-Bus error name, message]`; see flatpak_update.cpp. */
    external fun update(callback: LinuxFlatpakProgressCallback, idleTimeoutMs: Int): Array<String>
}

/** Maps the native `[status, error, message]` triple; status follows the portal's Progress codes. */
internal fun flatpakUpdateResult(raw: Array<String>): FlatpakUpdateResult {
    val status = raw.getOrNull(0)?.toIntOrNull()
    val error = raw.getOrNull(1).orEmpty()
    val message = raw.getOrNull(2).orEmpty().take(300)
    return when (status) {
        2 -> FlatpakUpdateResult.Updated
        1 -> FlatpakUpdateResult.NothingNewer
        else -> FlatpakUpdateResult.Failed(
            reason = when {
                error == "org.freedesktop.DBus.Error.AccessDenied" -> FlatpakUpdateFailure.Denied
                error == "org.freedesktop.DBus.Error.NotSupported" && "permission" in message ->
                    FlatpakUpdateFailure.NewPermissions
                else -> FlatpakUpdateFailure.Other
            },
            message = message.ifBlank { "the Flatpak portal reported an error" },
        )
    }
}

/** Overall percentage across the portal's operations (runtime and app updates count separately). */
internal fun flatpakOverallPercent(op: Int, ops: Int, progress: Int): Int {
    if (ops <= 0) return progress.coerceIn(0, 100)
    val done = op.coerceIn(0, ops - 1) * 100 + progress.coerceIn(0, 100)
    return (done / ops).coerceIn(0, 100)
}

internal val systemFlatpakPortalUpdater = FlatpakPortalUpdater { onProgress ->
    NativePlayerBridge.ensureNativeLibraryLoaded()
    val raw = LinuxFlatpakPortalNative.update(
        { op, ops, progress -> onProgress(flatpakOverallPercent(op, ops, progress)) },
        FlatpakPortalIdleTimeoutMs,
    )
    flatpakUpdateResult(raw)
}

/** No portal progress for this long (including an unanswered permission dialog) ends the attempt. */
private const val FlatpakPortalIdleTimeoutMs = 15 * 60 * 1000

/** `--user` or `--system`, from the sandbox's own record of where the app is installed. */
internal fun flatpakInstallationOption(flatpakInfo: String?): String {
    val appPath = flatpakInfo?.lineSequence()
        ?.map(String::trim)
        ?.firstOrNull { it.startsWith("app-path=") }
        ?.removePrefix("app-path=")
    return if (appPath != null && appPath.startsWith("/var/lib/flatpak/")) "--system" else "--user"
}

internal fun readFlatpakInfo(): String? = runCatching { Files.readString(Path.of("/.flatpak-info")) }.getOrNull()

/** The one-time commands that link an install to the project's signed Flatpak repository. */
internal fun flatpakMigrationCommands(installation: String, appId: String): String = listOf(
    "flatpak remote-add $installation --if-not-exists $FlatpakRemoteName $FlatpakRepoFile",
    "flatpak install $installation --reinstall $FlatpakRemoteName $appId//stable",
).joinToString("\n")

internal const val FlatpakRemoteName = "nuvio"
internal const val FlatpakRepoFile = "https://iamslokker.github.io/NuvioDesktop-Linux/flatpak/nuvio.flatpakrepo"
internal const val FlatpakDefaultAppId = "com.nuvio.media.desktop"
