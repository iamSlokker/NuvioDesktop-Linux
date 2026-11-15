package com.nuvio.app.features.player.desktop

import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxHudNavTest {
    private val select = KeyEvent.VK_ENTER
    private val playPause = KeyEvent.VK_SPACE

    private val dismiss = KeyEvent.VK_ESCAPE
    private val keys = HudNavKeyBindings(
        select = select,
        playPause = playPause,
        dismiss = dismiss,
        up = KeyEvent.VK_UP,
        down = KeyEvent.VK_DOWN,
        left = KeyEvent.VK_LEFT,
        right = KeyEvent.VK_RIGHT,
    )
    private fun route(keyCode: Int, state: Int) = linuxHudNavKey(keyCode, state, keys)

    @Test
    fun unavailableStateKeepsEveryKeysNormalMeaning() {
        for (key in listOf(KeyEvent.VK_LEFT, KeyEvent.VK_UP, select, playPause)) {
            assertNull(route(key, LinuxHudNavState.UNAVAILABLE), "key $key")
        }
    }

    @Test
    fun hiddenHudLeavesArrowsToSeekAndVolumeAndSelectRevealsIt() {
        assertNull(route(KeyEvent.VK_LEFT, LinuxHudNavState.HIDDEN))
        assertNull(route(KeyEvent.VK_DOWN, LinuxHudNavState.HIDDEN))
        assertEquals("Enter", route(select, LinuxHudNavState.HIDDEN))
        assertNull(route(playPause, LinuxHudNavState.HIDDEN))
    }

    @Test
    fun visibleHudDrivesArrowsAndSelectButNotPlayPause() {
        assertEquals("ArrowLeft", route(KeyEvent.VK_LEFT, LinuxHudNavState.VISIBLE))
        assertEquals("ArrowRight", route(KeyEvent.VK_RIGHT, LinuxHudNavState.VISIBLE))
        assertEquals("ArrowUp", route(KeyEvent.VK_UP, LinuxHudNavState.VISIBLE))
        assertEquals("ArrowDown", route(KeyEvent.VK_DOWN, LinuxHudNavState.VISIBLE))
        assertEquals("Enter", route(select, LinuxHudNavState.VISIBLE))
        assertNull(route(playPause, LinuxHudNavState.VISIBLE))
    }

    @Test
    fun focusedControlAlsoActivatesOnPlayPause() {
        assertEquals("ArrowLeft", route(KeyEvent.VK_LEFT, LinuxHudNavState.FOCUSED))
        assertEquals("Enter", route(select, LinuxHudNavState.FOCUSED))
        assertEquals("Enter", route(playPause, LinuxHudNavState.FOCUSED))
    }

    @Test
    fun theVolumeSliderTakesAllArrowsAndEscapeLeavesIt() {
        assertEquals("ArrowLeft", route(KeyEvent.VK_LEFT, LinuxHudNavState.VOLUME_FOCUSED))
        assertEquals("ArrowRight", route(KeyEvent.VK_RIGHT, LinuxHudNavState.VOLUME_FOCUSED))
        assertEquals("ArrowUp", route(KeyEvent.VK_UP, LinuxHudNavState.VOLUME_FOCUSED))
        assertEquals("ArrowDown", route(KeyEvent.VK_DOWN, LinuxHudNavState.VOLUME_FOCUSED))
        assertEquals("Escape", route(dismiss, LinuxHudNavState.VOLUME_FOCUSED))
        assertNull(route(dismiss, LinuxHudNavState.VISIBLE))
        assertNull(route(dismiss, LinuxHudNavState.FOCUSED))
    }

    @Test
    fun unrelatedKeysAreNeverRouted() {
        for (state in listOf(LinuxHudNavState.HIDDEN, LinuxHudNavState.VISIBLE, LinuxHudNavState.FOCUSED)) {
            assertNull(route(KeyEvent.VK_A, state))
            assertNull(route(KeyEvent.VK_ESCAPE, state))
        }
    }

    @Test
    fun reboundSelectAndPlayPauseFollowTheBindings() {
        val rebound = keys.copy(select = KeyEvent.VK_SPACE, playPause = KeyEvent.VK_K)
        assertEquals("Enter", linuxHudNavKey(KeyEvent.VK_SPACE, LinuxHudNavState.VISIBLE, rebound))
        assertEquals("Enter", linuxHudNavKey(KeyEvent.VK_K, LinuxHudNavState.FOCUSED, rebound))
        assertNull(linuxHudNavKey(KeyEvent.VK_K, LinuxHudNavState.VISIBLE, rebound))
        // The historical default stops acting once the action has moved off it.
        assertNull(linuxHudNavKey(KeyEvent.VK_ENTER, LinuxHudNavState.VISIBLE, rebound))
    }

    @Test
    fun theArrowKeysFollowTheMoveBindings() {
        // A keyboard without an arrow cluster: the four directions live on W/A/S/D (TKL mode's
        // keys, but bound as the Move actions, which is what the rest of the app navigates with).
        val wasd = keys.copy(
            up = KeyEvent.VK_W,
            down = KeyEvent.VK_S,
            left = KeyEvent.VK_A,
            right = KeyEvent.VK_D,
        )
        assertEquals("ArrowUp", linuxHudNavKey(KeyEvent.VK_W, LinuxHudNavState.VISIBLE, wasd))
        assertEquals("ArrowDown", linuxHudNavKey(KeyEvent.VK_S, LinuxHudNavState.VISIBLE, wasd))
        assertEquals("ArrowLeft", linuxHudNavKey(KeyEvent.VK_A, LinuxHudNavState.VISIBLE, wasd))
        assertEquals("ArrowRight", linuxHudNavKey(KeyEvent.VK_D, LinuxHudNavState.VISIBLE, wasd))
        // ...and the physical arrows no longer navigate, as everywhere else in the app.
        assertNull(linuxHudNavKey(KeyEvent.VK_UP, LinuxHudNavState.VISIBLE, wasd))
        assertNull(linuxHudNavKey(KeyEvent.VK_LEFT, LinuxHudNavState.VISIBLE, wasd))
    }
}

