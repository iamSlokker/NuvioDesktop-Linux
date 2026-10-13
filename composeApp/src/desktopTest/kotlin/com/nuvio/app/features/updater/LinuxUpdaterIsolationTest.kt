package com.nuvio.app.features.updater

import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import java.awt.GraphicsEnvironment
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercise the real controller guard without allowing an accidental regression to run HTTP work. */
class LinuxUpdaterIsolationTest {
    @Test
    fun automaticUpdateChecksCannotEnterTheWindowsUpdaterOnLinux() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxUpdaterIsolationProbe::class.java, "automatic")
    }

    @Test
    fun forcedUpdateChecksCannotEnterTheWindowsUpdaterOnLinux() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxUpdaterIsolationProbe::class.java, "forced")
    }
}

internal object LinuxUpdaterIsolationProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        assertEquals("Linux", System.getProperty("os.name"))
        assertTrue(GraphicsEnvironment.isHeadless())
        assertNull(System.getenv("DISPLAY"))
        assertNull(System.getenv("WAYLAND_DISPLAY"))
        assertTrue(AppFeaturePolicy.inAppUpdaterEnabled, "Exercise the OS guard, not a disabled feature flag")
        assertFalse(AppUpdaterPlatform.isSupported)

        var dispatched = 0
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                // Never execute the submitted work: a broken guard must fail deterministically,
                // without reaching GitHub, a staged update, or a platform installer.
                dispatched += 1
            }
        }
        val job = Job()
        try {
            val controller = AppUpdaterController(CoroutineScope(dispatcher + job))
            val before = controller.uiState.value
            when (args[1]) {
                "automatic" -> repeat(2) { controller.ensureAutoCheckStarted() }
                "forced" -> controller.checkForUpdates(force = true, showNoUpdateFeedback = false)
                else -> error("Unknown fixture scenario")
            }
            controller.downloadUpdate()
            controller.installDownloadedUpdate()
            assertEquals(0, dispatched, "Linux must not enqueue the Windows release/delta download path")
            assertEquals(before, controller.uiState.value)
            assertNull(controller.uiState.value.update)
            assertNull(controller.uiState.value.downloadedApkPath)
        } finally {
            job.cancel()
        }
    }
}
