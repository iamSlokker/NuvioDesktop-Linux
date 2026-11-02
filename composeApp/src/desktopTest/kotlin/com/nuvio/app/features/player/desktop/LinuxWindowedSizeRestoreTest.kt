package com.nuvio.app.features.player.desktop

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxWindowedSizeRestoreTest {
    private val screen = Rectangle(0, 0, 1920, 1080)
    private val remembered = DesktopWindowGeometry(x = 100f, y = 50f, width = 1280f, height = 746f, maximized = false)

    @Test
    fun `a usable size is the user's and stays`() {
        assertNull(linuxWindowedBoundsAfterFullscreen(Rectangle(320, 257, 1280, 746), remembered, screen))
    }

    @Test
    fun `the PiP rect goes back to the remembered window`() {
        assertEquals(
            Rectangle(100, 50, 1280, 746),
            linuxWindowedBoundsAfterFullscreen(Rectangle(1420, 810, 480, 270), remembered, screen),
        )
    }

    @Test
    fun `with nothing remembered it opens centred at the default size, within the screen`() {
        assertEquals(
            Rectangle(320, 130, 1280, 820),
            linuxWindowedBoundsAfterFullscreen(Rectangle(1420, 810, 480, 270), null, screen),
        )
        assertEquals(
            Rectangle(0, 0, 1024, 768),
            linuxWindowedBoundsAfterFullscreen(Rectangle(0, 0, 480, 270), null, Rectangle(0, 0, 1024, 768)),
        )
    }
}
