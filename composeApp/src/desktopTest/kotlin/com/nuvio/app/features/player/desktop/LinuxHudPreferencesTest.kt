package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxHudPreferencesTest {
    @Test fun aReportedWriteBecomesWhatThePageReadsBack() {
        assertEquals("true", LinuxHudPreferences.stored("nuvioShowRemainingTime", 1.0))
        assertEquals("false", LinuxHudPreferences.stored("nuvioShowRemainingTime", 0.0))
        assertEquals("false", LinuxHudPreferences.stored("nuvioContextMenuCloseOnSelect", 0.0))
        assertEquals("640", LinuxHudPreferences.stored("nuvioSourcePanelWidth", 640.0))
    }

    @Test fun aRemovalOrAnUnusableValueForgetsIt() {
        assertNull(LinuxHudPreferences.stored("nuvioSourcePanelWidth", -1.0))
        assertNull(LinuxHudPreferences.stored("nuvioSourcePanelWidth", Double.NaN))
        assertNull(LinuxHudPreferences.stored("nuvioSourcePanelWidth", Double.POSITIVE_INFINITY))
    }

    @Test fun theUrlValueNeedsNoEscaping() {
        assertEquals("", LinuxHudPreferences.encode(emptyList()))
        assertEquals("nuvioShowRemainingTime:true,nuvioSourcePanelWidth:640",
            LinuxHudPreferences.encode(listOf("nuvioShowRemainingTime" to "true", "nuvioSourcePanelWidth" to "640")))
    }
}
