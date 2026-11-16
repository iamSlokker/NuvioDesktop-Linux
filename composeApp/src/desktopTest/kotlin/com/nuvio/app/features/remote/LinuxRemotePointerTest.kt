package com.nuvio.app.features.remote

import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxRemotePointerTest {
    @Test
    fun aWaylandSessionIsNamedOrHasADisplay() {
        assertTrue(waylandSession(mapOf("XDG_SESSION_TYPE" to "wayland")))
        // Flatpak passes the Wayland socket but not always the session type.
        assertTrue(waylandSession(mapOf("WAYLAND_DISPLAY" to "wayland-0")))
        assertFalse(waylandSession(mapOf("XDG_SESSION_TYPE" to "x11", "DISPLAY" to ":0")))
        assertFalse(waylandSession(mapOf("WAYLAND_DISPLAY" to " ")))
        assertFalse(waylandSession(emptyMap()))
    }

    @Test
    fun robotButtonsBecomeEvdevButtons() {
        assertEquals(listOf(272), evdevButtons(InputEvent.BUTTON1_DOWN_MASK))
        assertEquals(listOf(273), evdevButtons(InputEvent.BUTTON3_DOWN_MASK))
        assertEquals(listOf(274), evdevButtons(InputEvent.BUTTON2_DOWN_MASK))
        assertEquals(listOf(272, 273), evdevButtons(InputEvent.BUTTON1_DOWN_MASK or InputEvent.BUTTON3_DOWN_MASK))
        assertEquals(emptyList(), evdevButtons(0))
    }

    @Test
    fun onlyShiftGoesThroughThePortalKeyboard() {
        assertEquals(42, evdevKey(KeyEvent.VK_SHIFT))
        assertNull(evdevKey(KeyEvent.VK_A))
    }
}
