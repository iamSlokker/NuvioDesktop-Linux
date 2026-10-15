package com.nuvio.app.features.updater

import com.nuvio.app.testing.LocalHttpFixture
import com.nuvio.app.testing.respond
import kotlinx.coroutines.runBlocking
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Linux updater rules and file handling. Loopback HTTP and temp directories only. */
class LinuxAppUpdaterTest {
    private lateinit var root: Path

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("nuvio-linux-updater-")
    }

    @AfterTest
    fun tearDown() {
        root.toFile().walkBottomUp().forEach { it.setWritable(true) }
        root.toFile().deleteRecursively()
    }

    // --- Repository per OS -------------------------------------------------------------------

    @Test
    fun windowsAndMacKeepTheUpstreamRepositoryRules() {
        assertNull(desktopPlatformReleaseRules("Windows 11"))
        assertNull(desktopPlatformReleaseRules("Windows 10"))
        assertNull(desktopPlatformReleaseRules("Mac OS X"))
        assertNull(desktopPlatformReleaseRules(null))
    }

    @Test
    fun linuxFollowsTheLinuxForkStableReleasesOnly() {
        val rules = assertNotNull(desktopPlatformReleaseRules("Linux"))
        assertSame(LinuxReleaseRules, rules)
        assertEquals("iamSlokker/NuvioDesktop-Linux", rules.repository)
        assertEquals("https://github.com/iamSlokker/NuvioDesktop-Linux", rules.projectUrl)
        assertFalse(rules.nightlyChannelAvailable)
    }

    // --- Release and version rules ------------------------------------------------------------

    @Test
    fun onlyPublishedVersionTagsAreStableLinuxReleases() {
        assertTrue(LinuxReleaseRules.matchesChannel(release("v1.15.0"), UpdateChannel.Stable))
        assertTrue(LinuxReleaseRules.matchesChannel(release("V1.16.2"), UpdateChannel.Stable))
        // The source-archive release, even if someone forgets to mark it a pre-release.
        assertFalse(LinuxReleaseRules.matchesChannel(release("v1.15.0-source", prerelease = true), UpdateChannel.Stable))
        assertFalse(LinuxReleaseRules.matchesChannel(release("v1.15.0-source"), UpdateChannel.Stable))
        assertFalse(LinuxReleaseRules.matchesChannel(release("v1.16.0", prerelease = true), UpdateChannel.Stable))
        assertFalse(LinuxReleaseRules.matchesChannel(release("v1.16.0").copy(draft = true), UpdateChannel.Stable))
        assertFalse(LinuxReleaseRules.matchesChannel(release("Nightly"), UpdateChannel.Stable))
        assertFalse(LinuxReleaseRules.matchesChannel(release("v1.15"), UpdateChannel.Stable))
        // No Linux nightly channel exists.
        assertFalse(LinuxReleaseRules.matchesChannel(release("v1.16.0"), UpdateChannel.Nightly))
        assertFalse(LinuxReleaseRules.matchesChannel(release("Nightly", prerelease = true), UpdateChannel.Nightly))
    }

    @Test
    fun aLinuxTagIsOfferedOnlyWhenNewerThanTheRunningVersion() {
        fun offered(tag: String, local: String) = UpdateAvailability.isOffered(
            update = appUpdate(tag),
            localVersion = local,
            installedNightlyId = null,
            runningBuild = null,
        )
        assertFalse(offered("v1.15.0", "1.15.0"))
        assertFalse(offered("v1.14.9", "1.15.0"))
        assertTrue(offered("v1.15.1", "1.15.0"))
        assertTrue(offered("v1.16.0", "1.15.0"))
        assertTrue(offered("v2.0.0", "1.15.0"))
        // A stable-stamped or nightly-stamped Linux build of the same version is still level.
        val stamped = com.nuvio.app.core.build.PackagedBuild(id = "x", channel = "nightly")
        assertFalse(UpdateAvailability.isOffered(appUpdate("v1.15.0"), "1.15.0", null, stamped))
    }

    // --- Format detection ---------------------------------------------------------------------

    @Test
    fun detectsTheInstalledFormat() {
        val appImage = root.resolve("Nuvio.AppImage").also { Files.writeString(it, "old") }
        val files = setOf(appImage)
        fun detect(env: Map<String, String>, launcher: String? = null, flatpakInfo: Boolean = false) =
            LinuxInstallFormat.detect(env, launcher) { it in files || (flatpakInfo && it == Path.of("/.flatpak-info")) }

        assertEquals(LinuxInstallFormat.AppImage, detect(mapOf("APPIMAGE" to appImage.toString())))
        // A stale or relative APPIMAGE is not an AppImage install.
        assertEquals(LinuxInstallFormat.Unknown, detect(mapOf("APPIMAGE" to root.resolve("gone.AppImage").toString())))
        assertEquals(LinuxInstallFormat.Unknown, detect(mapOf("APPIMAGE" to "Nuvio.AppImage")))
        assertEquals(LinuxInstallFormat.Flatpak, detect(mapOf("FLATPAK_ID" to "com.nuvio.media.desktop")))
        assertEquals(LinuxInstallFormat.Flatpak, detect(emptyMap(), flatpakInfo = true))
        assertEquals(LinuxInstallFormat.Deb, detect(emptyMap(), "/opt/nuvio-desktop/bin/nuvio"))
        assertEquals(LinuxInstallFormat.Unknown, detect(emptyMap(), "/opt/nuvio-desktop-other/bin/nuvio"))
        assertEquals(LinuxInstallFormat.Unknown, detect(emptyMap(), "/home/user/src/build/bin/nuvio"))
        assertEquals(LinuxInstallFormat.Unknown, detect(emptyMap(), null))
        assertTrue(LinuxInstallFormat.AppImage.updatesInApp)
        assertTrue(LinuxInstallFormat.Deb.updatesInApp)
        assertFalse(LinuxInstallFormat.Flatpak.updatesInApp)
        assertFalse(LinuxInstallFormat.Unknown.updatesInApp)
    }

    // --- Asset selection ----------------------------------------------------------------------

    @Test
    fun selectsOnlyTheAssetOfTheInstalledFormat() {
        val release = release("v1.16.0", assets = fullAssetSet("1.16.0"))
        fun pick(format: LinuxInstallFormat, arch: String = "amd64") =
            LinuxReleaseRules.selectAsset(release, format, arch)?.name

        assertEquals("NuvioDesktop-Linux-1.16.0-x86_64.AppImage", pick(LinuxInstallFormat.AppImage))
        assertEquals("NuvioDesktop-Linux-1.16.0-x86_64.deb", pick(LinuxInstallFormat.Deb))
        assertEquals("NuvioDesktop-Linux-1.16.0-x86_64.flatpak", pick(LinuxInstallFormat.Flatpak))
        assertEquals("NuvioDesktop-Linux-1.16.0-x86_64.AppImage", pick(LinuxInstallFormat.Unknown))
        assertEquals("NuvioDesktop-Linux-1.16.0-x86_64.deb", pick(LinuxInstallFormat.Deb, arch = "x86_64"))
        assertNull(pick(LinuxInstallFormat.Deb, arch = "aarch64"))
    }

    @Test
    fun ignoresSourceReleasesAndNonMatchingAssets() {
        // Assets whose name does not carry this release's exact version and arch are never taken.
        val stray = release(
            "v1.16.0",
            assets = listOf(
                asset("NuvioDesktop-Linux-1.15.0-x86_64.AppImage"),
                asset("NuvioDesktop-Linux-1.16.0-aarch64.AppImage"),
                asset("NuvioDesktop-Linux-1.16.0-x86_64.AppImage.zsync"),
                asset("nuvio-desktop_1.16.0_amd64.deb"),
                asset("NuvioDesktop-Linux-1.16.0-source.tar.gz"),
                asset("SHA256SUMS"),
                asset("Nuvio-windows-x64.zip"),
            ),
        )
        LinuxInstallFormat.entries.forEach { format -> assertNull(LinuxReleaseRules.selectAsset(stray, format, "amd64")) }
        val source = release("v1.16.0-source", prerelease = true, assets = fullAssetSet("1.16.0"))
        assertNull(LinuxReleaseRules.selectAsset(source, LinuxInstallFormat.AppImage, "amd64"))
    }

    @Test
    fun onlyExactReleaseAssetNamesAreWrittenToDisk() {
        assertTrue(LinuxReleaseRules.isAssetName("NuvioDesktop-Linux-1.16.0-x86_64.AppImage", LinuxInstallFormat.AppImage))
        assertTrue(LinuxReleaseRules.isAssetName("NuvioDesktop-Linux-1.16.0-x86_64.deb", LinuxInstallFormat.Deb))
        assertFalse(LinuxReleaseRules.isAssetName("NuvioDesktop-Linux-1.16.0-x86_64.deb", LinuxInstallFormat.AppImage))
        assertFalse(LinuxReleaseRules.isAssetName("NuvioDesktop-Linux-../../x-x86_64.deb", LinuxInstallFormat.Deb))
        assertFalse(LinuxReleaseRules.isAssetName("NuvioDesktop-Linux-1.16.0/x-x86_64.deb", LinuxInstallFormat.Deb))
        assertFalse(LinuxReleaseRules.isAssetName("Nuvio-windows-x64.zip", LinuxInstallFormat.Deb))
        assertFalse(LinuxReleaseRules.isAssetName("NuvioDesktop-Linux-1.16.0-x86_64.flatpak", LinuxInstallFormat.Unknown))
    }

    // --- AppImage replacement -----------------------------------------------------------------

    @Test
    fun verifiedAppImageReplacesTheOriginalAtomicallyAndExecutable() = withPayloadServer { url, payload ->
        val appImage = root.resolve("apps/Nuvio.AppImage").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "old build")
        }
        replaceAppImage(appImage, download(url, "NuvioDesktop-Linux-1.16.0-x86_64.AppImage", sha256(payload)))

        assertTrue(Files.readAllBytes(appImage).contentEquals(payload))
        assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(appImage))
        assertEquals(listOf("Nuvio.AppImage"), Files.list(appImage.parent).use { s -> s.map { it.fileName.toString() }.toList() })
    }

    @Test
    fun digestMismatchLeavesTheOriginalAppImageUntouched() = withPayloadServer { url, _ ->
        val appImage = root.resolve("Nuvio.AppImage").also { Files.writeString(it, "old build") }
        val result = runCatching {
            replaceAppImage(appImage, download(url, "NuvioDesktop-Linux-1.16.0-x86_64.AppImage", "0".repeat(64)))
        }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("verification failed"))
        assertEquals("old build", Files.readString(appImage))
        assertFalse(Files.exists(root.resolve(".NuvioDesktop-Linux-1.16.0-x86_64.AppImage.part")))
    }

    @Test
    fun httpFailureLeavesTheOriginalAppImageUntouched() = withPayloadServer { url, payload ->
        val appImage = root.resolve("Nuvio.AppImage").also { Files.writeString(it, "old build") }
        val result = runCatching {
            replaceAppImage(appImage, download("$url/missing", "NuvioDesktop-Linux-1.16.0-x86_64.AppImage", sha256(payload)))
        }
        assertTrue(result.isFailure)
        assertEquals("old build", Files.readString(appImage))
        assertEquals(1L, Files.list(root).use { it.count() })
    }

    @Test
    fun appImageUpdateOffersARestartOfTheReplacedFile() = withPayloadServer { url, payload ->
        val appImage = root.resolve("apps/Nuvio.AppImage").also {
            Files.createDirectories(it.parent)
            Files.writeString(it, "old build")
        }
        var relaunched = 0
        val updater = LinuxUpdater(
            environment(LinuxInstallFormat.AppImage, mapOf("APPIMAGE" to appImage.toString()), relaunch = {
                relaunched += 1
                true
            }),
        )
        val path = runBlocking {
            updater.download(url, "NuvioDesktop-Linux-1.16.0-x86_64.AppImage", sha256(payload)) { _, _ -> }
        }.getOrThrow()

        assertEquals(appImage.toRealPath().toString(), path)
        assertTrue(Files.readAllBytes(appImage).contentEquals(payload))
        assertEquals(LinuxUpdateStatus.Installed(appImage.toRealPath()), updater.status.value)
        assertEquals(UpdateInstallOutcome.REVEALED, updater.install(path).getOrThrow())
        assertEquals(0, relaunched, "The restart is offered, never forced")
        updater.restart()
        assertEquals(1, relaunched)
    }

    @Test
    fun unwritableAppImageFolderFallsBackToDownloads() = withPayloadServer { url, payload ->
        val apps = Files.createDirectories(root.resolve("apps"))
        val appImage = apps.resolve("Nuvio.AppImage").also { Files.writeString(it, "old build") }
        apps.toFile().setWritable(false)
        val downloads = root.resolve("Downloads")
        val updater = LinuxUpdater(
            environment(LinuxInstallFormat.AppImage, mapOf("APPIMAGE" to appImage.toString()), downloads = downloads),
        )
        val path = runBlocking {
            updater.download(url, "NuvioDesktop-Linux-1.16.0-x86_64.AppImage", sha256(payload)) { _, _ -> }
        }.getOrThrow()

        val saved = downloads.resolve("NuvioDesktop-Linux-1.16.0-x86_64.AppImage")
        assertEquals(saved.toString(), path)
        assertTrue(Files.isExecutable(saved))
        assertEquals("old build", Files.readString(appImage))
        assertEquals(LinuxUpdateStatus.SavedForManualInstall(LinuxInstallFormat.AppImage, saved), updater.status.value)
    }

    @Test
    fun missingDigestFailsClosedBeforeAnyRequest() {
        LocalHttpFixture().use { http ->
            http.route("/") { exchange, _ -> exchange.respond(200, ByteArray(4), "application/octet-stream") }
            http.start()
            val updater = LinuxUpdater(environment(LinuxInstallFormat.Deb))
            val result = runBlocking {
                updater.download("${http.url}/asset", "NuvioDesktop-Linux-1.16.0-x86_64.deb", null) { _, _ -> }
            }
            assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("SHA-256"))
            assertTrue(http.drainRequests().isEmpty())
            assertEquals(LinuxUpdateStatus.Idle, updater.status.value)
        }
    }

    @Test
    fun flatpakAndUnknownInstallsNeverDownload() {
        for (format in listOf(LinuxInstallFormat.Flatpak, LinuxInstallFormat.Unknown)) {
            val result = runBlocking {
                LinuxUpdater(environment(format)).download(
                    "http://127.0.0.1:9/never",
                    "NuvioDesktop-Linux-1.16.0-x86_64.flatpak",
                    "a".repeat(64),
                ) { _, _ -> }
            }
            assertTrue(result.isFailure)
        }
    }

    @Test
    fun aWindowsZipIsRefusedOnLinux() {
        val result = runBlocking {
            LinuxUpdater(environment(LinuxInstallFormat.AppImage)).download(
                "http://127.0.0.1:9/never",
                "Nuvio-windows-x64.zip",
                "a".repeat(64),
            ) { _, _ -> }
        }
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("not a Linux"))
    }

    // --- DEB ----------------------------------------------------------------------------------

    @Test
    fun debIsVerifiedIntoDownloadsAndInstalledThroughPolkit() = withPayloadServer { url, payload ->
        val downloads = root.resolve("Downloads")
        val commands = mutableListOf<List<String>>()
        val updater = LinuxUpdater(
            environment(
                LinuxInstallFormat.Deb,
                downloads = downloads,
                tools = setOf("pkexec", "apt-get", "dpkg"),
                runner = { command -> commands += command; LinuxCommandOutcome(0) },
            ),
        )
        val path = runBlocking {
            updater.download(url, "NuvioDesktop-Linux-1.16.0-x86_64.deb", sha256(payload)) { _, _ -> }
        }.getOrThrow()

        val saved = downloads.resolve("NuvioDesktop-Linux-1.16.0-x86_64.deb")
        assertEquals(saved.toString(), path)
        assertTrue(Files.readAllBytes(saved).contentEquals(payload))
        assertFalse(Files.isExecutable(saved))
        assertEquals(
            listOf(
                listOf("/usr/bin/pkexec", "/usr/bin/apt-get", "install", "-y", saved.toString()),
            ),
            commands,
        )
        assertEquals(LinuxUpdateStatus.Installed(saved), updater.status.value)
        assertEquals(UpdateInstallOutcome.REVEALED, updater.install(path).getOrThrow())
    }

    @Test
    fun debFallsBackToDpkgOnlyWithoutAptGet() {
        val deb = root.resolve("NuvioDesktop-Linux-1.16.0-x86_64.deb")
        val (command, missing) = linuxDebInstallCommand(deb) { name ->
            if (name == "apt-get") null else Path.of("/usr/bin/$name")
        }
        assertEquals(listOf("/usr/bin/pkexec", "/usr/bin/dpkg", "-i", deb.toString()), command)
        assertNull(missing)
        assertEquals(null to "pkexec", linuxDebInstallCommand(deb) { name -> Path.of("/usr/bin/$name").takeIf { name != "pkexec" } })
        assertEquals(null to "apt-get", linuxDebInstallCommand(deb) { name -> Path.of("/usr/bin/$name").takeIf { name == "pkexec" } })
    }

    @Test
    fun cancelledOrFailedPolkitInstallKeepsTheVerifiedPackage() = withPayloadServer { url, payload ->
        for ((exitCode, expected) in listOf(
            126 to DebInstallResult.Cancelled,
            127 to DebInstallResult.Cancelled,
            100 to DebInstallResult.Failed(100, "E: broken"),
        )) {
            val downloads = root.resolve("Downloads-$exitCode")
            val updater = LinuxUpdater(
                environment(
                    LinuxInstallFormat.Deb,
                    downloads = downloads,
                    tools = setOf("pkexec", "apt-get"),
                    runner = { LinuxCommandOutcome(exitCode, "E: broken") },
                ),
            )
            runBlocking { updater.download(url, "NuvioDesktop-Linux-1.16.0-x86_64.deb", sha256(payload)) { _, _ -> } }
                .getOrThrow()
            val saved = downloads.resolve("NuvioDesktop-Linux-1.16.0-x86_64.deb")
            assertTrue(Files.isRegularFile(saved))
            assertEquals(LinuxUpdateStatus.SavedForManualInstall(LinuxInstallFormat.Deb, saved, expected), updater.status.value)
        }
    }

    @Test
    fun missingPkexecOrDisabledAutoInstallNeverRunsACommand() = withPayloadServer { url, payload ->
        val runs = mutableListOf<List<String>>()
        val noPkexec = LinuxUpdater(
            environment(LinuxInstallFormat.Deb, downloads = root.resolve("a"), tools = setOf("apt-get"), runner = {
                runs += it
                LinuxCommandOutcome(0)
            }),
        )
        runBlocking { noPkexec.download(url, "NuvioDesktop-Linux-1.16.0-x86_64.deb", sha256(payload)) { _, _ -> } }.getOrThrow()
        val status = assertIs<LinuxUpdateStatus.SavedForManualInstall>(noPkexec.status.value)
        assertEquals(DebInstallResult.Unavailable("pkexec"), status.reason)

        val manual = LinuxUpdater(
            environment(LinuxInstallFormat.Deb, downloads = root.resolve("b"), inPlace = false, runner = {
                runs += it
                LinuxCommandOutcome(0)
            }),
        )
        runBlocking { manual.download(url, "NuvioDesktop-Linux-1.16.0-x86_64.deb", sha256(payload)) { _, _ -> } }.getOrThrow()
        assertNull(assertIs<LinuxUpdateStatus.SavedForManualInstall>(manual.status.value).reason)
        assertTrue(runs.isEmpty())
    }

    @Test
    fun aPackageChangedAfterDownloadIsNeverHandedToRoot() {
        val deb = root.resolve("NuvioDesktop-Linux-1.16.0-x86_64.deb").also { Files.writeString(it, "tampered") }
        var ran = false
        val result = runCatching {
            installDeb(deb, sha256("original".toByteArray()), { Path.of("/usr/bin/$it") }) {
                ran = true
                LinuxCommandOutcome(0)
            }
        }
        assertTrue(result.isFailure)
        assertFalse(ran)
    }

    // --- Downloads folder ---------------------------------------------------------------------

    @Test
    fun resolvesTheXdgDownloadsFolder() {
        val home = Files.createDirectories(root.resolve("home"))
        assertEquals(Path.of("/data/dl"), linuxDownloadsDirectory(emptyMap(), home) { "/data/dl\n" })
        // xdg-user-dir answers $HOME for an unconfigured folder.
        assertEquals(home.resolve("Downloads"), linuxDownloadsDirectory(emptyMap(), home) { "$home\n" })
        assertEquals(home.resolve("Downloads"), linuxDownloadsDirectory(emptyMap(), home) { null })
        val config = Files.createDirectories(root.resolve("config"))
        Files.writeString(config.resolve("user-dirs.dirs"), "# comment\nXDG_DOWNLOAD_DIR=\"\$HOME/Transferências\"\n")
        assertEquals(
            home.resolve("Transferências"),
            linuxDownloadsDirectory(mapOf("XDG_CONFIG_HOME" to config.toString()), home) { error("no tool") },
        )
    }

    // --- Fixtures -----------------------------------------------------------------------------

    private fun environment(
        format: LinuxInstallFormat,
        env: Map<String, String> = emptyMap(),
        downloads: Path = root.resolve("Downloads"),
        tools: Set<String> = emptySet(),
        inPlace: Boolean = true,
        runner: LinuxCommandRunner = LinuxCommandRunner { error("No command may run in this test") },
        relaunch: () -> Boolean = { error("No relaunch in this test") },
    ) = LinuxUpdaterEnvironment(
        format = format,
        environment = env,
        httpClient = HttpClient.newHttpClient(),
        findExecutable = { name -> Path.of("/usr/bin/$name").takeIf { name in tools } },
        runCommand = runner,
        downloadsDirectory = { downloads },
        inPlaceEnabled = { inPlace },
        relaunch = relaunch,
        openPath = { error("Nothing may be opened in this test") },
    )

    private fun withPayloadServer(test: (url: String, payload: ByteArray) -> Unit) {
        val payload = ByteArray(256 * 1024) { (it * 31 % 251).toByte() }
        LocalHttpFixture().use { http ->
            http.route("/asset") { exchange, request ->
                if (request.path == "/asset") {
                    exchange.respond(200, payload, "application/octet-stream")
                } else {
                    exchange.respond(404, ByteArray(0))
                }
            }
            http.start()
            test("${http.url}/asset", payload)
        }
    }

    private fun download(url: String, name: String, sha: String) =
        VerifiedDownload(HttpClient.newHttpClient(), url, name, sha) { _, _ -> }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun asset(name: String) = GitHubAssetDto(
        name = name,
        browserDownloadUrl = "https://github.com/iamSlokker/NuvioDesktop-Linux/releases/download/x/$name",
        digest = "sha256:" + "a".repeat(64),
    )

    private fun fullAssetSet(version: String) = listOf(
        asset("NuvioDesktop-Linux-$version-x86_64.deb"),
        asset("NuvioDesktop-Linux-$version-x86_64.flatpak"),
        asset("NuvioDesktop-Linux-$version-x86_64.AppImage"),
        asset("SHA256SUMS"),
    )

    private fun release(tag: String, prerelease: Boolean = false, assets: List<GitHubAssetDto> = emptyList()) =
        GitHubReleaseDto(tagName = tag, name = tag, prerelease = prerelease, targetCommitish = "linux", assets = assets)

    private fun appUpdate(tag: String) = AppUpdate(
        channel = UpdateChannel.Stable,
        tag = tag,
        title = tag,
        notes = "",
        releaseUrl = null,
        assetName = "NuvioDesktop-Linux-1.16.0-x86_64.deb",
        assetUrl = "https://example.invalid/asset",
        assetSizeBytes = null,
    )
}
