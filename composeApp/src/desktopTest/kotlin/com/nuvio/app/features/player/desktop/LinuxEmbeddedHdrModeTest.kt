package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopHdrMode
import com.nuvio.app.features.player.DesktopMpvConfigMode
import com.nuvio.app.features.player.PlayerSettingsUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The HDR mode on the embedded player: it has no HDR surface, and must say so rather than do nothing. */
class LinuxEmbeddedHdrModeTest {
    private fun targets(mode: DesktopHdrMode, panel: String? = null) = linuxEmbeddedHdrTargetProperties(
        PlayerSettingsUiState(
            desktopMpvConfigMode = DesktopMpvConfigMode.Off,
            desktopHdrMode = mode,
            desktopMpvPropertyOverrides = panel?.let { mapOf(DESKTOP_DISPLAY_PANEL_OVERRIDE_KEY to it) }.orEmpty(),
        ),
    ).toMap()

    @Test fun tonemapForcesAnSdrTarget() {
        val properties = targets(DesktopHdrMode.AlwaysTonemap)
        assertEquals("bt.709", properties["target-prim"])
        assertEquals("bt.1886", properties["target-trc"])
        assertEquals("203", properties["target-peak"])
        assertEquals("1000", properties["target-contrast"])
    }

    @Test fun autoLeavesTheTargetToMpv() {
        val properties = targets(DesktopHdrMode.Auto)
        assertEquals("auto", properties["target-prim"])
        assertEquals("auto", properties["target-trc"])
        assertEquals("auto", properties["target-peak"])
    }

    @Test fun passthroughHasNoPathInThisWindowAndTakesAutosTargets() {
        assertEquals(targets(DesktopHdrMode.Auto), targets(DesktopHdrMode.AlwaysPassthrough))
    }

    @Test fun anOledPanelKeepsItsContrastInEveryMode() {
        for (mode in DesktopHdrMode.entries) {
            assertEquals("inf", targets(mode, DESKTOP_DISPLAY_PANEL_OLED)["target-contrast"], mode.name)
        }
    }

    @Test fun theGradeFollowsWindowsRule() {
        assertTrue(linuxColorGradeApplies(DesktopHdrMode.Auto, isHdr = false))
        assertTrue(linuxColorGradeApplies(DesktopHdrMode.AlwaysPassthrough, isHdr = false))
        assertTrue(linuxColorGradeApplies(DesktopHdrMode.AlwaysTonemap, isHdr = true))
        assertTrue(linuxColorGradeApplies(DesktopHdrMode.AlwaysTonemap, isHdr = null))
        assertFalse(linuxColorGradeApplies(DesktopHdrMode.Auto, isHdr = true))
        assertFalse(linuxColorGradeApplies(DesktopHdrMode.Auto, isHdr = null))
        assertFalse(linuxColorGradeApplies(DesktopHdrMode.AlwaysPassthrough, isHdr = true))
    }

    @Test fun thePanelSaysToneMappedOnlyForHdr() {
        assertEquals("HDR Tone Mapped to SDR", linuxEmbeddedHdrInfoLabel(true))
        assertNull(linuxEmbeddedHdrInfoLabel(false))
        assertNull(linuxEmbeddedHdrInfoLabel(null))
    }

    @Test fun thePillSaysWhenPassthroughCannotBeHonoured() {
        assertEquals("Passthrough: needs Experimental HDR (Wayland)",
            linuxHdrModePillValue(DesktopHdrMode.AlwaysPassthrough, "Passthrough", experimentalHdrEnabled = false))
        assertEquals("Passthrough", linuxHdrModePillValue(DesktopHdrMode.AlwaysPassthrough, "Passthrough", experimentalHdrEnabled = true))
        assertEquals("Auto", linuxHdrModePillValue(DesktopHdrMode.Auto, "Auto", experimentalHdrEnabled = false))
    }
}
