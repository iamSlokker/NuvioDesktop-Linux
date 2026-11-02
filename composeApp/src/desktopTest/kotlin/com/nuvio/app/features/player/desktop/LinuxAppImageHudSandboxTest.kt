package com.nuvio.app.features.player.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class LinuxAppImageHudSandboxTest {
    private fun <T> withHelper(script: String, block: (Path) -> T): T {
        val dir = createTempDirectory("nuvio-hud-probe")
        try {
            val helper = dir.resolve("nuvio-bwrap")
            Files.writeString(helper, "#!/bin/sh\n$script\n")
            Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwxr-xr-x"))
            return block(helper)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `the probe asks for the namespaces WebKit needs and runs nothing but true`() {
        val command = LinuxAppImageHudSandbox.probeCommand(Path.of("/img/bin/nuvio-bwrap"), "/usr/bin/true")
        assertEquals("/img/bin/nuvio-bwrap", command.first())
        assertEquals("/usr/bin/true", command.last())
        assertTrue("--unshare-all" in command)
        assertEquals("/", command[command.indexOf("--ro-bind") + 1])
    }

    @Test
    fun `the helper is found beside the image's executable`() {
        assertEquals(Path.of("/img/bin/nuvio-bwrap"), LinuxAppImageHudSandbox.helperFor("/img/lib/nuvio"))
        assertEquals(null, LinuxAppImageHudSandbox.helperFor(null))
        assertEquals(null, LinuxAppImageHudSandbox.helperFor(""))
    }

    @Test
    fun `a working helper is usable`() = withHelper("exit 0") { helper ->
        assertEquals(HudSandboxProbe.Usable, LinuxAppImageHudSandbox.probe(helper))
    }

    @Test
    fun `a refusal is blocked and carries the helper's own words`() =
        withHelper("echo 'bwrap: setting up uid map: Permission denied' >&2; exit 1") { helper ->
            assertEquals(
                HudSandboxProbe.Blocked("bwrap: setting up uid map: Permission denied"),
                LinuxAppImageHudSandbox.probe(helper),
            )
        }

    @Test
    fun `a silent failure still reports something`() = withHelper("exit 7") { helper ->
        assertEquals(HudSandboxProbe.Blocked("exit code 7"), LinuxAppImageHudSandbox.probe(helper))
    }

    @Test
    fun `no helper proves nothing about the HUD`() {
        assertIs<HudSandboxProbe.Inconclusive>(LinuxAppImageHudSandbox.probe(null))
        assertIs<HudSandboxProbe.Inconclusive>(LinuxAppImageHudSandbox.probe(Path.of("/nonexistent/nuvio-bwrap")))
    }
}
