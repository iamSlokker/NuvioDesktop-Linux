package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.StartupReadySignal
import com.nuvio.app.core.i18n.localizedString
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.updater.LinuxInstallFormat
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import nuvio.composeapp.generated.resources.*

/*
 * The AppImage's private WebKit refuses to run its WebProcess unsandboxed, by design. Where unprivileged
 * user namespaces are blocked (AppArmor's default on Ubuntu 23.10 and later, some hardened kernels) the
 * bundled Bubblewrap cannot start, the WebProcess dies with it, and the player's HUD is just empty with
 * nothing said anywhere. The fallback stays closed; this only finds out early and tells the user.
 *
 * The probe uses the bundled helper with the namespaces WebKit asks for, on a read-only view of the
 * host. It starts nothing but `true`. DEB and Flatpak do not come here: they use their distribution's
 * WebKit, which has its own handling.
 */

internal sealed interface HudSandboxProbe {
    data object Usable : HudSandboxProbe

    /** The check itself could not run (no helper, or it hung); that says nothing about the HUD. */
    data class Inconclusive(val detail: String) : HudSandboxProbe
    data class Blocked(val detail: String) : HudSandboxProbe
}

internal object LinuxAppImageHudSandbox {
    private val log = Logger.withTag("HudSandbox")
    private const val ProbeTimeoutSeconds = 10L
    private const val MaxDetailLength = 300

    internal fun probeCommand(helper: Path, trueBinary: String): List<String> = listOf(
        helper.toString(),
        "--ro-bind", "/", "/",
        "--proc", "/proc",
        "--dev", "/dev",
        "--unshare-all",
        "--die-with-parent",
        // The launcher's private library path is for the helper, not for a host program.
        "--unsetenv", "LD_LIBRARY_PATH",
        trueBinary,
    )

    /** lib/nuvio in the image, whose helper is bin/nuvio-bwrap. */
    internal fun helperFor(executable: String?): Path? =
        executable?.takeIf(String::isNotBlank)?.let { Path.of(it).parent?.parent?.resolve("bin/nuvio-bwrap") }

    internal fun probe(helper: Path?, isExecutable: (Path) -> Boolean = { Files.isExecutable(it) }, trueBinary: String = systemTrue()): HudSandboxProbe {
        if (helper == null || !isExecutable(helper)) return HudSandboxProbe.Inconclusive("no bundled helper at $helper")
        return runCatching {
            val process = ProcessBuilder(probeCommand(helper, trueBinary))
                .redirectInput(ProcessBuilder.Redirect.from(java.io.File("/dev/null")))
                .redirectErrorStream(true)
                .start()
            if (!process.waitFor(ProbeTimeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return@runCatching HudSandboxProbe.Inconclusive("the helper did not finish in ${ProbeTimeoutSeconds}s")
            }
            val output = process.inputStream.readNBytes(4096).toString(Charsets.UTF_8).trim().take(MaxDetailLength)
            if (process.exitValue() == 0) {
                HudSandboxProbe.Usable
            } else {
                HudSandboxProbe.Blocked(output.ifBlank { "exit code ${process.exitValue()}" })
            }
        }.getOrElse { error -> HudSandboxProbe.Inconclusive(error.message ?: error.toString()) }
    }

    private fun systemTrue(): String = listOf("/usr/bin/true", "/bin/true").firstOrNull { Files.isExecutable(Path.of(it)) } ?: "/usr/bin/true"

    /** Off the startup path; the verdict is shown once Home is up, where a toast can be read. */
    fun checkInBackground() {
        if (DesktopHostOs.current != DesktopHostOs.LINUX) return
        val executable = System.getProperty("jpackage.app-path")
        if (LinuxInstallFormat.detect(System.getenv(), executable) != LinuxInstallFormat.AppImage) return
        Thread({
            when (val result = probe(helperFor(executable))) {
                HudSandboxProbe.Usable -> log.i { "WebKit sandbox helper works" }
                is HudSandboxProbe.Inconclusive -> log.w { "WebKit sandbox not checked: ${result.detail}" }
                is HudSandboxProbe.Blocked -> {
                    log.e { "WebKit sandbox helper is blocked, so the HUD cannot start: ${result.detail}" }
                    StartupReadySignal.onHomeReady { tell(result.detail) }
                }
            }
        }, "nuvio-hud-sandbox-probe").apply { isDaemon = true }.start()
    }

    private fun tell(detail: String) {
        NuvioToastController.show(
            message = localizedString(Res.string.linux_appimage_hud_blocked, detail),
            durationMillis = 30_000L,
            title = localizedString(Res.string.linux_appimage_hud_blocked_title),
        )
    }
}