/** Up/Down routing with "Player volume control" on and off. Left/Right never reach volume. */
class LinuxVolumeKeyRoutingTest {
    private data class Row(val key: Int, val enabled: Boolean, val expected: String?)

    private val truthTable = listOf(
        Row(KeyEvent.VK_UP, enabled = true, expected = "volumeUp"),
        Row(KeyEvent.VK_DOWN, enabled = true, expected = "volumeDown"),
        Row(KeyEvent.VK_UP, enabled = false, expected = null),
        Row(KeyEvent.VK_DOWN, enabled = false, expected = null),
        Row(KeyEvent.VK_LEFT, enabled = true, expected = null),
        Row(KeyEvent.VK_RIGHT, enabled = true, expected = null),
        Row(KeyEvent.VK_LEFT, enabled = false, expected = null),
        Row(KeyEvent.VK_RIGHT, enabled = false, expected = null),
    )

    @Test
    fun routingTruthTable() {
        for (row in truthTable) {
            assertEquals(row.expected, linuxVolumeKeyType(row.key, row.enabled), "key ${row.key} enabled ${row.enabled}")
        }
    }
}

class LinuxHudSelectRepeatGuardTest {
    @Test
    fun aHeldKeyIsAcceptedOnceUntilReleased() {
        val guard = LinuxHudSelectRepeatGuard()
        assertEquals(true, guard.accept(KeyEvent.VK_ENTER))
        assertEquals(false, guard.accept(KeyEvent.VK_ENTER))
        assertEquals(false, guard.accept(KeyEvent.VK_ENTER))
        guard.release(KeyEvent.VK_ENTER)
        assertEquals(true, guard.accept(KeyEvent.VK_ENTER))
    }

    @Test
    fun keysAreTrackedIndependently() {
        val guard = LinuxHudSelectRepeatGuard()
        assertEquals(true, guard.accept(KeyEvent.VK_ENTER))
        assertEquals(true, guard.accept(KeyEvent.VK_SPACE))
        guard.release(KeyEvent.VK_SPACE)
        assertEquals(false, guard.accept(KeyEvent.VK_ENTER))
    }
}

class PlayerOwnsKeysTest {
    @Test
    fun ownsKeysOnlyWhenTheVideoFillsTheWindow() {
        assertEquals(true, playerOwnsKeys(miniPlayer = false, pictureInPicture = false, browsePip = false))
        assertEquals(false, playerOwnsKeys(miniPlayer = true, pictureInPicture = false, browsePip = false))
        assertEquals(false, playerOwnsKeys(miniPlayer = false, pictureInPicture = true, browsePip = false))
        assertEquals(false, playerOwnsKeys(miniPlayer = false, pictureInPicture = false, browsePip = true))
        assertEquals(false, playerOwnsKeys(miniPlayer = true, pictureInPicture = true, browsePip = true))
    }
}

class YouTubeWatchFocusAfterTest {
    private val keys = HudNavKeyBindings(
        select = KeyEvent.VK_ENTER,
        playPause = KeyEvent.VK_SPACE,
        dismiss = KeyEvent.VK_ESCAPE,
        up = KeyEvent.VK_UP,
        down = KeyEvent.VK_DOWN,
        left = KeyEvent.VK_LEFT,
        right = KeyEvent.VK_RIGHT,
    )

    @Test
    fun downFromTheHudBottomRowGoesToThePage() {
        assertEquals(
            YouTubeWatchFocusLocation.PageFirstControl,
            youTubeWatchFocusAfter(YouTubeWatchFocusLocation.HudBottomRow, KeyEvent.VK_DOWN, keys),
        )
    }

    @Test
    fun upFromThePageFirstControlReturnsToTheHud() {
        assertEquals(
            YouTubeWatchFocusLocation.HudBottomRow,
            youTubeWatchFocusAfter(YouTubeWatchFocusLocation.PageFirstControl, KeyEvent.VK_UP, keys),
        )
    }

    @Test
    fun everyOtherPressHasNoCrossBoundaryMeaning() {
        assertNull(youTubeWatchFocusAfter(YouTubeWatchFocusLocation.HudBottomRow, KeyEvent.VK_UP, keys))
        assertNull(youTubeWatchFocusAfter(YouTubeWatchFocusLocation.HudBottomRow, KeyEvent.VK_LEFT, keys))
        assertNull(youTubeWatchFocusAfter(YouTubeWatchFocusLocation.PageFirstControl, KeyEvent.VK_DOWN, keys))
        assertNull(youTubeWatchFocusAfter(YouTubeWatchFocusLocation.PageFirstControl, KeyEvent.VK_RIGHT, keys))
        assertNull(youTubeWatchFocusAfter(YouTubeWatchFocusLocation.PageFirstControl, KeyEvent.VK_ENTER, keys))
    }
}
