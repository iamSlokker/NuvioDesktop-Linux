package com.nuvio.app.features.updater

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Windows updater policy, exercised without touching installed programs or making network calls.
 * The unauthenticated ZIP-range delta path is intentionally not offered.
 */
class AuthenticatedPortableZipTest {
    @Test
    fun publishedDigestMustBePresentAndWellFormed() {
        listOf(null, "", "deadbeef", "sha256:" + "a".repeat(64), "g".repeat(64))
            .forEach { invalid ->
                assertFailsWith<IllegalArgumentException> {
                    requireExpectedArchiveSha256(invalid)
                }
            }
        val digest = "aBcD".repeat(16)
        assertEquals(digest, requireExpectedArchiveSha256(digest))
    }

    @Test
    fun legacyDeltaMarkerCannotBeInstalled() {
        val error = assertFailsWith<IllegalStateException> {
            requireCompleteZipForInstall(File("delta-update.txt"))
        }
        assertEquals(true, error.message.orEmpty().contains("verified full ZIP"))
        assertFailsWith<IllegalStateException> {
            requireCompleteZipForInstall(File("staged-delta/partial.bin"))
        }
    }

    @Test
    fun aCompleteZipPathRemainsEligibleForVerifiedInstallation() {
        requireCompleteZipForInstall(File("Nuvio-Desktop.zip"))
        requireCompleteZipForInstall(File("Nuvio-Desktop.ZIP"))
    }
}
