package com.nuvio.app.core.ui

import androidx.compose.ui.input.key.Key
import com.nuvio.app.features.settings.SettingsNavKeyAction
import com.nuvio.app.features.settings.settingsNavKeyAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The action vocabulary every navigation surface reads (see [NavAction]). These tests pin the
 * mapping, so a surface that starts comparing raw keys, or a mapping that drifts away from the
 * user's rebindable actions, fails here instead of on a controller in someone's living room.
 */
class NavActionTest {

    @Test
    fun directionKeysMapToTheirActions() {
        assertEquals(NavAction.Up, navActionForKey(Key.DirectionUp))
        assertEquals(NavAction.Down, navActionForKey(Key.DirectionDown))
        assertEquals(NavAction.Left, navActionForKey(Key.DirectionLeft))
        assertEquals(NavAction.Right, navActionForKey(Key.DirectionRight))
    }

    @Test
    fun selectBackAndDismissMapToTheirCanonicalKeys() {
        // navigationKey() hands Select, Back and Dismiss over as these keys after applying the
        // user's bindings, so the funnel accepts both forms of each.
        assertEquals(NavAction.Select, navActionForKey(Key.Enter))
        assertEquals(NavAction.Select, navActionForKey(Key.NumPadEnter))
        assertEquals(NavAction.Back, navActionForKey(Key.Backspace))
        assertEquals(NavAction.Back, navActionForKey(Key.Back))
        assertEquals(NavAction.Dismiss, navActionForKey(Key.Escape))
    }

    @Test
    fun otherKeysAreNotNavigation() {
        for (key in listOf(Key.J, Key.W, Key.Spacebar, Key.PageDown, Key.Tab, Key.F3)) {
            assertNull(navActionForKey(key), "$key")
        }
    }

    @Test
    fun sidebarReadsTheSameVocabulary() {
        assertEquals(SidebarKeyAction.Move(1), sidebarKeyAction(Key.DirectionDown, index = 0, count = 3))
        assertEquals(SidebarKeyAction.Move(0), sidebarKeyAction(Key.DirectionUp, index = 1, count = 3))
        assertEquals(SidebarKeyAction.Activate, sidebarKeyAction(Key.Enter, index = 0, count = 3))
        assertEquals(SidebarKeyAction.Leave, sidebarKeyAction(Key.DirectionRight, index = 0, count = 3))
        assertEquals(SidebarKeyAction.Leave, sidebarKeyAction(Key.Escape, index = 0, count = 3))
        assertEquals(SidebarKeyAction.Leave, sidebarKeyAction(Key.Backspace, index = 0, count = 3))
        // Left is not the sidebar's: the row's outer arrow navigation steps out to the icons there.
        assertNull(sidebarKeyAction(Key.DirectionLeft, index = 0, count = 3))
    }

    @Test
    fun topBarReadsTheSameVocabulary() {
        assertEquals(TopBarKeyAction.Move(1), topBarKeyAction(Key.DirectionRight, index = 0, count = 3))
        assertEquals(TopBarKeyAction.Move(0), topBarKeyAction(Key.DirectionLeft, index = 1, count = 3))
        assertEquals(TopBarKeyAction.Activate, topBarKeyAction(Key.Enter, index = 0, count = 3))
        assertEquals(TopBarKeyAction.Leave, topBarKeyAction(Key.DirectionDown, index = 0, count = 3))
        assertEquals(TopBarKeyAction.Leave, topBarKeyAction(Key.Escape, index = 0, count = 3))
        assertEquals(TopBarKeyAction.Leave, topBarKeyAction(Key.Backspace, index = 0, count = 3))
    }

    @Test
    fun settingsReadsTheSameVocabulary() {
        assertEquals(SettingsNavKeyAction.Up, settingsNavKeyAction(Key.DirectionUp))
        assertEquals(SettingsNavKeyAction.Down, settingsNavKeyAction(Key.DirectionDown))
        assertEquals(SettingsNavKeyAction.Left, settingsNavKeyAction(Key.DirectionLeft))
        assertEquals(SettingsNavKeyAction.Right, settingsNavKeyAction(Key.DirectionRight))
        assertEquals(SettingsNavKeyAction.Activate, settingsNavKeyAction(Key.Enter))
        assertEquals(SettingsNavKeyAction.Back, settingsNavKeyAction(Key.Escape))
        assertEquals(SettingsNavKeyAction.Back, settingsNavKeyAction(Key.Backspace))
    }

    @Test
    fun tabHandoffReadsTheSameVocabulary() {
        assertEquals(TabHandoffKey.Escape, tabHandoffKey(Key.Escape, homeSelected = false))
        assertNull(tabHandoffKey(Key.Escape, homeSelected = true))
        assertEquals(TabHandoffKey.Back, tabHandoffKey(Key.Backspace, homeSelected = false))
        assertEquals(TabHandoffKey.Left, tabHandoffKey(Key.DirectionLeft, homeSelected = false))
        assertEquals(TabHandoffKey.Up, tabHandoffKey(Key.DirectionUp, homeSelected = false, topBarAvailable = true))
        assertNull(tabHandoffKey(Key.DirectionUp, homeSelected = false, topBarAvailable = false))
    }

    /**
     * The deciders take canonical keys, never physical ones: W, A, S, D and any other key the user
     * binds arrive here already turned into the direction they stand for (see [navigationKey]). A
     * decider that matched physical keys would ignore the rebind; these nulls are what prove it does not.
     */
    @Test
    fun decidersDoNotMatchPhysicalKeysDirectly() {
        for (key in listOf(Key.W, Key.A, Key.S, Key.D, Key.J, Key.Spacebar)) {
            assertNull(sidebarKeyAction(key, index = 0, count = 3), "$key sidebar")
            assertNull(topBarKeyAction(key, index = 0, count = 3), "$key top bar")
            assertNull(settingsNavKeyAction(key), "$key settings")
            assertNull(tabHandoffKey(key, homeSelected = false), "$key hand-off")
        }
    }
}
