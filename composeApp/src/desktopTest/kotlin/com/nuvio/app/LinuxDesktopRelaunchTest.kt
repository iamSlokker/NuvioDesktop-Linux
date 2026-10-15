package com.nuvio.app

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Fake package paths/processes only: no Nuvio process, display, or personal storage is opened. */
class LinuxDesktopRelaunchTest {
    @BeforeTest
    fun linuxOnly() {
        assumeTrue("Linux relaunch fixtures", System.getProperty("os.name").equals("Linux", ignoreCase = true))
    }

    @Test
    fun appImageRestartsTheStableExecutableNotTheTemporaryMount() {
        val packaged = "/tmp/.mount_nuvio/usr/lib/nuvio-desktop/bin/nuvio"
        val image = "/tmp/downloads/Nuvio 'test' \$(no-shell).AppImage"
        val launcher = LinuxDesktopRelaunch.resolveLauncher(
            packaged, mapOf("APPIMAGE" to image, "APPDIR" to "/tmp/.mount_nuvio"),
        ) { it.toString() in setOf(packaged, image) }
        assertEquals(Path.of(image), launcher?.path)
        assertFalse(launcher!!.flatpak)
        assertNull(LinuxDesktopRelaunch.resolveLauncher(packaged, mapOf("APPIMAGE" to image)) {
            it.toString() == packaged
        }, "Never fall back to a disappearing mount when the original AppImage is gone")
    }

    @Test
    fun debUsesRuntimeWrapperAndFlatpakStaysInsideItsSandbox() {
        val packaged = "/opt/nuvio-desktop/bin/nuvio"
        assertEquals(
            Path.of("/opt/nuvio-desktop/bin/nuvio-desktop"),
            LinuxDesktopRelaunch.resolveLauncher(packaged, emptyMap()) { true }?.path,
        )
        assertEquals(
            LinuxDesktopRelaunch.Launcher(Path.of("/app/bin/nuvio-desktop"), flatpak = true),
            LinuxDesktopRelaunch.resolveLauncher("/app/lib/nuvio-desktop/bin/nuvio", mapOf("FLATPAK_ID" to "test")) { true },
        )
        assertNull(LinuxDesktopRelaunch.resolveLauncher(packaged, emptyMap()) { it.toString() == packaged })
    }

    @Test
    fun flatpakUpdateRestartStartsTheLatestDeploymentInANewSandbox() {
        val flatpak = LinuxDesktopRelaunch.Launcher(Path.of("/app/bin/nuvio-desktop"), flatpak = true)
        val latest = LinuxDesktopRelaunch.latestFlatpakLauncher(flatpak) { it == Path.of("/usr/bin/flatpak-spawn") }
        assertEquals(
            listOf("/usr/bin/flatpak-spawn", "--latest-version", "/app/bin/nuvio-desktop"),
            LinuxDesktopRelaunch.command(assertNotNull(latest)),
        )
        assertEquals(listOf("/app/bin/nuvio-desktop"), LinuxDesktopRelaunch.command(flatpak))
        assertNull(LinuxDesktopRelaunch.latestFlatpakLauncher(flatpak) { false }, "No flatpak-spawn, no restart")
        val deb = LinuxDesktopRelaunch.Launcher(Path.of("/opt/nuvio-desktop/bin/nuvio-desktop"))
        assertNull(LinuxDesktopRelaunch.latestFlatpakLauncher(deb) { true }, "Only a Flatpak launcher is respawned")
    }

    @Test
    fun developerAndInvalidLaunchesDoNotStartAnything() {
        assertNull(LinuxDesktopRelaunch.resolveLauncher(null, mapOf("APPIMAGE" to "/tmp/other.AppImage")) { true })
        assertNull(LinuxDesktopRelaunch.resolveLauncher("relative/nuvio", emptyMap()) { true })
        assertNull(LinuxDesktopRelaunch.resolveLauncher("/missing/nuvio", emptyMap()) { false })
    }

