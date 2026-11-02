package com.nuvio.app.features.screensaver

import com.nuvio.app.features.input.GamepadInput
import com.nuvio.app.features.player.desktop.LinuxPlayerWindowFocus

import java.awt.AWTEvent
import java.awt.Frame
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.abs

/** Dimming, then the shutdown countdown when that is switched on. Called on the EDT. */
internal class LinuxScreensaverController(
    private val idle: LinuxIdleSource,
    private val show: (Float) -> Unit,
    private val hide: () -> Unit,
    private val disposeShade: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val gamepadIdle: () -> Long? = { null },
    private val showCountdown: (Float, Int) -> Unit = { opacity, _ -> show(opacity) },
    private val shutdownAvailable: () -> Boolean = { false },
    /** Asks for the power-off and reports on the EDT whether it was accepted. */
    private val powerOff: ((Boolean) -> Unit) -> Unit = { it(false) },
    private val exitApplication: () -> Unit = {},
) : AutoCloseable {
    private var lastActivity = clock()
    private var closed = false
    private var countdownStartedAt: Long? = null
    private var shutdownIssued = false
    private var shutdownFailed = false

    fun activity() {
        if (closed) return
        lastActivity = clock()
        countdownStartedAt = null
        hide()
    }

    fun tick(settings: ScreensaverSettings, playerActive: Boolean, windowShowing: Boolean) {
        if (closed || shutdownIssued) return
        val now = clock()
        val sessionIdle = runCatching { idle.idleMs() }.getOrNull()?.takeIf { it >= 0 }
        val padIdle = gamepadIdle()?.takeIf { it >= 0 }
        // Controllers are absent from the session clock, and OSK-consumed input may
        // never reach AWT. Preserve either observed activity across backend loss.
        for (observedIdle in listOfNotNull(sessionIdle, padIdle)) {
            lastActivity = maxOf(lastActivity, now - observedIdle)
        }
        val idleMs = (now - lastActivity).coerceAtLeast(0)
        val allowed = windowShowing && (!playerActive || settings.activeDuringPlayback)
        // windowShowing already means focused: idle with another program in front is someone
        // using this PC, not an empty room.
        val wantShutdown = allowed && settings.shutdownEnabled && !shutdownFailed && shutdownAvailable() &&
            idleMs >= settings.shutdownDelayMinutes(playerActive) * 60_000L
        if (!wantShutdown) countdownStartedAt = null
        else if (countdownStartedAt == null) countdownStartedAt = now
        val remaining = countdownStartedAt?.let { SCREENSAVER_SHUTDOWN_COUNTDOWN_SECONDS - ((now - it) / 1_000L).toInt() }
        when {
            remaining != null && remaining <= 0 -> performShutdown()
            remaining != null -> showCountdown(maxOf(settings.dimPercent / 100f, CountdownMinOpacity), remaining)
            allowed && settings.enabled && idleMs >= settings.dimDelayMinutes(playerActive) * 60_000L ->
                show(settings.dimPercent / 100f)
            else -> hide()
        }
    }

    private fun performShutdown() {
        shutdownIssued = true
        countdownStartedAt = null
        powerOff { accepted ->
            if (accepted) {
                // Leave through the normal exit so pending writes and watch progress are flushed
                // before the session goes down, as on Windows.
                exitApplication()
            } else {
                // Nothing is going to turn the PC off, and asking again will not change that.
                shutdownIssued = false
                shutdownFailed = true
                hide()
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { idle.close() } finally { disposeShade() }
    }
}

private const val CountdownMinOpacity = 0.9f

internal object LinuxScreensaver {
    fun install(window: Window, exitApplication: () -> Unit = {}): () -> Unit {
        ScreensaverSettingsRepository.ensureLoaded()
        // The settings page asks too, on the EDT: have the answer before it gets there.
        kotlin.concurrent.thread(isDaemon = true, name = "Nuvio-LogindProbe") { LinuxPower.available }
        var shade: DesktopScreensaver.Shade? = null
        var restingPointer: Point? = null
        var closed = false
        fun showShade(opacity: Float, seconds: Int?) {
            val overlay = shade ?: createShade(window).also { created ->
                shade = created
                created.addComponentListener(object : ComponentAdapter() {
                    override fun componentHidden(e: ComponentEvent) {
                        if (!created.isVisible) LinuxPlayerWindowFocus.setDimmed(window, false)
                    }
                })
            }
            if (!overlay.isVisible) restingPointer = MouseInfo.getPointerInfo()?.location
            LinuxPlayerWindowFocus.setDimmed(window, true)
            overlay.show(opacity, seconds)
        }
        val controller = LinuxScreensaverController(
            idle = LinuxSessionIdleSource(),
            gamepadIdle = GamepadInput::idleMs,
            show = { opacity -> showShade(opacity, null) },
            showCountdown = { opacity, seconds -> showShade(opacity, seconds) },
            shutdownAvailable = { LinuxPower.available },
            powerOff = { done ->
                kotlin.concurrent.thread(isDaemon = true, name = "Nuvio-LogindPowerOff") {
                    val accepted = LinuxPower.powerOff()
                    SwingUtilities.invokeLater { done(accepted) }
                }
            },
            exitApplication = exitApplication,
            hide = {
                // Keep the resting point throughout fade-out; map/enter events aren't activity.
                shade?.fadeOut()
            },
            disposeShade = { shade?.dispose(); shade = null; LinuxPlayerWindowFocus.setDimmed(window, false) },
        )
        fun activity() {
            if (SwingUtilities.isEventDispatchThread()) controller.activity()
            else SwingUtilities.invokeLater { controller.activity() }
        }
        val listener = AWTEventListener { event ->
            if (event is InputEvent && meaningfulInput(event, restingPointer.takeIf { shade?.isVisible == true })) {
                activity()
            }
        }
        val mask = AWTEvent.MOUSE_MOTION_EVENT_MASK or AWTEvent.MOUSE_EVENT_MASK or
            AWTEvent.MOUSE_WHEEL_EVENT_MASK or AWTEvent.KEY_EVENT_MASK
        Toolkit.getDefaultToolkit().addAWTEventListener(listener, mask)
        // Player shortcuts can consume keys before toolkit AWT listeners see them.
        // Observe them first without consuming or replaying any input.
        val keyboard = KeyEventDispatcher { activity(); false }
        val focusManager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
        focusManager.addKeyEventDispatcher(keyboard)
        fun tick() {
            controller.tick(ScreensaverSettingsRepository.snapshot(), DesktopScreensaver.playerActive,
                window.isShowing && window.isFocused && ((window as? Frame)?.extendedState ?: 0) and Frame.ICONIFIED == 0)
        }
        val timer = Timer(1_000) { tick() }.apply { start() }
        val visibility = object : ComponentAdapter() {
            override fun componentHidden(e: ComponentEvent) { shade?.isVisible = false }
        }
        window.addComponentListener(visibility)
        lateinit var lifecycle: WindowAdapter
        fun uninstall() {
            if (closed) return
            closed = true
            timer.stop()
            Toolkit.getDefaultToolkit().removeAWTEventListener(listener)
            focusManager.removeKeyEventDispatcher(keyboard)
            window.removeComponentListener(visibility)
            window.removeWindowListener(lifecycle)
            window.removeWindowFocusListener(lifecycle)
            window.removeWindowStateListener(lifecycle)
            controller.close()
        }
        lifecycle = object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) = uninstall()
            override fun windowLostFocus(e: WindowEvent) {
                // POPUP bypasses WM work-area constraints and stacking. Unmap immediately
                // when another application gains focus; never float over unrelated windows.
                shade?.isVisible = false
                LinuxPlayerWindowFocus.setDimmed(window, false)
            }
            override fun windowGainedFocus(e: WindowEvent) = controller.activity()
            override fun windowIconified(e: WindowEvent) { shade?.isVisible = false }
            override fun windowStateChanged(e: WindowEvent) = tick()
        }
        window.addWindowListener(lifecycle)
        window.addWindowFocusListener(lifecycle)
        window.addWindowStateListener(lifecycle)
        return ::uninstall
    }

    internal fun createShade(window: Window): DesktopScreensaver.Shade =
        DesktopScreensaver.Shade(window).apply {
            // GNOME otherwise constrains the owned shade to the work area, even when
            // its owner is fullscreen. POPUP retains exact owner bounds on XWayland.
            type = Window.Type.POPUP
        }

    internal fun meaningfulInput(event: InputEvent, resting: Point?): Boolean = when (event) {
        is MouseWheelEvent, is KeyEvent -> true
        is MouseEvent -> when (event.id) {
            MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED -> true
            MouseEvent.MOUSE_MOVED, MouseEvent.MOUSE_DRAGGED -> resting == null ||
                abs(event.xOnScreen - resting.x) >= 3 || abs(event.yOnScreen - resting.y) >= 3
            else -> false
        }
        else -> false
    }
}
