package com.nuvio.app.core.storage

import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A new HTPC user who has run the official Nuvio Desktop has its `%APPDATA%\Nuvio` directory; that
 * alone must not read as an upgrade, or they never see the setup wizard or new-install defaults.
 */
class DesktopStorageFreshInstallTest {
    @Test
    fun `an upstream Nuvio directory is not an HTPC install`() {
        val dir = Files.createTempDirectory("nuvio-upstream")
        Files.writeString(dir.resolve("nuvio_player_settings.properties"), "")
        Files.writeString(dir.resolve("nuvio_addons.properties"), "")
        Files.createDirectories(dir.resolve("Cache"))
        assertFalse(DesktopStorage.holdsHtpcOnlyStore(dir))
    }

    @Test
    fun `a pre-1_10 HTPC directory is recognised by its fork-only stores`() {
        val dir = Files.createTempDirectory("nuvio-htpc-legacy")
        Files.writeString(dir.resolve("nuvio_player_shortcuts.properties"), "")
        assertTrue(DesktopStorage.holdsHtpcOnlyStore(dir))
    }

    @Test
    fun `a missing directory holds nothing`() {
        val dir = Files.createTempDirectory("nuvio-none").resolve("absent")
        assertFalse(DesktopStorage.holdsHtpcOnlyStore(dir))
    }

    @Test
    fun `the legacy migration copies settings and user data but not caches or updates`() {
        val root = Files.createTempDirectory("nuvio-legacy-migration")
        val roaming = Files.createDirectories(root.resolve("Roaming/Nuvio"))
        val local = Files.createDirectories(root.resolve("Local/Nuvio"))
        Files.writeString(roaming.resolve("nuvio_player_settings.properties"), "a=1")
        Files.createDirectories(roaming.resolve("updates"))
        Files.writeString(roaming.resolve("updates/Nuvio-setup.exe"), "x")
        Files.createDirectories(roaming.resolve("torrserver"))
        Files.writeString(roaming.resolve("torrserver/TorrServer.exe"), "x")
        Files.createDirectories(local.resolve("Cache/nuvio-engine"))
        Files.writeString(local.resolve("Cache/nuvio-engine/piece"), "x")
        Files.createDirectories(local.resolve("WebView2/Default"))
        Files.writeString(local.resolve("WebView2/Default/state"), "x")
        Files.createDirectories(local.resolve("logs"))
        Files.writeString(local.resolve("logs/nuvio.log"), "x")
        Files.writeString(local.resolve("instance.lock"), "")
        Files.createDirectories(local.resolve("Badges/cache"))
        Files.writeString(local.resolve("Badges/cache/award.png"), "x")

        val destination = root.resolve("Local/NuvioHTPC")
        DesktopStorage.migrateLegacyDirectories(destination, roaming, local)

        assertTrue(destination.resolve("nuvio_player_settings.properties").exists())
        // Only top-level names are skipped: a user folder may hold its own "cache".
        assertTrue(destination.resolve("Badges/cache/award.png").exists())
        listOf("updates", "torrserver", "Cache", "WebView2", "logs", "instance.lock").forEach { name ->
            assertFalse(destination.resolve(name).exists(), "$name should not be migrated")
        }
    }
}