    @Test
    fun appImageEnvironmentPreservesProfileAndDesktopButDropsExpiredRuntimePaths() {
        val environment = mapOf(
            "APPIMAGE" to "/tmp/Nuvio.AppImage", "APPDIR" to "/tmp/.mount_old",
            "HOME" to "/tmp/disposable", "XDG_CONFIG_HOME" to "/tmp/disposable/config",
            "DISPLAY" to ":987", "WAYLAND_DISPLAY" to "wayland-test", "XDG_RUNTIME_DIR" to "/tmp/runtime",
            "PULSE_SERVER" to "unix:/tmp/pulse", "DBUS_SESSION_BUS_ADDRESS" to "unix:path=/tmp/bus",
            "LD_LIBRARY_PATH" to "/tmp/.mount_old/lib", "LD_PRELOAD" to "/tmp/injected.so",
            "GSETTINGS_SCHEMA_DIR" to "/tmp/.mount_old/schemas", "GTK_IM_MODULE_FILE" to "/tmp/old.cache",
            "GST_REGISTRY" to "/tmp/old.registry", "JAVA_TOOL_OPTIONS" to "-Dexisting.option=true",
        )
        val result = assertNotNull(LinuxDesktopRelaunch.childEnvironment(
            LinuxDesktopRelaunch.Launcher(Path.of("/tmp/Nuvio.AppImage")), environment,
            mapOf("user.home" to "/tmp/disposable home", "java.io.tmpdir" to "/tmp/private", "nuvio.desktop.dataDir" to "/tmp/account data"),
        ))
        listOf("HOME", "XDG_CONFIG_HOME", "DISPLAY", "WAYLAND_DISPLAY", "XDG_RUNTIME_DIR", "PULSE_SERVER", "DBUS_SESSION_BUS_ADDRESS")
            .forEach { assertEquals(environment[it], result[it], it) }
        listOf("APPIMAGE", "APPDIR", "LD_LIBRARY_PATH", "LD_PRELOAD", "GSETTINGS_SCHEMA_DIR", "GTK_IM_MODULE_FILE", "GST_REGISTRY")
            .forEach { assertFalse(it in result, it) }
        assertEquals(
            "-Dexisting.option=true \"-Duser.home=/tmp/disposable home\" \"-Djava.io.tmpdir=/tmp/private\" \"-Dnuvio.desktop.dataDir=/tmp/account data\"",
            result["JAVA_TOOL_OPTIONS"],
        )
    }

    @Test
    fun flatpakDriverPathsAreRestoredAtExecRatherThanInjectedIntoTheHelper() {
        val result = assertNotNull(LinuxDesktopRelaunch.childEnvironment(
            LinuxDesktopRelaunch.Launcher(Path.of("/app/bin/nuvio-desktop"), flatpak = true),
            mapOf("LD_LIBRARY_PATH" to "/app/lib/native:/usr/lib/GL/default/lib", "FLATPAK_ID" to "test"), emptyMap(),
        ))
        assertFalse("LD_LIBRARY_PATH" in result)
        assertEquals("/app/lib/native:/usr/lib/GL/default/lib", result["NUVIO_RELAUNCH_LIBRARY_PATH"])
        assertEquals("test", result["FLATPAK_ID"])
    }

