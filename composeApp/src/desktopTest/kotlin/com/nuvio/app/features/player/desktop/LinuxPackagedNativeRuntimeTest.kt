package com.nuvio.app.features.player.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxPackagedNativeRuntimeTest {
    @Test
    fun linuxImageFindsPrivateNativeLibrariesFromBundledJavaHome() = withImage { root ->
        val native = root.resolve("lib/native").apply { mkdirs() }
        native.resolve("libplayer_bridge.so").writeText("fixture")
        native.resolve("libmpv.so.2").writeText("fixture")
        assertEquals(native, findPackagedNativeRuntime(
            DesktopHostOs.LINUX, root.resolve("lib/runtime"), required,
        ))
    }

    @Test
    fun incompleteLinuxImageIsRejected() = withImage { root ->
        root.resolve("lib/native").apply { mkdirs() }.resolve("libplayer_bridge.so").writeText("fixture")
        assertNull(findPackagedNativeRuntime(DesktopHostOs.LINUX, root.resolve("lib/runtime"), required))
    }

    @Test
    fun missingJavaHomeCannotSelectAWorkingDirectoryRuntime() {
        assertNull(findPackagedNativeRuntime(DesktopHostOs.LINUX, null, required))
    }

    @Test
    fun macosStillDoesNotUseLinuxLayout() = withImage { root ->
        root.resolve("lib/native").apply { mkdirs() }.resolve("libplayer_bridge.so").writeText("fixture")
        assertNull(findPackagedNativeRuntime(DesktopHostOs.MACOS, root.resolve("lib/runtime"), required))
    }

    private fun withImage(block: (java.io.File) -> Unit) {
        val root = Files.createTempDirectory("linux-package-layout").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }

    private val required = listOf("libplayer_bridge.so", "libmpv.so.2")
}
