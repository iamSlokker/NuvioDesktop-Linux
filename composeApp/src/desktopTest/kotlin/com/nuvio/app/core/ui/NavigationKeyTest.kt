package com.nuvio.app.core.ui

import com.nuvio.app.features.player.AppShortcutAction
import com.nuvio.app.features.player.AppShortcutsRepository
import java.awt.event.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The bound Select, Back and Dismiss keys that the navigation sites read through
 * `appShortcutMatches`. Compose key events can't be built without a live window, so these tests
 * cover the binding layer; the per-screen key handlers need manual validation.
 */
class NavigationKeyTest {
    @AfterTest
    fun restoreDefaults() {
        AppShortcutsRepository.resetAll()
    }

    @Test
    fun defaultBindingsAreEnterBackspaceEscape() {
        assertEquals(KeyEvent.VK_ENTER, AppShortcutsRepository.keyCode(AppShortcutAction.SelectFocused))
        assertEquals(KeyEvent.VK_BACK_SPACE, AppShortcutsRepository.keyCode(AppShortcutAction.GoBack))
        assertEquals(KeyEvent.VK_ESCAPE, AppShortcutsRepository.keyCode(AppShortcutAction.DismissOverlay))
    }

    @Test
    fun directionDefaultsAreTheArrowKeys() {
        assertEquals(KeyEvent.VK_UP, AppShortcutsRepository.keyCode(AppShortcutAction.MoveUp))
        assertEquals(KeyEvent.VK_DOWN, AppShortcutsRepository.keyCode(AppShortcutAction.MoveDown))
        assertEquals(KeyEvent.VK_LEFT, AppShortcutsRepository.keyCode(AppShortcutAction.MoveLeft))
        assertEquals(KeyEvent.VK_RIGHT, AppShortcutsRepository.keyCode(AppShortcutAction.MoveRight))
    }

    @Test
    fun reboundDirectionReplacesTheArrowAndReversesLookup() {
        AppShortcutsRepository.setBinding(AppShortcutAction.MoveUp, KeyEvent.VK_I)
        assertEquals(KeyEvent.VK_I, AppShortcutsRepository.keyCode(AppShortcutAction.MoveUp))
        assertEquals(AppShortcutAction.MoveUp, AppShortcutsRepository.actionForKeyCode(KeyEvent.VK_I))
        // The historical default no longer resolves to the action.
        assertEquals(null, AppShortcutsRepository.actionForKeyCode(KeyEvent.VK_UP))
    }

    @Test
    fun reboundSelectReplacesEnterAsTheSelectKey() {
        AppShortcutsRepository.setBinding(AppShortcutAction.SelectFocused, KeyEvent.VK_SPACE)
        assertEquals(KeyEvent.VK_SPACE, AppShortcutsRepository.keyCode(AppShortcutAction.SelectFocused))
        assertEquals(AppShortcutAction.SelectFocused, AppShortcutsRepository.actionForKeyCode(KeyEvent.VK_SPACE))
    }

    @Test
    fun reboundBackAndDismissUpdateTheirKeys() {
        AppShortcutsRepository.setBinding(AppShortcutAction.GoBack, KeyEvent.VK_BACK_QUOTE)
        AppShortcutsRepository.setBinding(AppShortcutAction.DismissOverlay, KeyEvent.VK_Q)
        assertEquals(AppShortcutAction.GoBack, AppShortcutsRepository.actionForKeyCode(KeyEvent.VK_BACK_QUOTE))
        assertEquals(AppShortcutAction.DismissOverlay, AppShortcutsRepository.actionForKeyCode(KeyEvent.VK_Q))
    }

    @Test
    fun resetAllRestoresDefaults() {
        AppShortcutsRepository.setBinding(AppShortcutAction.SelectFocused, KeyEvent.VK_SPACE)
        AppShortcutsRepository.resetAll()
        assertEquals(KeyEvent.VK_ENTER, AppShortcutsRepository.keyCode(AppShortcutAction.SelectFocused))
    }
}
