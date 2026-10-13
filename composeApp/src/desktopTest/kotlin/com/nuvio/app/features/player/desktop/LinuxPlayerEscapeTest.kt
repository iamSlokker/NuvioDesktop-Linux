package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.event.KeyEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Real AWT events without a peer/window/display, plus the production native delivery adapter. */
class LinuxPlayerEscapeTest {
    private fun key(code: Int = KeyEvent.VK_ESCAPE, id: Int = KeyEvent.KEY_PRESSED, modifiers: Int = 0) =
        KeyEvent(Canvas(), id, 1L, modifiers, code, KeyEvent.CHAR_UNDEFINED)

    @Test fun escapeIsConsumedAndDeliveredWithoutWebViewFocus() {
        val input = LinuxPlayerEscape(DesktopHostOs.LINUX)
        var calls = 0
        val event = key()
        assertTrue(input.dispatch(event, true, KeyEvent.VK_ESCAPE) { calls++ })
        assertTrue(event.isConsumed)
        assertEquals(1, calls)
        assertFalse(input.dispatch(event, true, KeyEvent.VK_ESCAPE) { calls++ })
        assertEquals(1, calls)
    }

    @Test fun heldEscapeCannotDismissPanelThenNavigateOnAutoRepeat() {
        val input = LinuxPlayerEscape(DesktopHostOs.LINUX)
        var calls = 0
        repeat(20) { assertTrue(input.dispatch(key(), true, KeyEvent.VK_ESCAPE) { calls++ }) }
        assertEquals(1, calls)
        assertTrue(input.dispatch(key(id = KeyEvent.KEY_RELEASED), true, KeyEvent.VK_ESCAPE) { calls++ })
        assertTrue(input.dispatch(key(), true, KeyEvent.VK_ESCAPE) { calls++ })
        assertEquals(2, calls)
    }

    @Test fun gamepadUsesReboundDismissKeyAndPhysicalEscapeStillWorks() {
        val input = LinuxPlayerEscape(DesktopHostOs.LINUX)
        var calls = 0
        assertTrue(input.dispatch(key(KeyEvent.VK_F9), true, KeyEvent.VK_F9) { calls++ })
        input.dispatch(key(KeyEvent.VK_F9, KeyEvent.KEY_RELEASED), true, KeyEvent.VK_F9) { calls++ }
        assertTrue(input.dispatch(key(), true, KeyEvent.VK_F9) { calls++ })
        assertEquals(2, calls)
    }

    @Test fun otherWindowsModifiersBackspaceAndUnrelatedKeysAreUntouched() {
        val input = LinuxPlayerEscape(DesktopHostOs.LINUX)
        assertFalse(input.dispatch(key(), false, KeyEvent.VK_ESCAPE) { error("Dialog must own its Escape") })
        for (code in listOf(KeyEvent.VK_BACK_SPACE, KeyEvent.VK_LEFT, KeyEvent.VK_F11)) {
            assertFalse(input.dispatch(key(code), true, KeyEvent.VK_ESCAPE) { error("Not an Escape gesture") })
        }
        for (modifier in listOf(KeyEvent.CTRL_DOWN_MASK, KeyEvent.ALT_DOWN_MASK, KeyEvent.SHIFT_DOWN_MASK, KeyEvent.META_DOWN_MASK)) {
            assertFalse(input.dispatch(key(modifiers = modifier), true, KeyEvent.VK_ESCAPE) { error("Modified shortcut") })
        }
    }

    @Test fun windowFocusChangesAndPipTransitionsDoNotLeaveAHeldKey() {
        val input = LinuxPlayerEscape(DesktopHostOs.LINUX)
        var calls = 0
        // Same player controller/dispatcher survives fullscreen and PiP. Focus can leave before key-up.
        repeat(4) {
            assertTrue(input.dispatch(key(), true, KeyEvent.VK_ESCAPE) { calls++ })
            input.reset()
        }
        assertEquals(4, calls)
    }

    @Test fun dialogKeyUpCannotLeavePlayerEscapeLatched() {
        val input = LinuxPlayerEscape(DesktopHostOs.LINUX)
        var calls = 0
        assertTrue(input.dispatch(key(), true, KeyEvent.VK_ESCAPE) { calls++ })
        assertFalse(input.dispatch(key(id = KeyEvent.KEY_RELEASED), false, KeyEvent.VK_ESCAPE) { calls++ })
        assertTrue(input.dispatch(key(), true, KeyEvent.VK_ESCAPE) { calls++ })
        assertEquals(2, calls)
    }

    @Test fun windowsMacAndUnknownNeverIntercept() {
        for (os in listOf(DesktopHostOs.WINDOWS, DesktopHostOs.MACOS, DesktopHostOs.UNKNOWN)) {
            val event = key()
            assertFalse(LinuxPlayerEscape(os).dispatch(event, true, KeyEvent.VK_ESCAPE) { error("Linux only") })
            assertFalse(event.isConsumed)
        }
    }

    @Test fun fullscreenAndWindowedUseTheExistingHudPolicyWhilePipBypassesItsSuspendedQueue() {
        val scripts = mutableListOf<Pair<Long, String>>()
        var back = 0
        val js: (Long, String) -> Unit = { handle, script -> scripts += handle to script }
        val sharedBack = { back++; Unit }
        deliverLinuxPlayerEscape(7L, false, js, sharedBack)
        deliverLinuxPlayerEscape(7L, true, { _, _ -> error("PiP suppresses HUD scripts") }, sharedBack)
        deliverLinuxPlayerEscape(7L, false, js, sharedBack)
        assertEquals(listOf(7L to LINUX_PLAYER_ESCAPE_SCRIPT, 7L to LINUX_PLAYER_ESCAPE_SCRIPT), scripts)
        assertEquals(1, back)
    }

    @Test fun loadingOrFailedAttachStillUsesSharedBackWithoutNativeCalls() {
        var back = 0
        deliverLinuxPlayerEscape(0L, false, { _, _ -> error("No native player") }) { back++ }
        assertEquals(1, back)
    }

    @Test fun controllerBackStillDeliversTheTypedActionAndDisposedControllersIgnoreIt() {
        val controller = NativePlayerController(NativePlayerHost())
        val actions = mutableListOf<com.nuvio.app.features.player.PlayerControlsAction>()
        controller.setControlCallbacks({ actions += it; true }, { _, _ -> false }, { false }, { false })
        controller.dispatchKeyboardShortcut("back")
        assertEquals(listOf(com.nuvio.app.features.player.PlayerControlsAction.Back), actions)
        controller.dispose()
        controller.dispatchKeyboardShortcut("back")
        assertEquals(1, actions.size)
    }
}
