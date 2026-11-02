package com.nuvio.app.core.ui

import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxStatusNotifierTrayTest {
    @Test fun pixelsAreNetworkOrderArgb() {
        val image = BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, 0x11223344)
        image.setRGB(1, 0, 0xFFA0B0C0.toInt())
        assertEquals(
            listOf(0x11, 0x22, 0x33, 0x44, 0xFF, 0xA0, 0xB0, 0xC0),
            LinuxStatusNotifierTray.argbBytes(image).map { it.toInt() and 0xFF },
        )
    }

    @Test fun eventsReachTheirActions() {
        val seen = mutableListOf<String>()
        fun run(event: Int) = LinuxStatusNotifierTray.dispatch(event, { seen += "open" }, { seen += "exit" }, { seen += "host $it" })
        assertTrue(run(LinuxStatusNotifierTray.EventOpen))
        assertTrue(run(LinuxStatusNotifierTray.EventExit))
        assertTrue(run(LinuxStatusNotifierTray.EventHostLost))
        assertTrue(run(LinuxStatusNotifierTray.EventHostBack))
        assertFalse(run(0))
        assertEquals(listOf("open", "exit", "host false", "host true"), seen)
    }
}
