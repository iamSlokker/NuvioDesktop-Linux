package com.nuvio.app.features.p2p

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.engine.internal.NuvioEngineLibrary
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxEngineTlsTest {
    @Test
    fun `the first bundle that exists is used on Linux`() {
        val dir = Files.createTempDirectory("nuvio-ca").toFile()
        try {
            val fedora = dir.resolve("ca-bundle.crt").apply { writeText("pem") }
            assertEquals(fedora, linuxSystemCaBundle(DesktopHostOs.LINUX, listOf(dir.resolve("missing.crt"), fedora)))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `nothing changes off Linux or without a bundle`() {
        val existing = File.createTempFile("nuvio-ca", ".crt")
        try {
            assertNull(linuxSystemCaBundle(DesktopHostOs.WINDOWS, listOf(existing)))
            assertNull(linuxSystemCaBundle(DesktopHostOs.LINUX, listOf(File("/nonexistent/ca.crt"))))
        } finally {
            existing.delete()
        }
    }

    @Test
    fun `the Linux library has its own name`() {
        assertTrue(NuvioEngineLibrary.LIBRARY_FILE_NAME in setOf("libnuvio_engine.so", "nuvio_engine.dll"))
    }
}
