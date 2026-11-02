package com.nuvio.app.features.updater

import com.nuvio.app.LinuxDesktopRelaunch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.nuvio.app.core.i18n.localizedString
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.linux_updates_auto_install_description_appimage
import nuvio.composeapp.generated.resources.linux_updates_auto_install_description_deb
import nuvio.composeapp.generated.resources.updates_linux_asset_missing
import org.jetbrains.compose.resources.getString
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat
import java.util.concurrent.TimeUnit

/*
 * Linux in-app updates.
 *
 * Linux builds are published on the Linux fork's own repository as `vX.Y.Z` releases carrying one
 * asset per package format. The running install's format decides what an update does:
 *  - AppImage: download next to $APPIMAGE, verify, atomically rename over it, offer a restart.
 *  - DEB: download the verified package to the user's Downloads folder and install it through
 *    polkit (`pkexec apt-get install`), falling back to a manual `apt install` command.
 *  - Flatpak: nothing is downloaded; the Flatpak portal updates the install from its own signed
 *    remote (see LinuxFlatpakUpdate.kt), and the restart starts the newly deployed version.
 *  - Anything unrecognised: nothing is downloaded; the dialog opens the release page.
 * Every download must match the SHA-256 digest GitHub publishes for the asset, or it is discarded.
 */

internal fun isLinuxOsName(osName: String?): Boolean = osName.orEmpty().equals("Linux", ignoreCase = true)

internal enum class LinuxInstallFormat(val assetExtension: String?) {
    AppImage(".AppImage"),
    Deb(".deb"),
    Flatpak(".flatpak"),

    /** A source run or an unpacked image: there is no package this app may replace. */
    Unknown(null),
    ;

    /** Whether the update is applied from inside Nuvio (Flatpak through the Flatpak portal). */
    val updatesInApp: Boolean get() = this != Unknown

    companion object {
        /** Where the DEB package installs the application image. */
        val DebInstallRoot: Path = Path.of("/opt/nuvio-desktop")

        fun detect(
            environment: Map<String, String>,
            packagedLauncher: String?,
            isRegularFile: (Path) -> Boolean = Files::isRegularFile,
        ): LinuxInstallFormat {
            val appImage = environment["APPIMAGE"]?.takeIf(String::isNotBlank)?.let(::pathOrNull)
            if (appImage != null && appImage.isAbsolute && isRegularFile(appImage)) return AppImage
            if (!environment["FLATPAK_ID"].isNullOrBlank() || isRegularFile(Path.of("/.flatpak-info"))) return Flatpak
            val launcher = packagedLauncher?.takeIf(String::isNotBlank)?.let(::pathOrNull)?.normalize()
            if (launcher != null && launcher.isAbsolute && launcher.startsWith(DebInstallRoot)) return Deb
            return Unknown
        }
    }
}

/**
 * The portal alone installs a Flatpak update, whatever the setting says, and there is nothing to
 * apply in an install the updater does not recognise. An AppImage and a DEB do read it.
 */
internal fun linuxOffersInPlaceUpdateOption(format: LinuxInstallFormat): Boolean =
    format == LinuxInstallFormat.AppImage || format == LinuxInstallFormat.Deb

/** The setting's upstream text is about extracting the Windows ZIP, which no Linux package does. */
internal fun linuxInPlaceUpdateOptionDescription(format: LinuxInstallFormat): String? = when (format) {
    LinuxInstallFormat.AppImage -> localizedString(Res.string.linux_updates_auto_install_description_appimage)
    LinuxInstallFormat.Deb -> localizedString(Res.string.linux_updates_auto_install_description_deb)
    else -> null
}

/** Which releases and assets of the Linux fork's repository this install accepts. */
internal object LinuxReleaseRules : PlatformReleaseRules {
    override val repository: String = "iamSlokker/NuvioDesktop-Linux"
    override val projectUrl: String = "https://github.com/iamSlokker/NuvioDesktop-Linux"
    override val nightlyChannelAvailable: Boolean = false

    /** Exactly `vX.Y.Z`: never the `vX.Y.Z-source` archive release or any other suffix. */
    private val releaseTagPattern = Regex("""^[vV](\d+)\.(\d+)\.(\d+)$""")

