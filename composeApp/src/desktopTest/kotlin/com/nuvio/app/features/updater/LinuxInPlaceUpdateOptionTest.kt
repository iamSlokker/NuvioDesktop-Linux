package com.nuvio.app.features.updater

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxInPlaceUpdateOptionTest {
    @Test
    fun `only the packages that read the setting offer it`() {
        assertTrue(linuxOffersInPlaceUpdateOption(LinuxInstallFormat.AppImage))
        assertTrue(linuxOffersInPlaceUpdateOption(LinuxInstallFormat.Deb))
        assertFalse(linuxOffersInPlaceUpdateOption(LinuxInstallFormat.Flatpak))
        assertFalse(linuxOffersInPlaceUpdateOption(LinuxInstallFormat.Unknown))
    }

    @Test
    fun `a package without the option has no text for it`() {
        assertNull(linuxInPlaceUpdateOptionDescription(LinuxInstallFormat.Flatpak))
        assertNull(linuxInPlaceUpdateOptionDescription(LinuxInstallFormat.Unknown))
    }
}
