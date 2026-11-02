package com.nuvio.app.features.player.desktop

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxShaderCacheOptionTest {
    @Test fun theCacheSitsBesideTheLogsAndTheFolderExists() {
        val root = Files.createTempDirectory("nuvio cache")
        val option = linuxShaderCacheOption(root)
        assertEquals("gpu-shader-cache-dir=${root.resolve("shader-cache")}", option)
        assertTrue(Files.isDirectory(root.resolve("shader-cache")))
        assertEquals(option, linuxShaderCacheOption(root), "an existing folder is fine")
    }

    @Test fun aFolderThatCannotBeMadeMeansNoOption() {
        val file = Files.createTempFile("nuvio", "cache")
        assertNull(linuxShaderCacheOption(file))
    }
}
