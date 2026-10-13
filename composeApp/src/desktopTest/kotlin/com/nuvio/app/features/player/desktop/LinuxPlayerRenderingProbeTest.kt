package com.nuvio.app.features.player.desktop

import com.nuvio.app.LiveDisplayTests
import com.nuvio.app.features.player.DesktopColorProfile
import com.nuvio.app.features.player.DesktopMpvConfigMode
import com.nuvio.app.features.player.presetGrade
import java.awt.Color
import java.nio.file.Path
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/** Explicit player-display probe. No Nuvio application, remote media or audio output. */
class LinuxPlayerRenderingProbeTest {
    @Test
    fun localSixteenByNineRetainsFullFrameAcrossLiveColorChangesWithoutHud() {
        LiveDisplayTests.assumeEnabled()
        assumeTrue("Use an explicitly requested test display and isolated XDG directories",
            System.getenv("NUVIO_PLAYER_RENDERING_PROBE") == "1")
        assumeTrue(DesktopHostOs.current == DesktopHostOs.LINUX)
        // Avoid an asynchronous WM decoration/inset change during fixture sizing.
        LinuxBaseConfigurationTest.Fixture(videoWidth = 160, videoHeight = 90, undecorated = true).use { f ->
            SwingUtilities.invokeAndWait {
                val frame = f.frame!!
                val insets = frame.insets
                frame.setSize(640 + insets.left + insets.right, 360 + insets.top + insets.bottom)
            }
            f.waitFor { f.host.width == 640 && f.host.height == 360 }
            // Empty controls URL intentionally excludes GTK/WebKit from this probe.
            val output = Path.of(System.getProperty("nuvio.playerProbe.output", f.directory.toString()))
            f.openNative(DesktopMpvConfigMode.Replace, listOf("log-file=${output.resolve("mpv.log")}", "gpu-sw=yes"))
            f.assertIntegration(f.values())
            NativePlayerBridge.setResizeMode(f.handle, 0)
            NativePlayerBridge.setPaused(f.handle, false)
            for (profile in listOf(DesktopColorProfile.Neutral, DesktopColorProfile.Cinematic,
                DesktopColorProfile.Vivid, DesktopColorProfile.Neutral)) {
                val grade = profile.presetGrade()
                for ((key, value) in listOf("contrast" to grade.contrast, "brightness" to grade.brightness,
                    "saturation" to grade.saturation, "gamma" to grade.gamma)) {
                    NativePlayerBridge.setMpvProperty(f.handle, key, value.toString())
                }
                f.assertGrade(grade.contrast, grade.brightness, grade.saturation, grade.gamma)
                val image = f.directory.resolve("${profile.name}-${System.nanoTime()}.png")
                f.screenshot(image)
                val pixels = ImageIO.read(image.toFile())
                assertEquals(640, pixels.width)
                assertEquals(360, pixels.height)
                // Inspect the real GPU output, including all four edges. No cropping,
                // aspect override or hidden HUD could make a blank render pass this.
                for (y in listOf(0, 1, 180, 358, 359)) {
                    for (x in listOf(0, 1, 320, 638, 639)) {
                        val pixel = Color(pixels.getRGB(x, y))
                        assertTrue(pixel.red > 30 && pixel.green > 30 && pixel.blue > 30,
                            "$profile black frame/bar at $x,$y: $pixel")
                    }
                }
                println("PLAYER_RENDER $profile: 640x360, nonblack edges and center")
            }
        }
    }
}
