package com.nuvio.app.core.build

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxHostProcessTest {
    @Test
    fun `the self-test names what a host program must not inherit`() {
        val leaked = { child: Map<String, String>, sandboxed: Boolean ->
            LinuxNativeSelfTest.leakedEnvironment(child, "/tmp/.mount_x/usr/lib/nuvio-desktop", sandboxed)
        }
        val inImage = "/tmp/.mount_x/usr/lib/nuvio-desktop/share"
        assertEquals(emptyList(), leaked(mapOf("PATH" to "/usr/bin", "XDG_DATA_DIRS" to "/usr/share", "_" to inImage), false))
        assertEquals(listOf("LD_LIBRARY_PATH", "XDG_DATA_DIRS"), leaked(mapOf("LD_LIBRARY_PATH" to "/x", "XDG_DATA_DIRS" to inImage), false))
        assertEquals(listOf("LD_PRELOAD"), leaked(mapOf("LD_PRELOAD" to "/x", "XDG_DATA_DIRS" to inImage), true))
    }

    @Test
    fun `a real child loses the private library path`() {
        val builder = ProcessBuilder("env").apply { environment()["LD_LIBRARY_PATH"] = "/x" }
        LinuxHostProcess.scrub(builder.environment())
        val output = builder.start().inputStream.bufferedReader().readLines()
        assertFalse(output.any { it.startsWith("LD_LIBRARY_PATH=") }, output.toString())
    }

    private fun scrubbed(vararg variables: Pair<String, String>): Map<String, String> =
        mutableMapOf(*variables).also(LinuxHostProcess::scrub)

    @Test
    fun `a DEB or Flatpak child only loses the private library path`() {
        assertEquals(
            mapOf("PATH" to "/usr/bin", "XDG_DATA_DIRS" to "/usr/share", "GDK_BACKEND" to "x11"),
            scrubbed(
                "PATH" to "/usr/bin", "XDG_DATA_DIRS" to "/usr/share", "GDK_BACKEND" to "x11",
                "LD_LIBRARY_PATH" to "/app/lib", "LD_PRELOAD" to "/app/x.so",
            ),
        )
    }

    @Test
    fun `an AppImage child gets the host data directories back and no mount paths`() {
        val result = scrubbed(
            "PATH" to "/usr/bin",
            "NUVIO_HOST_XDG_DATA_DIRS" to "/home/u/.local/share/flatpak/exports/share:/usr/share",
            "XDG_DATA_DIRS" to "/tmp/.mount_x/share:/usr/share",
            "APPIMAGE" to "/home/u/Nuvio.AppImage",
            "GTK_PATH" to "/tmp/.mount_x/lib/native/gtk-3.0",
            "GIO_MODULE_DIR" to "/tmp/.mount_x/lib/native/gio/modules",
            "GST_PLUGIN_SYSTEM_PATH_1_0" to "/tmp/.mount_x/lib/native/gstreamer-1.0",
            "GDK_BACKEND" to "x11",
            "WEBKIT_FORCE_SANDBOX" to "1",
            "LD_LIBRARY_PATH" to "/tmp/.mount_x/lib/native",
        )
        assertEquals(
            mapOf("PATH" to "/usr/bin", "XDG_DATA_DIRS" to "/home/u/.local/share/flatpak/exports/share:/usr/share"),
            result,
        )
    }

    @Test
    fun `an unset host value stays unset so programs use the XDG default`() {
        assertEquals(
            emptyMap(),
            scrubbed("NUVIO_HOST_XDG_DATA_DIRS" to "", "XDG_DATA_DIRS" to "/tmp/.mount_x/share"),
        )
    }

    private fun exited(code: Int): Process = object : Process() {
        override fun getOutputStream() = java.io.OutputStream.nullOutputStream()
        override fun getInputStream() = java.io.InputStream.nullInputStream()
        override fun getErrorStream() = java.io.InputStream.nullInputStream()
        override fun waitFor() = code
        override fun exitValue() = code
        override fun destroy() = Unit
        override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
    }

    @Test
    fun `xdg-open that cannot start or fails falls back`() {
        val missing = CountDownLatch(1)
        LinuxHostProcess.open("https://example.com", missing::countDown) { throw IOException("no xdg-open") }
        assertTrue(missing.await(5, TimeUnit.SECONDS))

        val failed = CountDownLatch(1)
        LinuxHostProcess.open("https://example.com", failed::countDown) { exited(3) }
        assertTrue(failed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `xdg-open that succeeds does not fall back and gets the target as its argument`() {
        var command = emptyList<String>()
        var fellBack = false
        LinuxHostProcess.open("https://example.com", { fellBack = true }) { builder ->
            command = builder.command()
            exited(0)
        }
        assertEquals(listOf("xdg-open", "https://example.com"), command)
        assertFalse(fellBack)
    }
}
