package com.nuvio.app.features.games

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LinuxFlatpakGamesTest {
    @Test
    fun `the sandbox refuses programs and accepts launcher links`() {
        assertTrue(gameTargetOutOfReach("/home/me/Games/game.sh", sandboxed = true))
        assertTrue(gameTargetOutOfReach("/usr/share/applications/game.desktop", sandboxed = true))
        assertFalse(gameTargetOutOfReach("steam://rungameid/620", sandboxed = true))
        assertFalse(gameTargetOutOfReach(" heroic://launch/legendary/abc ", sandboxed = true))
    }

    @Test
    fun `the other packages keep every kind of target`() {
        assertFalse(gameTargetOutOfReach("/home/me/Games/game.sh", sandboxed = false))
        assertFalse(gameTargetOutOfReach("steam://rungameid/620", sandboxed = false))
    }
}
