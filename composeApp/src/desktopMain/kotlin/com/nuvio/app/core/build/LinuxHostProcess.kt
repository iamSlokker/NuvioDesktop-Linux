package com.nuvio.app.core.build

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.io.File
import java.util.concurrent.TimeUnit

/** A process for a program of the host rather than of the package; only Linux changes anything. */
internal fun hostProcessBuilder(command: List<String>): ProcessBuilder = ProcessBuilder(command).apply {
    if (DesktopHostOs.current == DesktopHostOs.LINUX) LinuxHostProcess.scrub(environment())
}

/**
 * The packages point libraries, GTK modules and data directories at their own files. Host programs
 * (browsers, players, games, xdg-open) load their own, and fail with symbol errors or cannot find
 * their handlers when they inherit those paths.
 */
internal object LinuxHostProcess {
    /** The XDG_DATA_DIRS the AppImage launcher replaced; only that launcher sets it. */
    private const val HostDataDirs = "NUVIO_HOST_XDG_DATA_DIRS"
    private val log = Logger.withTag("LinuxHostProcess")

    /** Variables the AppImage launcher sets to paths inside its mount. */
    val AppImageRuntimeVariables = listOf(
        "APPIMAGE", "APPDIR", "ARGV0", "GSETTINGS_SCHEMA_DIR", "GIO_MODULE_DIR", "GIO_EXTRA_MODULES",
        "GDK_PIXBUF_MODULEDIR", "GDK_PIXBUF_MODULE_FILE", "GTK_PATH", "GTK_IM_MODULE_FILE",
        "GST_PLUGIN_SYSTEM_PATH_1_0", "GST_PLUGIN_PATH_1_0", "GST_PLUGIN_SCANNER", "GST_PLUGIN_SCANNER_1_0",
        "GST_REGISTRY", "GST_REGISTRY_1_0", "WEBKIT_EXEC_PATH", "WEBKIT_INJECTED_BUNDLE_PATH",
        "XDG_DATA_DIRS",
    )

    fun scrub(environment: MutableMap<String, String>) {
        environment.remove("LD_LIBRARY_PATH")
        environment.remove("LD_PRELOAD")
        val hostDataDirs = environment.remove(HostDataDirs) ?: return
        AppImageRuntimeVariables.forEach(environment::remove)
        environment.remove("GDK_BACKEND")
        environment.remove("WEBKIT_FORCE_SANDBOX")
        if (hostDataDirs.isNotEmpty()) environment["XDG_DATA_DIRS"] = hostDataDirs
    }

    /**
     * Hands [target] to xdg-open, which reaches browsers installed as Flatpaks and, inside the
     * sandbox, the portal. [fallback] runs when xdg-open is missing or soon reports a failure; a
     * late exit code can be the browser's own, because xdg-open may wait for it.
     */
    fun open(
        target: String,
        fallback: () -> Unit,
        start: (ProcessBuilder) -> Process = ProcessBuilder::start,
    ) {
        val startedAt = System.nanoTime()
        val process = runCatching {
            start(
                hostProcessBuilder(listOf("xdg-open", target))
                    .redirectInput(File("/dev/null"))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD),
            )
        }.getOrElse { error ->
            log.w(error) { "xdg-open could not start" }
            fallback()
            return
        }
        process.onExit().thenAccept { exited ->
            val elapsed = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt)
            if (exited.exitValue() != 0 && elapsed < FallbackWindowSeconds) {
                log.w { "xdg-open exited with ${exited.exitValue()}" }
                fallback()
            }
        }
    }

    private const val FallbackWindowSeconds = 10L
}
