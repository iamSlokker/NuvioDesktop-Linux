package com.nuvio.app.features.remote

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.awt.MouseInfo
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import kotlin.concurrent.thread

/** JNI surface of remote_pointer_portal.cpp, in libplayer_bridge.so. */
internal object LinuxRemotePointerNative {
    /** [status, new restore token, message]; status 0 runs, 1 refused, else failed (remote_pointer_portal.h). */
    external fun start(restoreToken: String, timeoutMs: Int): Array<String>
    external fun isActive(): Boolean
    external fun motion(dx: Double, dy: Double)
    external fun button(code: Int, pressed: Boolean)
    external fun axis(axis: Int, steps: Int)
    external fun key(code: Int, pressed: Boolean)
    external fun stop()
}

/**
 * fork:linux - the phone remote's Pointer and the controller cursor move the real cursor (PhoneRemotePointer). On a
 * Wayland desktop XTest, which java.awt.Robot uses, only moves Xwayland's own pointer: the cursor on screen stays put
 * and the next real motion snaps it back. There the input goes through the RemoteDesktop portal instead, started on
 * first use (the desktop asks once; its restore token is kept beside the app data so later runs do not ask again).
 * Until the session runs, input is dropped; if the portal is missing or refused, XTest is used as before.
 */
internal object LinuxRemotePointer {
    private val log = Logger.withTag("LinuxRemotePointer")
    private const val START_TIMEOUT_MS = 120_000
    private const val TOKEN_FILE = "remote-desktop-portal.token"

    private enum class State { Idle, Starting, Live, Fallback }

    @Volatile
    private var state = State.Idle

    val wanted: Boolean by lazy {
        DesktopHostOs.current == DesktopHostOs.LINUX && waylandSession(System.getenv()) &&
            System.getProperty("nuvio.linux.remotePointer") != "xtest"
    }

    /** True when input should go to the portal now; starts (or restarts) the session when it does not run. */
    fun ready(): Boolean {
        when (state) {
            State.Live -> {
                if (runCatching { LinuxRemotePointerNative.isActive() }.getOrDefault(false)) return true
                log.i { "Remote desktop session ended; starting it again" }
                begin()
            }
            State.Idle -> begin()
            State.Starting, State.Fallback -> Unit
        }
        return false
    }

    /** The portal is missing or was refused: send input through XTest, as before. */
    val fallback: Boolean get() = state == State.Fallback

    @Synchronized
    private fun begin() {
        if (state == State.Starting) return
        state = State.Starting
        thread(isDaemon = true, name = "remote-desktop-portal") {
            val result = runCatching {
                NativePlayerBridge.ensureNativeLibraryLoaded()
                LinuxRemotePointerNative.start(savedToken(), START_TIMEOUT_MS)
            }.getOrElse { error -> arrayOf("-1", "", error.message ?: error.toString()) }
            if (result.getOrNull(0) == "0") {
                result.getOrNull(1)?.takeIf { it.isNotBlank() }?.let(::saveToken)
                state = State.Live
                log.i { "Remote desktop portal: the pointer moves the desktop's cursor" }
            } else {
                state = State.Fallback
                log.w { "Remote desktop portal unavailable (${result.getOrNull(2).orEmpty()}); the pointer uses XTest" }
            }
        }
    }

    private fun tokenFile() = DesktopStorage.rootDir.resolve(TOKEN_FILE).toFile()

    private fun savedToken(): String = runCatching { tokenFile().readText().trim() }.getOrDefault("")

    private fun saveToken(token: String) {
        runCatching { tokenFile().apply { parentFile?.mkdirs() }.writeText(token) }
            .onFailure { log.w(it) { "Could not keep the remote desktop restore token" } }
    }
}

internal fun waylandSession(environment: Map<String, String>): Boolean =
    environment["XDG_SESSION_TYPE"].equals("wayland", ignoreCase = true) ||
        !environment["WAYLAND_DISPLAY"].isNullOrBlank()

/** Linux evdev codes for Robot's button masks (BTN_LEFT, BTN_RIGHT, BTN_MIDDLE). */
internal fun evdevButtons(buttons: Int): List<Int> = buildList {
    if (buttons and InputEvent.BUTTON1_DOWN_MASK != 0) add(272)
    if (buttons and InputEvent.BUTTON3_DOWN_MASK != 0) add(273)
    if (buttons and InputEvent.BUTTON2_DOWN_MASK != 0) add(274)
}

/** KEY_LEFTSHIFT: the only key PhoneRemotePointer presses (Shift with the wheel for sideways scrolling). */
internal fun evdevKey(keyCode: Int): Int? = if (keyCode == KeyEvent.VK_SHIFT) 42 else null

/** The Robot PhoneRemotePointer moves the cursor with: on a Wayland desktop it speaks to the portal. */
internal fun linuxPointerRobot(): Robot = if (LinuxRemotePointer.wanted) LinuxPortalRobot() else Robot()

private class LinuxPortalRobot : Robot() {
    override fun mouseMove(x: Int, y: Int) {
        if (!LinuxRemotePointer.ready()) {
            if (LinuxRemotePointer.fallback) super.mouseMove(x, y)
            return
        }
        // The portal moves relatively: from where Xwayland last saw the cursor (over Nuvio's windows, the real place).
        val at = MouseInfo.getPointerInfo()?.location ?: return
        LinuxRemotePointerNative.motion((x - at.x).toDouble(), (y - at.y).toDouble())
    }

    override fun mousePress(buttons: Int) {
        if (!LinuxRemotePointer.ready()) {
            if (LinuxRemotePointer.fallback) super.mousePress(buttons)
            return
        }
        evdevButtons(buttons).forEach { LinuxRemotePointerNative.button(it, true) }
    }

    override fun mouseRelease(buttons: Int) {
        if (!LinuxRemotePointer.ready()) {
            if (LinuxRemotePointer.fallback) super.mouseRelease(buttons)
            return
        }
        evdevButtons(buttons).forEach { LinuxRemotePointerNative.button(it, false) }
    }

    override fun mouseWheel(wheelAmt: Int) {
        if (!LinuxRemotePointer.ready()) {
            if (LinuxRemotePointer.fallback) super.mouseWheel(wheelAmt)
            return
        }
        LinuxRemotePointerNative.axis(0, wheelAmt)
    }

    override fun keyPress(keycode: Int) {
        val code = evdevKey(keycode)
        if (code == null || !LinuxRemotePointer.ready()) {
            if (code == null || LinuxRemotePointer.fallback) super.keyPress(keycode)
            return
        }
        LinuxRemotePointerNative.key(code, true)
    }

    override fun keyRelease(keycode: Int) {
        val code = evdevKey(keycode)
        if (code == null || !LinuxRemotePointer.ready()) {
            if (code == null || LinuxRemotePointer.fallback) super.keyRelease(keycode)
            return
        }
        LinuxRemotePointerNative.key(code, false)
    }
}