    fun releaseVersion(tag: String?): String? =
        tag?.trim()?.let(releaseTagPattern::matchEntire)?.destructured?.let { (major, minor, patch) ->
            "$major.$minor.$patch"
        }

    override fun matchesChannel(release: GitHubReleaseDto, channel: UpdateChannel): Boolean =
        channel == UpdateChannel.Stable && !release.draft && !release.prerelease &&
            releaseVersion(release.tagName) != null

    override fun selectUpdateAsset(release: GitHubReleaseDto): GitHubAssetDto? {
        val format = LinuxUpdater.current.format
        return selectAsset(release, format, System.getProperty("os.arch"))
            ?: error(runBlocking { getString(Res.string.updates_linux_asset_missing, assetDescription(format)) })
    }

    fun selectAsset(release: GitHubReleaseDto, format: LinuxInstallFormat, arch: String?): GitHubAssetDto? {
        val version = releaseVersion(release.tagName) ?: return null
        if (!isX86_64(arch)) return null
        // Nothing is installed from an unknown install; any published format names the release.
        val candidates = if (format == LinuxInstallFormat.Unknown) {
            listOf(LinuxInstallFormat.AppImage, LinuxInstallFormat.Deb, LinuxInstallFormat.Flatpak)
        } else {
            listOf(format)
        }
        for (candidate in candidates) {
            val name = assetName(version, candidate) ?: continue
            release.assets.firstOrNull { it.name == name }?.let { return it }
        }
        return null
    }

    fun assetName(version: String, format: LinuxInstallFormat): String? =
        format.assetExtension?.let { "NuvioDesktop-Linux-$version-x86_64$it" }

    /** Whether [name] is a release asset of [format]; the only names ever written to disk. */
    fun isAssetName(name: String, format: LinuxInstallFormat): Boolean {
        val extension = format.assetExtension ?: return false
        val version = name.removePrefix("NuvioDesktop-Linux-").removeSuffix("-x86_64$extension")
        return name.startsWith("NuvioDesktop-Linux-") && name.endsWith("-x86_64$extension") &&
            releaseVersion("v$version") != null
    }

    private fun isX86_64(arch: String?): Boolean =
        arch.orEmpty().lowercase().let { it == "amd64" || it == "x86_64" }

    private fun assetDescription(format: LinuxInstallFormat): String = when (format) {
        LinuxInstallFormat.AppImage -> "AppImage"
        LinuxInstallFormat.Deb -> "DEB package"
        LinuxInstallFormat.Flatpak -> "Flatpak bundle"
        LinuxInstallFormat.Unknown -> "build"
    }
}

internal data class LinuxCommandOutcome(val exitCode: Int, val output: String = "")

internal fun interface LinuxCommandRunner {
    fun run(command: List<String>): LinuxCommandOutcome
}

internal sealed interface DebInstallResult {
    data object Installed : DebInstallResult

    /** A tool the install needs is missing, so it was never attempted. */
    data class Unavailable(val tool: String) : DebInstallResult

    /** pkexec could not authorize: the polkit dialog was dismissed or authentication failed. */
    data object Cancelled : DebInstallResult

    data class Failed(val exitCode: Int, val output: String) : DebInstallResult
}

/** What the last Linux update attempt left behind, for the update dialog. */
internal sealed interface LinuxUpdateStatus {
    data object Idle : LinuxUpdateStatus

    data object Installing : LinuxUpdateStatus

    /** The new build is in place (AppImage replaced, package or Flatpak installed); a restart applies it. */
    data class Installed(val file: Path) : LinuxUpdateStatus

    /** The Flatpak portal did not update this install; [commands] update it from a terminal. */
    data class FlatpakNotUpdated(val result: FlatpakUpdateResult, val commands: String) : LinuxUpdateStatus

    /** Installed, but this process could not start the new one. */
    data class RestartFailed(val file: Path) : LinuxUpdateStatus

