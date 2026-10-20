package com.nuvio.app.features.plugins

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxQuickJsNativeLibraryTest {
    @Test
    fun `the packaged library is found in lib native beside the runtime`() {
        val root = Files.createTempDirectory("nuvio-quickjs").toFile()
        try {
            val runtime = root.resolve("lib/runtime").apply { mkdirs() }
            val native = root.resolve("lib/native").apply { mkdirs() }
            assertNull(LinuxQuickJsNativeLibrary.libraryDirectory(runtime, localBuilds = false))
            native.resolve(LinuxQuickJsNativeLibrary.LIBRARY_FILE_NAME).writeText("fixture")
            assertEquals(native, LinuxQuickJsNativeLibrary.libraryDirectory(runtime, localBuilds = false))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a packaged app never falls back to a library under the working directory`() {
        val root = Files.createTempDirectory("nuvio-quickjs").toFile()
        try {
            assertNull(LinuxQuickJsNativeLibrary.libraryDirectory(root.resolve("lib/runtime"), localBuilds = false))
            assertNull(LinuxQuickJsNativeLibrary.libraryDirectory(null, localBuilds = false))
        } finally {
            root.deleteRecursively()
        }
    }
}
