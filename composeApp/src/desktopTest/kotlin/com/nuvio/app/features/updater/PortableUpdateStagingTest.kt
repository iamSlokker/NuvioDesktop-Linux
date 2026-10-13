package com.nuvio.app.features.updater

import java.io.File
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercise the real private staging functions without launching cmd, Explorer, or the app.
 * Reflection keeps this test seam out of the production updater API. Script checks establish
 * generation/order only; Windows file locking, shutdown, copying and restart need a real VM.
 */
class PortableUpdateStagingTest {
    @Test fun extractsCompleteRootAndWrappedImagesWithoutChangingBytes() = temporary { root ->
        for (prefix in listOf("", "Nuvio/")) {
            val archive = File(root, "update.zip")
            val payload = byteArrayOf(0, 1, 2, 127, -1)
            ZipOutputStream(archive.outputStream()).use { zip ->
                listOf(prefix + "Nuvio.exe", prefix + "runtime/lib.dll").forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(payload)
                    zip.closeEntry()
                }
            }
            val staged = File(root, if (prefix.isEmpty()) "root" else "wrapped").apply { mkdirs() }
            call("extractZip", archive, staged)
            val image = call("locateAppImageRoot", staged) as File
            assertContentEquals(payload, File(image, "Nuvio.exe").readBytes())
            assertContentEquals(payload, File(image, "runtime/lib.dll").readBytes())
        }
    }

    @Test fun rejectsZipTraversalBeforeWritingOutsideStaging() = temporary { root ->
        val archive = File(root, "update.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("../outside.txt"))
            zip.write("must not escape".toByteArray())
            zip.closeEntry()
        }
        val staged = File(root, "staged").apply { mkdirs() }
        assertFailsWith<IllegalStateException> { call("extractZip", archive, staged) }
        assertFalse(File(root, "outside.txt").exists())
    }

    @Test fun imageWithoutLauncherCannotQualifyForReplacement() = temporary { root ->
        File(root, "app.jar").writeText("not a portable application")
        assertNull(call("locateAppImageRoot", root))
    }

    @Test fun staleManifestOnlyListsDroppedOwnedFilesAndPreservesUserFiles() = temporary { root ->
        val install = File(root, "installed").apply { mkdirs() }
        val staged = File(root, "staged").apply { mkdirs() }
        File(install, "install-manifest.txt").writeText("Nuvio.exe\napp/keep.jar\napp\\old.jar\n")
        File(install, "user-file.txt").writeText("keep")
        val stale = call("writeInstallManifest", staged, install,
            listOf("Nuvio.exe", "APP\\KEEP.jar", "app\\new.jar", "install-manifest.txt")) as List<*>
        assertEquals(listOf("app\\old.jar"), stale)
        assertEquals("keep", File(install, "user-file.txt").readText())
        assertEquals(setOf("Nuvio.exe", "APP\\KEEP.jar", "app\\new.jar"),
            File(staged, "install-manifest.txt").readLines().toSet())
    }

    @Test fun firstUpdateWithoutPreviousManifestDeletesNothing() = temporary { root ->
        val staged = File(root, "staged").apply { mkdirs() }
        assertEquals(emptyList<String>(), call("writeInstallManifest", staged, root, listOf("Nuvio.exe")))
    }

    @Test fun staleManifestRejectsWindowsTraversalAndAbsoluteAliases() = temporary { root ->
        val staged = File(root, "staged").apply { mkdirs() }
        val unsafe = listOf(
            "../outside.txt", "nested/../../outside.txt", "nested\\../outside.txt",
            "..\\outside.txt", "nested\\..\\..\\outside.txt", "/outside.txt", "\\outside.txt",
            "C:\\outside.txt", "C:outside.txt", "\\\\host\\share\\file", "//host/share/file",
            ".. /outside.txt", "nested/.. /../outside.txt", "bad%PATH%.txt", "bad!name.txt",
            "*", "app/*.jar", "app\\?.dll", ".", "nested/./old.jar", "nested./old.jar",
            "nested /old.jar", "nested//old.jar",
        )
        File(root, "install-manifest.txt").writeText((unsafe + "app\\old.jar").joinToString("\n"))
        val stale = call("writeInstallManifest", staged, root, listOf("Nuvio.exe")) as List<*>
        assertEquals(listOf("app\\old.jar"), stale)
    }

    @Test fun generatedHelperWaitsForExitAndDoesNotRestartAfterCopyFailure() = temporary { root ->
        val script = call("writeHelperScript", root, 12345L, File(root, "source"),
            File(root, "installed"), File(root, "installed/Nuvio.exe"), File(root, "staged"),
            File(root, "stale-files.txt")) as File
        val text = script.readText()
        val wait = text.indexOf("tasklist /fi")
        val copy = text.indexOf("robocopy ")
        val failed = text.indexOf("if %ERRORLEVEL% GEQ 8 (")
        val failureEnd = text.indexOf("goto done", failed)
        val deletion = text.indexOf("for /f ")
        val restart = text.indexOf("start \"\" \"%EXE%\"")
        val done = text.indexOf(":done", restart)
        assertTrue(wait >= 0 && wait < copy && copy < failed && failed < failureEnd)
        assertTrue(failureEnd < deletion && deletion < restart && restart < done)
        assertTrue(text.substring(failed, failureEnd).contains("explorer.exe"))
        assertTrue(text.contains("/E /R:15 /W:2"))
        assertFalse(text.contains("/MIR"))
        assertFalse(File(root, "installed").exists()) // Script generated, never executed.
    }

    private fun call(name: String, vararg args: Any): Any? {
        val type = Class.forName("com.nuvio.app.features.updater.AppUpdaterPlatform_desktopKt")
        val method = type.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return try { method.invoke(null, *args) } catch (error: InvocationTargetException) {
            throw error.targetException
        }
    }

    private fun temporary(test: (File) -> Unit) {
        val root = Files.createTempDirectory("nuvio-portable-staging-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
}