    /** The verified download waits for the user; [reason] says why a DEB was not installed. */
    data class SavedForManualInstall(
        val format: LinuxInstallFormat,
        val file: Path,
        val reason: DebInstallResult? = null,
    ) : LinuxUpdateStatus
}

internal class LinuxUpdaterEnvironment(
    val format: LinuxInstallFormat,
    val environment: Map<String, String>,
    val httpClient: HttpClient,
    val findExecutable: (String) -> Path?,
    val runCommand: LinuxCommandRunner,
    val downloadsDirectory: () -> Path,
    val inPlaceEnabled: () -> Boolean,
    val relaunch: () -> Boolean,
    val openPath: (Path) -> Unit,
    val flatpakUpdater: FlatpakPortalUpdater = FlatpakPortalUpdater { error("The Flatpak portal is not available.") },
    val flatpakInfo: () -> String? = { null },
    /** Starts the newest deployed Flatpak version once this process exits. */
    val relaunchLatestFlatpak: () -> Boolean = relaunch,
)

internal class LinuxUpdater(private val env: LinuxUpdaterEnvironment) {
    val format: LinuxInstallFormat get() = env.format

    private val _status = MutableStateFlow<LinuxUpdateStatus>(LinuxUpdateStatus.Idle)
    val status: StateFlow<LinuxUpdateStatus> = _status.asStateFlow()

    /** Downloads, verifies and (where this install allows it) applies the update. */
    suspend fun download(
        assetUrl: String,
        assetName: String,
        expectedSha256: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            _status.value = LinuxUpdateStatus.Idle
            try {
                check(env.format.updatesInApp) { "This install is updated outside Nuvio." }
                require(LinuxReleaseRules.isAssetName(assetName, env.format)) {
                    "Update refused: $assetName is not a Linux ${env.format} release asset."
                }
                // The release's .flatpak only proves the version exists; the portal verifies the update.
                if (env.format == LinuxInstallFormat.Flatpak) return@runCatching applyFlatpak(onProgress).toString()
                // Fail closed, as on Windows: no published digest, no download.
                val sha256 = requireExpectedArchiveSha256(expectedSha256).lowercase()
                val download = VerifiedDownload(env.httpClient, assetUrl, assetName, sha256, onProgress)
                when (env.format) {
                    LinuxInstallFormat.AppImage -> applyAppImage(download)
                    LinuxInstallFormat.Deb -> applyDeb(download)
                    else -> error("This install is updated outside Nuvio.")
                }.toString()
            } catch (error: Throwable) {
                _status.value = LinuxUpdateStatus.Idle
                throw error
            }
        }
    }

    /** The second half of the shared download-then-install flow; [download] already did the work. */
    fun install(path: String): Result<UpdateInstallOutcome> = runCatching {
        val file = when (val status = _status.value) {
            is LinuxUpdateStatus.Installed -> status.file
            is LinuxUpdateStatus.RestartFailed -> status.file
            is LinuxUpdateStatus.SavedForManualInstall -> status.file
            is LinuxUpdateStatus.FlatpakNotUpdated -> FlatpakAppPath
            else -> null
        }
        check(file != null && file.toString() == path) { "The downloaded update file is missing." }
        // Nothing to swap after exit: the dialog now offers the restart or the manual step.
        UpdateInstallOutcome.REVEALED
    }

    fun restart() {
        val file = (_status.value as? LinuxUpdateStatus.Installed)?.file
            ?: (_status.value as? LinuxUpdateStatus.RestartFailed)?.file
            ?: return
        val relaunch = if (env.format == LinuxInstallFormat.Flatpak) env.relaunchLatestFlatpak else env.relaunch
        val started = runCatching { relaunch() }.getOrDefault(false)
        if (!started) _status.value = LinuxUpdateStatus.RestartFailed(file)
    }

    fun openDownload(file: Path) {
        // A DEB opens in the system's package installer; an AppImage's folder is shown instead
        // of running the new binary behind the user's back.
        val target = if (file.fileName.toString().endsWith(".deb")) file else file.parent
        runCatching { env.openPath(target) }
    }

    private fun applyAppImage(download: VerifiedDownload): Path {
        val appImage = Path.of(env.environment.getValue("APPIMAGE")).toRealPath()
        if (env.inPlaceEnabled() && isDirectoryWritable(appImage.parent)) {
            replaceAppImage(appImage, download)
            _status.value = LinuxUpdateStatus.Installed(appImage)
            return appImage
        }
        val saved = saveVerifiedDownload(env.downloadsDirectory(), download, executable = true)
        _status.value = LinuxUpdateStatus.SavedForManualInstall(LinuxInstallFormat.AppImage, saved)
        return saved
    }

    private fun applyFlatpak(onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit): Path {
        val result = env.flatpakUpdater.update { percent -> onProgress(percent.toLong(), 100L) }
        _status.value = if (result == FlatpakUpdateResult.Updated) {
            LinuxUpdateStatus.Installed(FlatpakAppPath)
        } else {
            val installation = flatpakInstallationOption(env.flatpakInfo())
            val appId = env.environment["FLATPAK_ID"]?.takeIf(String::isNotBlank) ?: FlatpakDefaultAppId
            val permissionOnly = result is FlatpakUpdateResult.Failed && result.reason != FlatpakUpdateFailure.Other
            LinuxUpdateStatus.FlatpakNotUpdated(
                result,
                if (permissionOnly) {
                    "flatpak update $installation $appId"
                } else {
                    flatpakMigrationCommands(installation, appId)
                },
            )
        }
        return FlatpakAppPath
    }

    private fun applyDeb(download: VerifiedDownload): Path {
        val saved = saveVerifiedDownload(env.downloadsDirectory(), download, executable = false)
        if (!env.inPlaceEnabled()) {
            _status.value = LinuxUpdateStatus.SavedForManualInstall(LinuxInstallFormat.Deb, saved)
            return saved
        }
        _status.value = LinuxUpdateStatus.Installing
        val result = installDeb(saved, download.sha256, env.findExecutable, env.runCommand)
        _status.value = if (result == DebInstallResult.Installed) {
            LinuxUpdateStatus.Installed(saved)
        } else {
            LinuxUpdateStatus.SavedForManualInstall(LinuxInstallFormat.Deb, saved, result)
        }
        return saved
    }

    companion object {
        /**
         * Whether this process routes updates through the Linux updater. Mutable only so the
         * Windows portable-ZIP tests can still exercise that path on a Linux test host.
         */
        @Volatile
        internal var isActive: Boolean = isLinuxOsName(System.getProperty("os.name"))

        val current: LinuxUpdater by lazy { LinuxUpdater(systemLinuxUpdaterEnvironment()) }

        /** The sandbox's application mount: the "file" a Flatpak update replaces. */
        val FlatpakAppPath: Path = Path.of("/app")
    }
}

