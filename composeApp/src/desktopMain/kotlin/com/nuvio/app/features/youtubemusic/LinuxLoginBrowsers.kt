package com.nuvio.app.features.youtubemusic

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

internal fun linuxLoginHost(): Boolean = System.getProperty("os.name").equals("Linux", ignoreCase = true)

/**
 * A browser to sign in with on Linux. [profileParent] is where its temporary profile has to live:
 * a Snap or Flatpak browser has its own /tmp and refuses most of the home directory, so the profile
 * goes in the one folder it can write and the host can still read. Null means the system's
 * temporary directory.
 */
internal data class LinuxLoginBrowser(
    val executable: String,
    val firefox: Boolean = false,
    val profileParent: Path? = null,
) {
    fun createProfile(): Path {
        val parent = profileParent ?: return Files.createTempDirectory("nuvio-ytmusic-login")
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, "nuvio-ytmusic-login")
    }

    /** The sign-in profile kept for renewals, when this browser needs it in its own folder. */
    fun keptProfile(slot: String): Path? = profileParent?.resolve("nuvio-youtube-browser-profile-$slot")
}

/*
 * fork:linux
 *
 * The Chromium family comes first because its session reader is the one the Windows version has
 * always used. Firefox is last: it has no DevTools protocol any more, so its cookies come through
 * WebDriver BiDi, which only the headless second step ever opens.
 */
internal object LinuxLoginBrowsers {
    private val chromiumNames = listOf(
        "google-chrome", "google-chrome-stable", "google-chrome-beta", "google-chrome-unstable",
        "microsoft-edge", "microsoft-edge-stable", "microsoft-edge-beta", "microsoft-edge-dev",
        "brave-browser", "brave-browser-stable", "brave-browser-beta", "brave-browser-nightly", "brave",
        "chromium", "chromium-browser", "ungoogled-chromium",
        "vivaldi", "vivaldi-stable", "opera",
    )
    private val firefoxNames = listOf("firefox", "firefox-esr", "firefox-developer-edition", "librewolf")
    private val chromiumFlatpaks = listOf(
        "com.google.Chrome", "com.google.ChromeDev", "com.microsoft.Edge", "com.brave.Browser",
        "org.chromium.Chromium", "io.github.ungoogled_software.ungoogled_chromium", "com.vivaldi.Vivaldi", "com.opera.Opera",
    )
    private val firefoxFlatpaks = listOf("org.mozilla.firefox", "io.gitlab.librewolf-community")
    private val bidiEndpoint = Regex("WebDriver BiDi listening on (ws://\\S+)")

    fun find(): LinuxLoginBrowser? = hostCandidates().firstOrNull { Files.isExecutable(Path.of(it.executable)) }

    /** The first Chromium-family browser, for what needs the DevTools protocol (BotGuard). */
    fun findChromium(): LinuxLoginBrowser? =
        hostCandidates().firstOrNull { !it.firefox && Files.isExecutable(Path.of(it.executable)) }

    /** A throwaway profile [executable] can write to, or null when the system's temporary directory will do. */
    fun throwawayProfile(executable: String, prefix: String): Path? {
        val parent = hostCandidates().firstOrNull { it.executable == executable }?.profileParent ?: return null
        Files.createDirectories(parent)
        return Files.createTempDirectory(parent, prefix)
    }

