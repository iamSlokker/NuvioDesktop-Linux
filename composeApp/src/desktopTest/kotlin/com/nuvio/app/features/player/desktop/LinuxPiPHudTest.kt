package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxPiPHudTest {
    @Test fun targetsAreWhereTheyAreDrawn() {
        val layout = LinuxPiPHudLayout(400, 40)
        assertEquals(LinuxPiPHudTarget.Play, layout.target(5, 20))
        assertEquals(LinuxPiPHudTarget.Exit, layout.target(395, 20))
        assertEquals(LinuxPiPHudTarget.Seek, layout.target(200, 2))
        assertEquals(LinuxPiPHudTarget.None, layout.target(200, 40))
    }

    @Test fun theSeekBarSpansTheGapBetweenTheButtons() {
        val layout = LinuxPiPHudLayout(400, 40)
        assertEquals(0.0, layout.fractionAt(0))
        assertEquals(1.0, layout.fractionAt(399))
        assertEquals(0.5, layout.fractionAt(layout.bar.x + layout.bar.width / 2), 0.01)
        assertTrue(layout.bar.x >= layout.play.x + layout.play.width)
        assertTrue(layout.bar.x + layout.bar.width <= layout.exit.x)
    }

    @Test fun theSmallestWindowStillHasABar() {
        val strip = linuxPiPHudBounds(Rectangle(0, 0, 240, 135))
        val layout = LinuxPiPHudLayout(strip.width, strip.height)
        assertTrue(layout.bar.width > 100, "${layout.bar}")
        assertEquals(LinuxPiPHudTarget.Seek, layout.target(strip.width / 2, 20))
    }

    @Test fun theStripSitsInsideTheBottomOfThePicture() {
        val host = Rectangle(100, 200, 480, 270)
        val strip = linuxPiPHudBounds(host)
        assertTrue(host.contains(strip), "$strip")
        assertEquals(host.y + host.height - 8, strip.y + strip.height)
        assertEquals(host.x + 8, strip.x)
    }

    @Test fun seekingMapsToTheDurationAndLiveStreamsCannotBeSeeked() {
        assertEquals(30_000L, linuxPiPSeekTarget(0.5, 60_000))
        assertEquals(60_000L, linuxPiPSeekTarget(2.0, 60_000))
        assertEquals(0L, linuxPiPSeekTarget(-1.0, 60_000))
        assertNull(linuxPiPSeekTarget(0.5, 0))
    }

    @Test fun thePanelPaintsEveryStateWithoutADisplay() {
        val states = listOf(
            null,
            PlayerPlaybackSnapshot(isPlaying = true, durationMs = 60_000, positionMs = 30_000, bufferedPositionMs = 45_000),
            PlayerPlaybackSnapshot(isPlaying = false, durationMs = 0),
        )
        val blank = BufferedImage(300, 40, BufferedImage.TYPE_INT_ARGB)
        for (state in states) {
            val image = BufferedImage(300, 40, BufferedImage.TYPE_INT_ARGB)
            val panel = LinuxPiPHudPanel({ state }, translucent = true)
            panel.setSize(300, 40)
            val g = image.createGraphics()
            panel.paint(g)
            g.dispose()
            assertNotEquals(blank.getRGB(150, 20), image.getRGB(150, 20), "$state")
        }
    }
}