internal class VerifiedDownload(
    val httpClient: HttpClient,
    val url: String,
    val assetName: String,
    val sha256: String,
    val onProgress: (downloadedBytes: Long, totalBytes: Long?) -> Unit,
)

/**
 * Streams [download] into [part], hashing as it goes. On any failure, including a digest mismatch,
 * the partial file is removed and nothing else is touched.
 */
internal fun downloadVerifiedTo(download: VerifiedDownload, part: Path) {
    Files.deleteIfExists(part)
    try {
        val request = HttpRequest.newBuilder(URI.create(download.url))
            .timeout(Duration.ofMinutes(10))
            .header("Accept", "application/octet-stream")
            .GET()
            .build()
        val response = download.httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            error("Update download failed with HTTP ${response.statusCode()}.")
        }
        val totalBytes = response.headers().firstValue("Content-Length").orElse(null)
            ?.toLongOrNull()
            ?.takeIf { it > 0L }
        val digest = MessageDigest.getInstance("SHA-256")
        var downloadedBytes = 0L
        response.body().use { input ->
            Files.newOutputStream(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    digest.update(buffer, 0, read)
                    downloadedBytes += read
                    download.onProgress(downloadedBytes, totalBytes)
                }
            }
        }
        check(downloadedBytes > 0L) { "The downloaded update was empty." }
        check(HexFormat.of().formatHex(digest.digest()).equals(download.sha256, ignoreCase = true)) {
            "Update verification failed: the download's SHA-256 did not match the expected checksum. " +
                "The file was discarded."
        }
    } catch (error: Throwable) {
        runCatching { Files.deleteIfExists(part) }
        throw error
    }
}

