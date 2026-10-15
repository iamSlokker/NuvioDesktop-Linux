package com.nuvio.app

import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Linux package wrappers establish the native runtime; never restart the bare jpackage binary. */
internal object LinuxDesktopRelaunch {
    internal data class Launcher(val path: Path, val flatpak: Boolean = false, val latestFlatpak: Boolean = false)

    /**
     * [latestFlatpak] starts the newest deployed version in a new sandbox (after a Flatpak update);
     * a plain restart inside this sandbox would keep running the old deployment.
     */
    fun relaunch(latestFlatpak: Boolean = false): Boolean {
        val environment = System.getenv()
        val launcher = resolveLauncher(System.getProperty("jpackage.app-path"), environment)
            ?.let { if (latestFlatpak) latestFlatpakLauncher(it) ?: return false else it }
            ?: return false
        return start(
            launcher = launcher,
            directory = DesktopStorage.rootDir,
            parentPid = ProcessHandle.current().pid(),
            environment = environment,
            properties = PreservedProperties.mapNotNull { key -> System.getProperty(key)?.let { key to it } }.toMap(),
            startProcess = { it.start() },
            requestExit = ::exitSoon,
        )
    }

    internal fun resolveLauncher(
        packagedLauncher: String?,
        environment: Map<String, String>,
        executable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    ): Launcher? {
        val packaged = packagedLauncher?.takeIf(String::isNotBlank)?.let(Path::of) ?: return null
        if (!packaged.isAbsolute || !executable(packaged)) return null
        val appImage = environment["APPIMAGE"]?.takeIf(String::isNotBlank)
        if (appImage != null) {
            // /tmp/.mount_... disappears when the current AppImage runtime exits.
            val path = Path.of(appImage)
            return path.takeIf { it.isAbsolute && executable(it) }?.let(::Launcher)
        }
        if (!environment["FLATPAK_ID"].isNullOrBlank()) {
            return Path.of("/app/bin/nuvio-desktop").takeIf(executable)?.let { Launcher(it, flatpak = true) }
        }
        return packaged.resolveSibling("nuvio-desktop").takeIf(executable)?.let(::Launcher)
    }

    internal fun latestFlatpakLauncher(
        launcher: Launcher,
        executable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    ): Launcher? = launcher.takeIf { it.flatpak && executable(FlatpakSpawn) }?.copy(latestFlatpak = true)

    /** The command the helper execs once this process has exited. */
    internal fun command(launcher: Launcher): List<String> = if (launcher.latestFlatpak) {
        listOf(FlatpakSpawn.toString(), "--latest-version", launcher.path.toString())
    } else {
        listOf(launcher.path.toString())
    }

    private val FlatpakSpawn: Path = Path.of("/usr/bin/flatpak-spawn")

    internal fun start(
        launcher: Launcher,
        directory: Path,
        parentPid: Long,
        environment: Map<String, String>,
        properties: Map<String, String>,
        startProcess: (ProcessBuilder) -> Unit,
        requestExit: () -> Unit,
    ): Boolean {
        val childEnvironment = childEnvironment(launcher, environment, properties) ?: return false
        val script = Files.createTempFile(directory, "relaunch-linux-", ".sh")
        try {
            Files.writeString(script, HelperScript)
            val process = ProcessBuilder(
                listOf("/bin/sh", script.toString(), parentPid.toString(), "40") + command(launcher),
            ).directory(directory.toFile())
                .redirectInput(File("/dev/null"))
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
            process.environment().clear()
            process.environment().putAll(childEnvironment)
            startProcess(process)
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(script) }
            throw error
        }
        // Use the normal application exit so stores flush and the native player is disposed.
        requestExit()
        return true
    }

    internal fun childEnvironment(
        launcher: Launcher,
        environment: Map<String, String>,
        properties: Map<String, String>,
    ): Map<String, String>? {
        val options = PreservedProperties.mapNotNull { key -> properties[key]?.let { "-D$key=$it" } }
            .map { option -> quoteJavaOption(option) ?: return null }
        return environment.toMutableMap().apply {
            // The helper is a host/runtime shell, not a private-media executable. Flatpak's
            // graphics extension search path is restored only when executing its stable wrapper.
            remove("NUVIO_RELAUNCH_LIBRARY_PATH")
            if (launcher.flatpak) get("LD_LIBRARY_PATH")?.let { put("NUVIO_RELAUNCH_LIBRARY_PATH", it) }
            remove("LD_LIBRARY_PATH")
            remove("LD_PRELOAD")
            if (!environment["APPIMAGE"].isNullOrBlank()) {
                // A new AppImage mount and wrapper recreate these absolute paths and caches.
                AppImageRuntimeVariables.forEach(::remove)
            }
            if (options.isNotEmpty()) {
                put("JAVA_TOOL_OPTIONS", (get("JAVA_TOOL_OPTIONS").orEmpty() + " " + options.joinToString(" ")).trim())
            }
        }
    }

    private fun quoteJavaOption(option: String): String? = when {
        option.any { it == '\n' || it == '\r' || it == '\u0000' } -> null
        '"' !in option -> "\"$option\""
        '\'' !in option -> "'$option'"
        else -> null // HotSpot's option string has no escape for both quote types; keep this session safe.
    }

    private val PreservedProperties = listOf("user.home", "java.io.tmpdir", "nuvio.desktop.dataDir")
    private val AppImageRuntimeVariables = listOf(
        "APPIMAGE", "APPDIR", "ARGV0", "GSETTINGS_SCHEMA_DIR", "GIO_MODULE_DIR", "GIO_EXTRA_MODULES",
        "GDK_PIXBUF_MODULEDIR", "GDK_PIXBUF_MODULE_FILE", "GTK_PATH", "GTK_IM_MODULE_FILE",
        "GST_PLUGIN_SYSTEM_PATH_1_0", "GST_PLUGIN_PATH_1_0", "GST_PLUGIN_SCANNER", "GST_PLUGIN_SCANNER_1_0",
        "GST_REGISTRY", "GST_REGISTRY_1_0", "WEBKIT_EXEC_PATH", "WEBKIT_INJECTED_BUNDLE_PATH",
        "XDG_DATA_DIRS",
    )

    // All variable input is positional/environment data. No launcher path is interpolated as shell code.
    // The helper bounds its wait, removes itself on every outcome, and holds the Flatpak sandbox
    // alive during shutdown. It does not replay a launch URL into the newly selected account.
    internal val HelperScript = """
        #!/bin/sh
        set -eu
        trap 'rm -f -- "${'$'}0"' EXIT
        trap 'exit 1' HUP INT TERM
        parent_pid=${'$'}1
        remaining=${'$'}2
        shift 2
        while kill -0 "${'$'}parent_pid" 2>/dev/null; do
            if [ "${'$'}remaining" -le 0 ]; then
                echo 'Nuvio restart cancelled: previous process did not exit in time.' >&2
                exit 1
            fi
            sleep 1
            remaining=${'$'}((remaining - 1))
        done
        rm -f -- "${'$'}0"
        trap - EXIT
        if [ "${'$'}{NUVIO_RELAUNCH_LIBRARY_PATH+x}" = x ]; then
            export LD_LIBRARY_PATH="${'$'}NUVIO_RELAUNCH_LIBRARY_PATH"
            unset NUVIO_RELAUNCH_LIBRARY_PATH
        fi
        exec "${'$'}@"
    """.trimIndent() + "\n"
}
