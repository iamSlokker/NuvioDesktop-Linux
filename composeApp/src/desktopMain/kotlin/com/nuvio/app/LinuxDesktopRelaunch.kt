package com.nuvio.app

import com.nuvio.app.core.build.LinuxHostProcess
import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/** Linux package wrappers establish the native runtime; never restart the bare jpackage binary. */
internal object LinuxDesktopRelaunch {
    internal data class Launcher(
        val path: Path,
        val flatpak: Boolean = false,
        val latestFlatpak: Boolean = false,
        /** Set for a dev run, whose start is a JVM command line rather than a package launcher. */
        val explicitCommand: List<String>? = null,
    )

    /**
     * [latestFlatpak] starts the newest deployed version in a new sandbox (after a Flatpak update);
     * a plain restart inside this sandbox would keep running the old deployment.
     */
    fun relaunch(latestFlatpak: Boolean = false): Boolean {
        val environment = System.getenv()
        val packaged = resolveLauncher(System.getProperty("jpackage.app-path"), environment)
            ?.let { if (latestFlatpak) latestFlatpakLauncher(it) ?: return false else it }
        val launcher = packaged ?: devRunLauncher() ?: return false
        return start(
            launcher = launcher,
            directory = DesktopStorage.rootDir,
            // A packaged launcher sets its own working directory; a dev run expects the one Gradle
            // gave it, where build/ and the local native binaries it loads are relative to.
            workingDirectory = if (packaged == null) currentWorkingDirectory() else null,
            parentPid = ProcessHandle.current().pid(),
            environment = environment,
            properties = PreservedProperties.mapNotNull { key -> System.getProperty(key)?.let { key to it } }.toMap(),
            startProcess = { it.start() },
            requestExit = ::exitSoon,
        )
    }

    /** The directory this process was started in; the dev relaunch runs from the same one. */
    private fun currentWorkingDirectory(): Path? = runCatching { Path.of(System.getProperty("user.dir")) }
        .getOrNull()
        ?.takeIf { Files.isDirectory(it) }

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
        // The runtime wrapper is bin/nuvio-desktop: beside jpackage's bin/nuvio, or one level up from
        // a native image's lib/nuvio (NativeImageSupport points jpackage.app-path at the executable).
        return packaged.parent?.resolveSibling("bin")?.resolve("nuvio-desktop")
            ?.takeIf { it.isAbsolute && executable(it) }?.let(::Launcher)
    }

    internal fun latestFlatpakLauncher(
        launcher: Launcher,
        executable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    ): Launcher? = launcher.takeIf { it.flatpak && executable(FlatpakSpawn) }?.copy(latestFlatpak = true)

    /**
     * An unpackaged start (a Gradle `run`, or any `java -cp` launch) has no package launcher, so
     * [resolveLauncher] finds nothing and an account switch used to close the app for good. Rebuild
     * the same JVM start instead: the class path, the main class, and every JVM input argument this
     * process was given, so the new copy runs exactly as this one did.
     */
    internal fun devRunLauncher(
        javaHome: String? = System.getProperty("java.home"),
        classPath: String? = System.getProperty("java.class.path"),
        mainClass: String? = devMainClass(),
        inputArguments: List<String> = JvmInputArguments,
        executable: (Path) -> Boolean = { Files.isRegularFile(it) && Files.isExecutable(it) },
    ): Launcher? {
        val java = javaHome?.takeIf(String::isNotBlank)
            ?.let { Path.of(it).resolve("bin").resolve("java") }
            ?: return null
        if (!java.isAbsolute || !executable(java)) return null
        return devJavaCommand(java, inputArguments, classPath, mainClass)
            ?.let { Launcher(java, explicitCommand = it) }
    }

    /**
     * `<java> <jvm args> -cp <class path> <main class>`. A class path already present in
     * [inputArguments] is dropped first: the JVM takes the last one, and the one this process is
     * running from is the one the new copy has to inherit.
     */
    internal fun devJavaCommand(
        java: Path,
        inputArguments: List<String>,
        classPath: String?,
        mainClass: String?,
    ): List<String>? {
        val cp = classPath?.takeIf(String::isNotBlank) ?: return null
        val main = mainClass?.takeIf(String::isNotBlank) ?: return null
        // Linux caps a single argument at 128 KB and the whole line at ARG_MAX (commonly 2 MB).
        if (cp.length > MaxInlineClassPath) return null
        val kept = mutableListOf<String>()
        var index = 0
        while (index < inputArguments.size) {
            val argument = inputArguments[index]
            when {
                argument in ClassPathFlags -> index += 2
                ClassPathFlags.any { argument.startsWith("$it=") } -> index += 1
                else -> {
                    kept += argument
                    index += 1
                }
            }
        }
        return listOf(java.toString()) + kept + listOf("-cp", cp, main)
    }

    private val ClassPathFlags = listOf("-cp", "-classpath", "--class-path")

    /** Beyond this a dev relaunch gives up rather than build an argument Linux would reject. */
    private val MaxInlineClassPath = 100_000

    /**
     * The main class this JVM was started with, from `sun.java.command`'s first token. A source-file
     * launch (`java Foo.java`) puts a path there, which is not a class this process could restart.
     */
    private fun devMainClass(): String? = System.getProperty("sun.java.command")
        ?.trim()
        ?.substringBefore(' ')
        ?.takeIf { it.isNotBlank() && !it.endsWith(".jar") && !it.endsWith(".java") && '/' !in it }
        ?: "com.nuvio.app.MainKt"

    private val JvmInputArguments: List<String> by lazy {
        runCatching { java.lang.management.ManagementFactory.getRuntimeMXBean().inputArguments }
            .getOrDefault(emptyList())
    }

    /** The command the helper execs once this process has exited. */
    internal fun command(launcher: Launcher): List<String> = when {
        launcher.explicitCommand != null -> launcher.explicitCommand
        launcher.latestFlatpak -> listOf(FlatpakSpawn.toString(), "--latest-version", launcher.path.toString())
        else -> listOf(launcher.path.toString())
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
        workingDirectory: Path? = null,
    ): Boolean {
        val childEnvironment = childEnvironment(launcher, environment, properties) ?: return false
        val script = Files.createTempFile(directory, "relaunch-linux-", ".sh")
        try {
            Files.writeString(script, HelperScript)
            val process = ProcessBuilder(
                listOf("/bin/sh", script.toString(), parentPid.toString(), "40") + command(launcher),
            ).directory((workingDirectory ?: directory).toFile())
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
                LinuxHostProcess.AppImageRuntimeVariables.forEach(::remove)
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
