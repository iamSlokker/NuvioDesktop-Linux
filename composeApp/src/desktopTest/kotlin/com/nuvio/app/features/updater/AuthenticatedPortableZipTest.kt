package com.nuvio.app.features.updater

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.testing.LocalHttpFixture
import com.nuvio.app.testing.respond
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real downloader and installation gate; temporary profiles, loopback HTTP, no installer launch. */
class AuthenticatedPortableZipTest {
    @Test fun publishedDigestMustBePresentAndWellFormed() {
        listOf(null, "", " ", "deadbeef", "sha256:" + "a".repeat(64), "g".repeat(64),
            "a".repeat(63), "a".repeat(65), "a".repeat(64) + "\n").forEach { invalid ->
            assertFailsWith<IllegalArgumentException> { requireExpectedArchiveSha256(invalid) }
        }
        val digest = "aBcD".repeat(16)
        assertEquals(digest, requireExpectedArchiveSha256(digest))
    }

    @Test fun legacyDeltaMarkerCannotBeInstalled() {
        for (name in listOf("delta-update.txt", "DELTA-UPDATE.TXT", "staged-delta/partial.bin")) {
            val error = assertFailsWith<IllegalStateException> { requireCompleteZipForInstall(File(name)) }
            assertTrue(error.message.orEmpty().contains("verified full ZIP"))
        }
    }

    @Test fun completeZipDownloadIsVerifiedWithoutRangeRequests() = probe("success")
    @Test fun missingOrMalformedDigestFailsBeforeAnyHttpOrFileWork() = probe("invalid-digest")
    @Test fun mismatchedDownloadedBytesAreDiscarded() = probe("mismatch")
    @Test fun emptyDownloadCannotBeInstalled() = probe("empty")
    @Test fun httpFailureCannotProduceAnInstallableArchive() = probe("http-error")
    @Test fun interruptedDownloadCannotProduceAnInstallableArchive() = probe("interrupted")
    @Test fun changedBytesAfterDownloadAreRejectedBeforeInstallerSideEffects() = probe("tampered")
    @Test fun anUnverifiedZipOrRenamedLegacyMarkerCannotBeInstalled() = probe("unverified")
    @Test fun failedRedownloadCannotReuseAPreviousVerificationReceipt() = probe("failed-redownload")
    @Test fun replacementDownloadUsesItsOwnDigestAndPath() = probe("replacement")

    private fun probe(scenario: String) = withHeadlessFixture {
        runHeadlessProbe(it, AuthenticatedPortableZipProbe::class.java, scenario)
    }
}