    private fun hostCandidates(): List<LinuxLoginBrowser> = candidates(
        path = System.getenv("PATH").orEmpty().split(java.io.File.pathSeparatorChar).filter(String::isNotBlank),
        home = Path.of(System.getProperty("user.home")),
        dataHome = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank)?.let { Path.of(it) },
    )

    /** Every place a browser could be, best first; which of them exist is the caller's question. */
    fun candidates(path: List<String>, home: Path, dataHome: Path? = null): List<LinuxLoginBrowser> {
        val flatpakRoots = listOf(
            (dataHome ?: home.resolve(".local/share")).resolve("flatpak/exports/bin"),
            Path.of("/var/lib/flatpak/exports/bin"),
        )
        // A Snap's launcher is on PATH under /snap/bin; list it even when PATH leaves that out.
        val directories = (path + "/snap/bin").distinct().map { Path.of(it) }.filter { it !in flatpakRoots }
        fun onPath(names: List<String>, firefox: Boolean) = names.flatMap { name ->
            directories.map { directory ->
                val snap = directory.startsWith("/snap/bin")
                LinuxLoginBrowser(directory.resolve(name).toString(), firefox,
                    if (snap) home.resolve("snap/$name/common") else null)
            }
        }
        fun flatpaks(ids: List<String>, firefox: Boolean) = ids.flatMap { id ->
            flatpakRoots.map { root ->
                LinuxLoginBrowser(root.resolve(id).toString(), firefox, home.resolve(".var/app/$id/cache"))
            }
        }
        return onPath(chromiumNames, false) + flatpaks(chromiumFlatpaks, false) +
            onPath(firefoxNames, true) + flatpaks(firefoxFlatpaks, true)
    }

    private val startedBrowsers = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    private val exitHook by lazy {
        Runtime.getRuntime().addShutdownHook(Thread({ startedBrowsers.forEach(::destroyTree) }, "linux-login-browser-exit"))
    }

    /** Stops [process] when the app exits: a headless browser does not end by itself, and would hold the kept profile. */
    fun stopAtExit(process: Process) {
        exitHook
        startedBrowsers += process
    }

    fun stopped(process: Process) {
        startedBrowsers -= process
    }

    private fun destroyTree(process: Process) {
        runCatching { process.descendants().forEach { it.destroyForcibly() } }
        process.destroyForcibly()
    }

    /**
     * Clears [profile] of a browser this app left running or a lock it left behind, after a crash. Only one
     * Nuvio runs at a time, and a second browser on the same profile would fight the first over its cookies.
     * The lock goes too: it names a process id, which inside a Flatpak browser's sandbox can be alive again.
     */
    fun clearLeftovers(profile: Path) {
        val path = profile.toString()
        val leftovers = ProcessHandle.allProcesses()
            .filter { handle -> handle.info().commandLine().orElse("").contains(path) }
            .toList()
        leftovers.forEach { it.destroyForcibly() }
        leftovers.forEach { runCatching { it.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS) } }
        Files.deleteIfExists(profile.resolve("SingletonLock"))
    }

    /** The same for throwaway profiles named [prefix], when none of this run is open: stops their browsers and removes them. */
    fun clearThrowaways(executable: String, prefix: String) {
        val leftovers = ProcessHandle.allProcesses().filter { handle ->
            val line = handle.info().commandLine().orElse("")
            "--user-data-dir=" in line && prefix in line
        }.toList()
        leftovers.forEach { it.destroyForcibly() }
        leftovers.forEach { runCatching { it.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS) } }
        val parent = hostCandidates().firstOrNull { it.executable == executable }?.profileParent
            ?: Path.of(System.getProperty("java.io.tmpdir"))
        val stale = runCatching {
            Files.list(parent).use { list -> list.filter { it.fileName.toString().startsWith(prefix) }.toList() }
        }.getOrDefault(emptyList())
        stale.forEach { profile ->
            runCatching {
                Files.walk(profile).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
            }
        }
    }

    /** A first run with no welcome tab or default-browser question over the sign-in page. */
    fun prepareFirefoxProfile(profile: Path) {
        Files.writeString(profile.resolve("user.js"), listOf(
            "browser.shell.checkDefaultBrowser" to "false",
            "browser.aboutwelcome.enabled" to "false",
            "datareporting.policy.dataSubmissionEnabled" to "false",
            "toolkit.telemetry.reportingpolicy.firstRun" to "false",
            "browser.startup.homepage_override.mstone" to "\"ignore\"",
        ).joinToString("") { (key, value) -> "user_pref(\"$key\", $value);\n" })
    }

    /** The visible window: no debugging port, no automation flag, like Chromium's. */
    fun firefoxSignInCommand(browser: String, profile: Path, url: String): List<String> =
        listOf(browser, "--no-remote", "--new-instance", "--profile", profile.toString(), url)

    /** The same profile, headless, with BiDi on a port of its choosing, used only to read cookies. */
    fun firefoxCookieReadCommand(browser: String, profile: Path, startUrl: String = "about:blank"): List<String> = listOf(
        browser, "--headless", "--no-remote", "--new-instance", "--profile", profile.toString(),
        "--remote-debugging-port", "0", startUrl,
    )

    fun bidiEndpointIn(line: String): String? = bidiEndpoint.find(line)?.groupValues?.get(1)

    /** Reads [process]'s output until it names its socket; keeps draining afterwards so it never blocks. */
    fun awaitBidiEndpoint(process: Process, timeoutMs: Long): String? {
        val found = java.util.concurrent.CompletableFuture<String?>()
        kotlin.concurrent.thread(isDaemon = true, name = "Nuvio-YouTubeLogin-Output") {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    bidiEndpointIn(line)?.let { found.complete(it) }
                }
            }
            found.complete(null)
        }
        return runCatching { found.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
    }

    /** BiDi wraps each cookie's value as `{type, value}`; the header builder takes DevTools' plain string. */
    fun plainBidiCookies(cookies: JsonArray): JsonArray = JsonArray(cookies.map { element ->
        val cookie = element.jsonObject
        val value = (cookie["value"] as? JsonObject)?.get("value")?.jsonPrimitive?.contentOrNull
        if (value == null) cookie else JsonObject(cookie + ("value" to JsonPrimitive(value)))
    })
}
