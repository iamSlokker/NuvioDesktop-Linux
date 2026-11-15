package com.nuvio.app.features.player

import com.nuvio.app.features.player.AppShortcutAction.DismissOverlay
import com.nuvio.app.features.player.AppShortcutAction.GoBack
import java.awt.event.KeyEvent
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [dismissesOverlay] closes the overlay on top on either close bind - but never while a text field
 * holds the key, which is the case that matters: an overlay that answered Backspace over a focused
 * box would swallow the keystroke aimed at the box.
 *
 * Compose key events cannot be built without a live window (see [com.nuvio.app.core.ui.NavigationKeyTest]),
 * so the rule is tested through [shouldDismissOverlay], which is the same function the matcher calls,
 * and the two binds it asks about are covered through the binding layer.
 */
class OverlayDismissKeyTest {

    @AfterTest
    fun restoreDefaults() {
        AppShortcutsRepository.resetAll()
    }

    @Test
    fun aTextFieldKeepsBothKeysForItself() {
        assertFalse(shouldDismissOverlay(textInputActive = true, matchesDismiss = true, matchesBack = false))
        assertFalse(shouldDismissOverlay(textInputActive = true, matchesDismiss = false, matchesBack = true))
        assertFalse(shouldDismissOverlay(textInputActive = true, matchesDismiss = true, matchesBack = true))
    }

    @Test
    fun withoutAFieldEitherBindDismisses() {
        assertTrue(shouldDismissOverlay(textInputActive = false, matchesDismiss = true, matchesBack = false))
        assertTrue(shouldDismissOverlay(textInputActive = false, matchesDismiss = false, matchesBack = true))
    }

    @Test
    fun anUnrelatedKeyDismissesNothing() {
        assertFalse(shouldDismissOverlay(textInputActive = false, matchesDismiss = false, matchesBack = false))
    }

    @Test
    fun theMatcherCoversExactlyTheTwoCloseBinds() {
        assertEquals(listOf(DismissOverlay, GoBack), OVERLAY_DISMISS_ACTIONS)
    }

    @Test
    fun bothCloseBindsAreBoundByDefault() {
        assertEquals(KeyEvent.VK_ESCAPE, AppShortcutsRepository.keyCode(DismissOverlay))
        assertEquals(KeyEvent.VK_BACK_SPACE, AppShortcutsRepository.keyCode(GoBack))
    }

    @Test
    fun theTwoBindsKeepSeparateKeys() {
        assertNotEquals(
            AppShortcutsRepository.keyCode(DismissOverlay),
            AppShortcutsRepository.keyCode(GoBack),
            "the close and back binds must be different keys, or one press would be both",
        )
    }

    @Test
    fun rebindingOneCloseBindLeavesTheOtherWhereItWas() {
        AppShortcutsRepository.setBinding(DismissOverlay, KeyEvent.VK_Q)

        assertEquals(KeyEvent.VK_Q, AppShortcutsRepository.keyCode(DismissOverlay))
        assertEquals(KeyEvent.VK_BACK_SPACE, AppShortcutsRepository.keyCode(GoBack))
        // The old physical Escape no longer means "close", so the matcher must not answer it either.
        assertEquals(null, AppShortcutsRepository.actionForKeyCode(KeyEvent.VK_ESCAPE))
        assertEquals(DismissOverlay, AppShortcutsRepository.actionForKeyCode(KeyEvent.VK_Q))
        assertEquals(GoBack, AppShortcutsRepository.actionForKeyCode(KeyEvent.VK_BACK_SPACE))
    }

    @Test
    fun bothCloseBindsResolveBackFromTheirKeys() {
        for (action in OVERLAY_DISMISS_ACTIONS) {
            val code = AppShortcutsRepository.keyCode(action)
            assertEquals(action, AppShortcutsRepository.actionForKeyCode(code), "$action")
            assertTrue(code != KeyEvent.VK_UNDEFINED, "$action has no key")
        }
    }
}