private val executablePermissions = PosixFilePermissions.fromString("rwxr-xr-x")

/**
 * Replaces [appImage] with the verified download. The new file is written beside it as a hidden
 * `.part` (same filesystem, so the final rename is atomic) and only renamed over the original once
 * its digest matched; the running AppImage keeps its already-open inode until it exits.
 */
internal fun replaceAppImage(appImage: Path, download: VerifiedDownload) {
    val part = appImage.resolveSibling(".${download.assetName}.part")
    downloadVerifiedTo(download, part)
    try {
        Files.setPosixFilePermissions(part, executablePermissions)
        Files.move(part, appImage, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: Throwable) {
        runCatching { Files.deleteIfExists(part) }
        throw error
    }
}

/** Saves the verified download as `<directory>/<asset name>`, replacing an older copy. */
internal fun saveVerifiedDownload(directory: Path, download: VerifiedDownload, executable: Boolean): Path {
    Files.createDirectories(directory)
    val destination = directory.resolve(download.assetName)
    val part = directory.resolve(".${download.assetName}.part")
    downloadVerifiedTo(download, part)
    try {
        if (executable) Files.setPosixFilePermissions(part, executablePermissions)
        Files.move(part, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (error: Throwable) {
        runCatching { Files.deleteIfExists(part) }
        throw error
    }
    return destination
}

/**
 * The polkit install command for [deb]: apt-get resolves dependencies, dpkg is the fallback where
 * apt-get is missing. An argument list, never a shell string, and only ever the verified file.
 */
internal fun linuxDebInstallCommand(deb: Path, findExecutable: (String) -> Path?): Pair<List<String>?, String?> {
    val pkexec = findExecutable("pkexec") ?: return null to "pkexec"
    val file = deb.toAbsolutePath().normalize().toString()
    findExecutable("apt-get")?.let { aptGet ->
        return listOf(pkexec.toString(), aptGet.toString(), "install", "-y", file) to null
    }
    findExecutable("dpkg")?.let { dpkg -> return listOf(pkexec.toString(), dpkg.toString(), "-i", file) to null }
    return null to "apt-get"
}

internal fun installDeb(
    deb: Path,
    expectedSha256: String,
    findExecutable: (String) -> Path?,
    runCommand: LinuxCommandRunner,
): DebInstallResult {
    val (command, missingTool) = linuxDebInstallCommand(deb, findExecutable)
    if (command == null) return DebInstallResult.Unavailable(missingTool ?: "pkexec")
    // The file sits in a user-writable folder: hash it again right before handing it to root.
    check(sha256Of(deb).equals(expectedSha256, ignoreCase = true)) {
        "Update verification failed: the package changed after download. Download the update again."
    }
    val outcome = runCommand.run(command)
    return when (outcome.exitCode) {
        0 -> DebInstallResult.Installed
        // pkexec: 126 = the authorization dialog was dismissed, 127 = not authorized.
        126, 127 -> DebInstallResult.Cancelled
        else -> DebInstallResult.Failed(outcome.exitCode, outcome.output)
    }
}

internal fun sha256Of(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return HexFormat.of().formatHex(digest.digest())
}

/**
 * The user's Downloads folder: `xdg-user-dir DOWNLOAD`, then `user-dirs.dirs`, then ~/Downloads.
 * xdg-user-dir answers $HOME when the folder is not configured, which is never where a download
 * should land.
 */
internal fun linuxDownloadsDirectory(
    environment: Map<String, String>,
    home: Path,
    queryXdgUserDir: () -> String?,
): Path {
    val normalizedHome = home.toAbsolutePath().normalize()
    fun usable(path: Path?): Path? = path?.normalize()?.takeIf { it.isAbsolute && it != normalizedHome }

    usable(runCatching { queryXdgUserDir() }.getOrNull()?.trim()?.takeIf(String::isNotEmpty)?.let(::pathOrNull))
        ?.let { return it }

    val configHome = environment["XDG_CONFIG_HOME"]?.takeIf(String::isNotBlank)?.let(::pathOrNull)
        ?.takeIf(Path::isAbsolute)
        ?: normalizedHome.resolve(".config")
    val configured = runCatching { Files.readAllLines(configHome.resolve("user-dirs.dirs")) }.getOrNull()
        ?.firstNotNullOfOrNull { line ->
            Regex("""^\s*XDG_DOWNLOAD_DIR\s*=\s*"(.*)"\s*$""").matchEntire(line)?.groupValues?.get(1)
        }
        ?.let { value ->
            when {
                value.startsWith("\$HOME/") -> normalizedHome.resolve(value.removePrefix("\$HOME/"))
                value.startsWith("/") -> pathOrNull(value)
                else -> null
            }
        }
    usable(configured)?.let { return it }
    return normalizedHome.resolve("Downloads")
}

internal fun isDirectoryWritable(directory: Path): Boolean = runCatching {
    if (!Files.isDirectory(directory) || !Files.isWritable(directory)) return false
    val probe = Files.createTempFile(directory, ".nuvio-update-probe-", ".tmp")
    Files.delete(probe)
    true
}.getOrDefault(false)

private fun pathOrNull(value: String): Path? = runCatching { Path.of(value) }.getOrNull()

/** Host tools must not inherit the packaged runtime's private library path. */
private fun hostProcess(command: List<String>): ProcessBuilder = ProcessBuilder(command).apply {
    environment().remove("LD_LIBRARY_PATH")
    environment().remove("LD_PRELOAD")
    redirectInput(File("/dev/null"))
}

private fun findHostExecutable(name: String, environment: Map<String, String>): Path? {
    val directories = environment["PATH"].orEmpty().split(':').filter(String::isNotBlank) +
        listOf("/usr/local/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin")
    return directories.asSequence()
        .mapNotNull { pathOrNull(it)?.takeIf(Path::isAbsolute)?.resolve(name) }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}

private val systemCommandRunner = LinuxCommandRunner { command ->
    val process = hostProcess(command).redirectErrorStream(true).start()
    // Bounded: only the tail is ever shown, and an endless pipe must not hold the update hostage.
    val output = process.inputStream.bufferedReader().use { reader ->
        val tail = ArrayDeque<String>()
        reader.lineSequence().forEach { line ->
            tail.addLast(line)
            if (tail.size > 40) tail.removeFirst()
        }
        tail.joinToString("\n")
    }
    LinuxCommandOutcome(process.waitFor(), output)
}

private fun systemLinuxUpdaterEnvironment(): LinuxUpdaterEnvironment {
    val environment = System.getenv()
    val home = Path.of(System.getProperty("user.home"))
    return LinuxUpdaterEnvironment(
        format = LinuxInstallFormat.detect(environment, System.getProperty("jpackage.app-path")),
        environment = environment,
        httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(60))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
        findExecutable = { findHostExecutable(it, environment) },
        runCommand = systemCommandRunner,
        downloadsDirectory = {
            linuxDownloadsDirectory(environment, home) {
                findHostExecutable("xdg-user-dir", environment)?.let { tool ->
                    val process = hostProcess(listOf(tool.toString(), "DOWNLOAD"))
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start()
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        null
                    } else {
                        process.inputStream.bufferedReader().use { it.readText() }.takeIf { process.exitValue() == 0 }
                    }
                }
            }
        },
        inPlaceEnabled = AppUpdaterPlatform::isInPlaceUpdateEnabled,
        relaunch = LinuxDesktopRelaunch::relaunch,
        flatpakUpdater = systemFlatpakPortalUpdater,
        flatpakInfo = ::readFlatpakInfo,
        relaunchLatestFlatpak = { LinuxDesktopRelaunch.relaunch(latestFlatpak = true) },
        openPath = { path ->
            val opener = findHostExecutable("xdg-open", environment)
                ?: error("xdg-open is not available")
            hostProcess(listOf(opener.toString(), path.toString()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        },
    )
}
