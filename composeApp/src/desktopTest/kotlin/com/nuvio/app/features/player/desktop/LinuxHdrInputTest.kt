package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.PlayerShortcutAction
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LinuxHdrInputTest {
    private val playerBindings = mapOf(
        KeyEvent.VK_SPACE to PlayerShortcutAction.PlayPause,
        KeyEvent.VK_K to PlayerShortcutAction.AlternatePlayPause,
        KeyEvent.VK_M to PlayerShortcutAction.ToggleMute,
        KeyEvent.VK_J to PlayerShortcutAction.SeekBackward,
        KeyEvent.VK_L to PlayerShortcutAction.SeekForward,
        KeyEvent.VK_CLOSE_BRACKET to PlayerShortcutAction.SpeedUp,
        KeyEvent.VK_OPEN_BRACKET to PlayerShortcutAction.SpeedDown,
        KeyEvent.VK_BACK_SPACE to PlayerShortcutAction.ToggleSpeed,
        KeyEvent.VK_S to PlayerShortcutAction.NextSubtitle,
        KeyEvent.VK_A to PlayerShortcutAction.NextAudio,
        KeyEvent.VK_E to PlayerShortcutAction.OpenEpisodes,
        KeyEvent.VK_O to PlayerShortcutAction.OpenSources,
        KeyEvent.VK_Z to PlayerShortcutAction.CycleZoom,
        KeyEvent.VK_N to PlayerShortcutAction.SkipInterval,
        KeyEvent.VK_F7 to PlayerShortcutAction.CycleSvp,
        KeyEvent.VK_H to PlayerShortcutAction.CycleHdr,
        KeyEvent.VK_C to PlayerShortcutAction.CycleColorProfile,
        KeyEvent.VK_F10 to PlayerShortcutAction.CycleAnime,
        KeyEvent.VK_I to PlayerShortcutAction.ToggleMpvDiagnostics,
    )
    private val appKeys = mapOf(
        AppShortcutAction.ToggleFullscreen to KeyEvent.VK_F,
        AppShortcutAction.DismissOverlay to KeyEvent.VK_ESCAPE,
        AppShortcutAction.GoBack to KeyEvent.VK_BACK_QUOTE,
        AppShortcutAction.SelectFocused to KeyEvent.VK_ENTER,
    )

    private fun action(code: Int) = linuxHdrInputAction(code, playerBindings::get) { appKeys[it] ?: KeyEvent.VK_UNDEFINED }

    @Test
    fun keysMapToTheSameEventsAsTheEmbeddedPlayerDispatcher() {
        assertEquals(LinuxHdrInputAction.Event("keyboardToggle"), action(KeyEvent.VK_SPACE))
        assertEquals(LinuxHdrInputAction.Event("keyboardToggle"), action(KeyEvent.VK_K))
        assertEquals(LinuxHdrInputAction.Event("keyboardToggleMute"), action(KeyEvent.VK_M))
        assertEquals(LinuxHdrInputAction.Event("keyboardSeekBack"), action(KeyEvent.VK_LEFT))
        assertEquals(LinuxHdrInputAction.Event("keyboardSeekForward"), action(KeyEvent.VK_RIGHT))
        assertEquals(LinuxHdrInputAction.Event("keyboardSeekBack"), action(KeyEvent.VK_J))
        assertEquals(LinuxHdrInputAction.Event("keyboardSeekForward"), action(KeyEvent.VK_L))
        assertEquals(LinuxHdrInputAction.Event("volumeUp"), action(KeyEvent.VK_UP))
        assertEquals(LinuxHdrInputAction.Event("volumeDown"), action(KeyEvent.VK_DOWN))
        assertEquals(LinuxHdrInputAction.Event("keyboardSpeedStep", 1.0), action(KeyEvent.VK_CLOSE_BRACKET))
        assertEquals(LinuxHdrInputAction.Event("keyboardSpeedStep", -1.0), action(KeyEvent.VK_OPEN_BRACKET))
        assertEquals(LinuxHdrInputAction.Event("keyboardSpeedToggle"), action(KeyEvent.VK_BACK_SPACE))
        assertEquals(LinuxHdrInputAction.Event("keyboardNextSubtitle"), action(KeyEvent.VK_S))
        assertEquals(LinuxHdrInputAction.Event("keyboardNextAudio"), action(KeyEvent.VK_A))
        assertEquals(LinuxHdrInputAction.Event("resize"), action(KeyEvent.VK_Z))
        assertEquals(LinuxHdrInputAction.Event("keyboardToggle"), action(KeyEvent.VK_ENTER))
    }

    @Test
    fun backFullscreenAndHudOnlyActionsAreExplicit() {
        assertEquals(LinuxHdrInputAction.Back, action(KeyEvent.VK_ESCAPE))
        assertEquals(LinuxHdrInputAction.Back, action(KeyEvent.VK_BACK_QUOTE))
        assertEquals(LinuxHdrInputAction.LeaveHdr, action(KeyEvent.VK_F))
        assertEquals(LinuxHdrInputAction.LeaveHdr, action(LINUX_HDR_KEY_LEAVE))
        assertEquals(LinuxHdrInputAction.LeaveHdr, action(KeyEvent.VK_E))
        assertEquals(LinuxHdrInputAction.LeaveHdr, action(KeyEvent.VK_O))
        assertEquals(LinuxHdrInputAction.SkipInterval, action(KeyEvent.VK_N))
        assertEquals(LinuxHdrInputAction.CycleHdr, action(KeyEvent.VK_H))
        assertEquals(LinuxHdrInputAction.ToggleDiagnostics, action(KeyEvent.VK_I))
        for (code in listOf(KeyEvent.VK_F7, KeyEvent.VK_C, KeyEvent.VK_F10)) assertIs<LinuxHdrInputAction.Unavailable>(action(code))
        assertEquals(LinuxHdrInputAction.Ignored, action(KeyEvent.VK_Q))
    }

    @Test
    fun bindingTableIsUniqueAndSafeForTheNativeInstaller() {
        val names = linuxHdrMpvKeyBindings.map { it.first }
        assertEquals(names.size, names.toSet().size)
        for ((name, code) in linuxHdrMpvKeyBindings) {
            // Mirrors parseLinuxHdrKeyBinding in hdr_wayland_session.cpp.
            assertTrue(name.isNotEmpty() && name.length <= 32, name)
            assertTrue(name.all { it.code in 0x21..0x7e && it !in "\"'\\#" }, name)
            assertTrue(code == LINUX_HDR_KEY_LEAVE || code > 0, name)
        }
        val entries = linuxHdrMpvKeyBindingEntries()
        assertTrue("SPACE=${KeyEvent.VK_SPACE}" in entries)
        assertTrue("==${KeyEvent.VK_EQUALS}" in entries)
        assertTrue("CLOSE_WIN=$LINUX_HDR_KEY_LEAVE" in entries)
        // Media keys stay with MPRIS so they cannot act twice.
        assertFalse(names.any { it in setOf("PLAY", "PAUSE", "PLAYPAUSE", "NEXT", "PREV", "STOP", "MUTE") })
    }

    @Test
    fun gamepadRouteDeliversOnlyToTheInstalledSession() {
        assertFalse(LinuxHdrInputRoute.deliver(KeyEvent.VK_SPACE))
        val received = mutableListOf<Int>()
        val sink: (Int) -> Boolean = { received += it; true }
        LinuxHdrInputRoute.install(sink)
        try {
            assertTrue(LinuxHdrInputRoute.deliver(KeyEvent.VK_SPACE))
            assertEquals(listOf(KeyEvent.VK_SPACE), received)
            LinuxHdrInputRoute.clear { true } // A different sink cannot clear the active one.
            assertTrue(LinuxHdrInputRoute.deliver(KeyEvent.VK_LEFT))
        } finally {
            LinuxHdrInputRoute.clear(sink)
        }
        assertFalse(LinuxHdrInputRoute.deliver(KeyEvent.VK_SPACE))
        assertEquals(listOf(KeyEvent.VK_SPACE, KeyEvent.VK_LEFT), received)
    }
}