internal object AuthenticatedPortableZipProbe {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        assertEquals("Linux", System.getProperty("os.name"))
        assertNull(System.getenv("DISPLAY"))
        assertNull(System.getenv("WAYLAND_DISPLAY"))
        val updates = DesktopStorage.rootDir.resolve("updates").toFile()
        // Make the portable in-place layout eligible even on Linux. Reconnecting the old delta
        // branch must produce a Range request and fail this fixture, rather than silently skipping
        // it because no installed Nuvio.exe was found. The fake launcher is never executed.
        val install = File(args[0], "portable-install").apply { mkdirs() }
        val launcher = File(install, "Nuvio.exe").apply { writeText("existing fixture build") }
        System.setProperty("jpackage.app-path", launcher.absolutePath)
        val bytes = zip("first build")
        val other = zip("different build")
        var served = bytes
        val scenario = args[1]
        LocalHttpFixture().use { http ->
            http.route("/archive") { exchange, _ ->
                when (scenario) {
                    "empty" -> exchange.sendResponseHeaders(200, -1)
                    "http-error" -> exchange.respond(403, "denied".toByteArray())
                    "interrupted" -> {
                        exchange.sendResponseHeaders(200, served.size.toLong())
                        exchange.responseBody.write(served, 0, served.size / 2)
                    }
                    else -> exchange.respond(200, served, "application/zip")
                }
            }
            http.start()
            suspend fun download(name: String = "Nuvio.zip", digest: String? = sha(bytes)): Result<String> =
                AppUpdaterPlatform.downloadApk(http.url + "/archive", name, digest) { received, total ->
                    assertTrue(received > 0)
                    assertEquals(served.size.toLong(), total)
                }
            fun rejectAtInstall(file: File, message: String) {
                val error = assertFailsWith<IllegalStateException> { requireCompleteZipForInstall(file) }
                assertTrue(error.message.orEmpty().contains(message), error.message)
                // Exercise the public installer too: rejection must precede Windows helpers/fallback.
                val failure = AppUpdaterPlatform.installDownloadedApk(file.absolutePath).exceptionOrNull()
                assertTrue(failure is IllegalStateException, "$failure")
                assertTrue(failure.message.orEmpty().contains(message), failure.message)
                assertFalse(File(updates, "apply-update.bat").exists())
                assertFalse(File(updates, "staged").exists())
            }
            when (scenario) {
                "success" -> {
                    for (name in listOf("Nuvio.zip", "Nuvio.ZIP")) {
                        val file = File(download(name, sha(bytes).uppercase()).getOrThrow())
                        assertContentEquals(bytes, file.readBytes())
                        requireCompleteZipForInstall(file)
                        assertFalse(File(updates, "$name.part").exists())
                    }
                }
                "invalid-digest" -> {
                    for (digest in listOf(null, "", "x".repeat(64), "a".repeat(63))) {
                        assertTrue(download(digest = digest).exceptionOrNull() is IllegalArgumentException)
                    }
                    assertTrue(http.drainRequests().isEmpty())
                    assertFalse(updates.exists())
                }
                "mismatch", "empty", "http-error", "interrupted" -> {
                    val result = download(digest = if (scenario == "mismatch") sha(other) else sha(bytes))
                    assertTrue(result.isFailure)
                    assertFalse(File(updates, "Nuvio.zip").exists())
                    if (scenario == "mismatch") {
                        assertTrue(result.exceptionOrNull()!!.message.orEmpty().contains("SHA-256"))
                        assertFalse(File(updates, "Nuvio.zip.part").exists())
                    }
                }
                "tampered" -> {
                    val file = File(download().getOrThrow())
                    file.writeBytes(other) // Still a well-formed ZIP, changed after streaming verification.
                    rejectAtInstall(file, "SHA-256")
                }
                "unverified" -> {
                    updates.mkdirs()
                    val zip = File(updates, "Nuvio.zip").apply { writeBytes(bytes) }
                    rejectAtInstall(zip, "verified download")
                    val marker = File(updates, "delta-update.txt").apply { writeText("Nuvio.exe\r\n") }
                    rejectAtInstall(marker, "verified full ZIP")
                    val disguised = File(updates, "delta-update.txt.zip")
                    assertTrue(marker.renameTo(disguised))
                    rejectAtInstall(disguised, "verified download")
                }
                "failed-redownload" -> {
                    val file = File(download().getOrThrow())
                    requireCompleteZipForInstall(file)
                    served = other
                    assertTrue(download().isFailure)
                    file.writeBytes(bytes)
                    rejectAtInstall(file, "verified download")
                }
                "replacement" -> {
                    val first = File(download().getOrThrow())
                    served = other
                    val replacement = File(download(digest = sha(other)).getOrThrow())
                    assertEquals(first, replacement)
                    requireCompleteZipForInstall(replacement)
                    val copied = File(updates, "copied.zip").apply { writeBytes(other) }
                    rejectAtInstall(copied, "verified download")
                    replacement.writeBytes(bytes)
                    rejectAtInstall(replacement, "SHA-256")
                }
                else -> error("Unknown scenario: $scenario")
            }
            for (request in http.drainRequests()) {
                assertEquals("GET", request.method)
                assertNull(request.headers["range"], "Unauthenticated ZIP range download is forbidden")
            }
            assertEquals("existing fixture build", launcher.readText())
        }
    }

    private fun zip(payload: String): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { archive ->
            archive.putNextEntry(ZipEntry("Nuvio/Nuvio.exe"))
            archive.write(payload.toByteArray())
            archive.closeEntry()
        }
    }.toByteArray()

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