    @Test
    fun helperExecRestoresFlatpakDriverPathsAndRemovesItsPrivateVariable() = temporaryDirectory { directory ->
        val wrapper = directory.resolve("flatpak-wrapper")
        val marker = directory.resolve("restored-library-path")
        wrapper.writeText(
            "#!/bin/sh\n" +
                "[ \"\${NUVIO_RELAUNCH_LIBRARY_PATH+x}\" != x ] || exit 9\n" +
                "printf '%s' \"\${LD_LIBRARY_PATH}\" > \"\${NUVIO_TEST_MARKER}\"\n",
        )
        assertTrue(wrapper.toFile().setExecutable(true, true))
        val expected = "/fixture/private-media:/fixture/graphics-extension"
        val output = directory.resolve("helper.log")
        var child: Process? = null
        try {
            assertTrue(LinuxDesktopRelaunch.start(
                LinuxDesktopRelaunch.Launcher(wrapper, flatpak = true), directory,
                // Above Linux PID_MAX_LIMIT: the fixture has no outgoing application owner.
                Int.MAX_VALUE.toLong(),
                mapOf("PATH" to "/usr/bin:/bin", "LD_LIBRARY_PATH" to expected, "NUVIO_TEST_MARKER" to marker.toString()),
                emptyMap(),
                startProcess = { builder ->
                    assertFalse("LD_LIBRARY_PATH" in builder.environment())
                    child = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start()
                },
                requestExit = {},
            ))
            val helper = assertNotNull(child)
            assertTrue(helper.waitFor(5, TimeUnit.SECONDS))
            assertEquals(0, helper.exitValue(), output.readText())
            assertEquals(expected, marker.readText())
            Files.list(directory).use { files ->
                assertFalse(files.anyMatch { it.fileName.toString().startsWith("relaunch-linux-") })
            }
        } finally {
            child?.destroyForcibly()
        }
    }

