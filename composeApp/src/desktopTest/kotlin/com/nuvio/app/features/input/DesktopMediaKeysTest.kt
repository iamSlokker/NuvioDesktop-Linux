package com.nuvio.app.features.input

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopMediaKeysTest {
    @Test
    fun transportKeysMapToTheirActions() {
        assertEquals(DesktopMediaKeyAction.PlayPause, desktopMediaKeyAction(0xB3))
        assertEquals(DesktopMediaKeyAction.Next, desktopMediaKeyAction(0xB0))
        assertEquals(DesktopMediaKeyAction.Previous, desktopMediaKeyAction(0xB1))
    }

    @Test
    fun otherKeysAreNotTransportKeys() {
        assertNull(desktopMediaKeyAction(0x20))
    }
}
