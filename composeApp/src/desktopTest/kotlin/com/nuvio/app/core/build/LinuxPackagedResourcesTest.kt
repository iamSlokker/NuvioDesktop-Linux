package com.nuvio.app.core.build

import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the shared resolver in fresh JVMs without opening windows or loading native code. */
class LinuxPackagedResourcesTest {
    @Test
    fun linuxLauncherResolvesBundledModelsNoticesAndMovedSetupImages() = withHeadlessFixture { root ->
        runHeadlessProbe(root, LinuxPackagedResourcesProbe::class.java, "linux-classpath")
    }

    @Test
    fun installedLooseResourceKeepsUpstreamPrecedenceAndClasspathFallback() = withHeadlessFixture { root ->
        runHeadlessProbe(root, LinuxPackagedResourcesProbe::class.java, "installed-loose")
    }

    @Test
    fun packagedLauncherCannotReadResourcesFromItsWorkingDirectory() = withHeadlessFixture { root ->
        runHeadlessProbe(root, LinuxPackagedResourcesProbe::class.java, "packaged-no-source")
    }

    @Test
    fun developmentRunRetainsBothSourceResourceRoots() = withHeadlessFixture { root ->
        runHeadlessProbe(root, LinuxPackagedResourcesProbe::class.java, "dev-source")
    }
}

object LinuxPackagedResourcesProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        System.clearProperty("nuvio.dev.localNatives")
        when (args[1]) {
            "linux-classpath" -> {
                // Linux jpackage places the launcher in bin, but keeps these resources in
                // the app JAR. It must not depend on Windows' sibling resources directory.
                System.setProperty("jpackage.app-path", root.resolve("installed/bin/nuvio").toString())
                assertFalse(DesktopDevRun.allowsLocalNativeBuilds)
                listOf(
                    "backdrop-framing/u2netp.onnx",
                    "backdrop-framing/yunet.onnx",
                    "backdrop-framing/NOTICE.txt",
                    "backdrop-framing/LICENSE-U2NET.txt",
                    "backdrop-framing/LICENSE-YUNET.txt",
                    "backdrop-framing/LICENSE-ONNXRUNTIME.txt",
                    "anime-list-mini.json",
                    "anime-mapping/NOTICE.txt",
                    "anime-mapping/PROVENANCE.json",
                    "anime-mapping/ODBL-1.0.txt",
                    "anime-mapping/DBCL-1.0.txt",
                    "player-shaders/LICENSE.md",
                    "setup/setup_mode_basic.webp",
                    "setup/setup_mode_adaptive.webp",
                    "setup/setup_mode_adaptive_ambient.webp",
                    "setup/setup_mode_tv.webp",
                ).forEach { path ->
                    val expected = assertNotNull(javaClass.getResourceAsStream("/$path"), path).use { it.readBytes() }
                    val actual = assertNotNull(PackagedResources.open(path), path).use { it.readBytes() }
                    assertTrue(actual.isNotEmpty(), path)
                    assertContentEquals(expected, actual, path)
                }
            }
            "installed-loose" -> {
                // Use the existing upstream layout, without pretending to run Windows.
                val install = root.resolve("installed")
                System.setProperty("jpackage.app-path", install.resolve("Nuvio.exe").toString())
                val relative = "backdrop-framing/NOTICE.txt"
                write(install.resolve("resources/$relative"), "installed fixture")
                assertEquals("installed fixture", PackagedResources.open("/$relative")?.bufferedReader()?.use { it.readText() })
                Files.delete(install.resolve("resources/$relative"))
                val expected = assertNotNull(javaClass.getResourceAsStream("/$relative")).use { it.readBytes() }
                assertContentEquals(expected, assertNotNull(PackagedResources.open(relative)).use { it.readBytes() })
            }
            "packaged-no-source" -> {
                System.setProperty("jpackage.app-path", root.resolve("installed/bin/nuvio").toString())
                write(root.resolve("composeApp/src/desktopMain/resources/fixture-app.txt"), "untrusted cwd")
                write(root.resolve("src/desktopMain/resources/fixture-module.txt"), "untrusted cwd")
                assertFalse(DesktopDevRun.allowsLocalNativeBuilds)
                assertNull(PackagedResources.open("fixture-app.txt"))
                assertNull(PackagedResources.open("fixture-module.txt"))
                assertNull(PackagedResources.open("backdrop-framing/missing.onnx"))
            }
            "dev-source" -> {
                System.clearProperty("jpackage.app-path")
                write(root.resolve("composeApp/src/desktopMain/resources/fixture-app.txt"), "app source")
                write(root.resolve("src/desktopMain/resources/fixture-module.txt"), "module source")
                assertTrue(DesktopDevRun.allowsLocalNativeBuilds)
                assertEquals("app source", PackagedResources.open("fixture-app.txt")?.bufferedReader()?.use { it.readText() })
                assertEquals("module source", PackagedResources.open("fixture-module.txt")?.bufferedReader()?.use { it.readText() })
            }
            else -> error("Unknown scenario ${args[1]}")
        }
    }

    private fun write(path: Path, content: String) {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