    @Test
    fun javaAcceptsThePreservedProfileOptionsIncludingSpacesAndEitherQuote() = temporaryDirectory { directory ->
        val properties = mapOf(
            "user.home" to directory.resolve("home 'quoted'").toString(),
            "java.io.tmpdir" to directory.resolve("temporary files").toString(),
            "nuvio.desktop.dataDir" to directory.resolve("account \"quoted\"").toString(),
        )
        val childEnvironment = assertNotNull(LinuxDesktopRelaunch.childEnvironment(
            LinuxDesktopRelaunch.Launcher(directory.resolve("wrapper")), emptyMap(), properties,
        ))
        val process = ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-XshowSettings:properties", "-version",
        ).redirectError(directory.resolve("java-options.log").toFile()).apply {
            environment().clear()
            environment().putAll(childEnvironment)
        }.start()
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val output = directory.resolve("java-options.log").readText()
            assertEquals(0, process.exitValue(), output)
            properties.forEach { (key, value) ->
                assertTrue(output.lineSequence().any { it.trim() == "$key = $value" }, output)
            }
        } finally {
            process.destroyForcibly()
        }
    }

    @Test
    fun processLaunchPrecedesCleanExitAndPathsRemainSeparateArguments() = temporaryDirectory { directory ->
        val launcher = LinuxDesktopRelaunch.Launcher(directory.resolve("Nuvio 'space' \$(literal).AppImage"))
        val events = mutableListOf<String>()
        var script: Path? = null
        assertTrue(LinuxDesktopRelaunch.start(
            launcher, directory, 12345, emptyMap(), emptyMap(),
            startProcess = { process ->
                events += "start"
                script = Path.of(process.command()[1])
                assertEquals(listOf("/bin/sh", script.toString(), "12345", "40", launcher.path.toString()), process.command())
                assertEquals(LinuxDesktopRelaunch.HelperScript, script!!.readText())
                assertEquals(directory.toFile(), process.directory())
            },
            requestExit = { events += "exit" },
        ))
        assertEquals(listOf("start", "exit"), events)
    }

    @Test
    fun failedProcessStartCleansHelperAndDoesNotExitTheApp() = temporaryDirectory { directory ->
        var exited = false
        assertFailsWith<IOException> {
            LinuxDesktopRelaunch.start(
                LinuxDesktopRelaunch.Launcher(directory.resolve("wrapper")), directory, 12345,
                emptyMap(), emptyMap(), startProcess = { throw IOException("fixture") }, requestExit = { exited = true },
            )
        }
        assertFalse(exited)
        Files.list(directory).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun unsafePropertyStringsCannotRestartIntoAnUnintendedProfile() = temporaryDirectory { directory ->
        for (value in listOf("/tmp/with\"both'quotes", "/tmp/line\nbreak")) {
            assertFalse(LinuxDesktopRelaunch.start(
                LinuxDesktopRelaunch.Launcher(directory.resolve("wrapper")), directory, 12345, emptyMap(),
                mapOf("nuvio.desktop.dataDir" to value), startProcess = { error("must not launch") }, requestExit = { error("must not exit") },
            ))
        }
        Files.list(directory).use { assertEquals(0L, it.count()) }
    }

    @Test
    fun helperWaitsForParentAndRemovesItselfWithoutExecutingShellSyntaxInPaths() = temporaryDirectory { directory ->
        val marker = directory.resolve("launched")
        val wrapper = directory.resolve("wrapper \$(touch should-not-exist) ' spaced")
        wrapper.writeText("#!/bin/sh\nprintf '%s' \"\${NUVIO_TEST_ARGUMENT}\" > \"\${NUVIO_TEST_MARKER}\"\n")
        assertTrue(wrapper.toFile().setExecutable(true, true))
        val script = directory.resolve("restart.sh").apply { writeText(LinuxDesktopRelaunch.HelperScript) }
        val fixtureBin = Files.createDirectory(directory.resolve("bin"))
        val sleep = fixtureBin.resolve("sleep")
        // A handshake replaces the timer, proving the real helper entered its wait loop.
        // Without that loop, no WAITING line arrives and this test fails deterministically.
        sleep.writeText("#!/bin/sh\n[ \"\${1}\" = 1 ] || exit 9\nprintf 'WAITING\\n'\nIFS= read -r release\n")
        assertTrue(sleep.toFile().setExecutable(true, true))
        val parent = ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start()
        val helper = ProcessBuilder("/bin/sh", script.toString(), parent.pid().toString(), "5", wrapper.toString())
            .directory(directory.toFile()).apply {
                environment()["PATH"] = "$fixtureBin:/usr/bin:/bin"
                environment()["NUVIO_TEST_ARGUMENT"] = "spaces 'quotes' \$(literal)"
                environment()["NUVIO_TEST_MARKER"] = marker.toString()
            }.start()
        try {
            val ready = CompletableFuture.supplyAsync { helper.inputStream.bufferedReader().readLine() }
            assertEquals("WAITING", ready.get(5, TimeUnit.SECONDS))
            assertTrue(parent.isAlive, "wait barrier must be reached while the parent is alive")
            assertFalse(marker.exists(), "launcher ran before its parent exited")
            parent.destroy()
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS))
            helper.outputStream.bufferedWriter().use { it.write("continue\n") }
            assertTrue(helper.waitFor(8, TimeUnit.SECONDS))
            assertEquals(0, helper.exitValue(), helper.errorStream.readAllBytes().decodeToString())
            assertEquals("spaces 'quotes' \$(literal)", marker.readText())
            assertFalse(script.exists())
            assertFalse(directory.resolve("should-not-exist").exists())
        } finally {
            parent.destroyForcibly()
            // Also stop a blocked fixture timer if an assertion failed before release.
            helper.descendants().forEach { it.destroyForcibly() }
            runCatching { helper.outputStream.close() }
            helper.destroyForcibly()
        }
    }

    @Test
    fun helperTimesOutAndCleansUpWithoutLaunchingASecondOwner() = temporaryDirectory { directory ->
        val script = directory.resolve("restart.sh").apply { writeText(LinuxDesktopRelaunch.HelperScript) }
        val helper = ProcessBuilder(
            "/bin/sh", script.toString(), ProcessHandle.current().pid().toString(), "0", "/must-not-run",
        ).start()
        try {
            assertTrue(helper.waitFor(5, TimeUnit.SECONDS))
            assertEquals(1, helper.exitValue())
            assertTrue(helper.errorStream.readAllBytes().decodeToString().contains("previous process did not exit"))
            assertFalse(script.exists())
        } finally {
            helper.destroyForcibly()
        }
    }

    private fun temporaryDirectory(block: (Path) -> Unit) {
        val directory = Files.createTempDirectory("nuvio-relaunch-test-")
        try {
            block(directory)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
