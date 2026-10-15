package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import kotlinx.coroutines.runBlocking
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The real platform object on Linux must route every update through the Linux updater and never
 * reach the Windows portable-ZIP/batch-helper path. Runs in a sanitized child JVM (no APPIMAGE,
 * FLATPAK_ID or packaged launcher), so the install is "unknown" and nothing may be downloaded.
 */
class LinuxUpdaterIsolationTest {
    @Test
    fun linuxUpdatesNeverEnterTheWindowsUpdater() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxUpdaterIsolationProbe::class.java, "routing")
    }
}

internal object LinuxUpdaterIsolationProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        assertEquals("Linux", System.getProperty("os.name"))
        assertTrue(GraphicsEnvironment.isHeadless())
        assertNull(System.getenv("DISPLAY"))
        assertNull(System.getenv("WAYLAND_DISPLAY"))
        assertNull(System.getenv("APPIMAGE"))
        assertNull(System.getenv("FLATPAK_ID"))
        assertEquals("routing", args[1])

        assertTrue(AppFeaturePolicy.inAppUpdaterEnabled)
        assertTrue(AppUpdaterPlatform.isSupported)
        assertSame(LinuxReleaseRules, platformReleaseRules)
        assertFalse(nightlyChannelSelectable)

        // A nightly seeded into the store (e.g. by a nightly-stamped build) is never followed.
        AppUpdaterPlatform.setUpdateChannel(UpdateChannel.Nightly)
        assertEquals(UpdateChannel.Stable, AppUpdaterPlatform.getUpdateChannel())

        // The Windows ZIP flow is unreachable: no download is attempted and no helper is staged.
        val download = runBlocking {
            AppUpdaterPlatform.downloadApk(
                assetUrl = "http://127.0.0.1:9/Nuvio-windows-x64.zip",
                assetName = "Nuvio-windows-x64.zip",
                expectedSha256 = "a".repeat(64),
            ) { _, _ -> error("No bytes may be downloaded") }
        }
        assertTrue(download.isFailure)
        assertTrue(AppUpdaterPlatform.installDownloadedApk("Nuvio-windows-x64.zip").isFailure)
    }
}
