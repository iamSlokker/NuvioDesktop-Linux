package com.nuvio.app.features.player.desktop

import java.awt.Cursor
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import javax.swing.Timer
import kotlin.math.hypot

/**
 * Which handle a point in a picture of [width] x [height] is on, numbered as the HUD's `data-pip-resize`:
 * 1 top, going clockwise to 8 top left; 0 away from the edges. The eight strips are invisible, as they
 * are on Windows, and the cursor is what shows them.
 */
internal fun linuxPiPResizeEdge(x: Int, y: Int, width: Int, height: Int, zone: Int = LinuxPiPResizeZone): Int {
    val top = y < zone
    val bottom = y >= height - zone
    val left = x < zone
    val right = x >= width - zone
    return when {
        top && left -> 8
        top && right -> 2
        bottom && right -> 4
        bottom && left -> 6
        top -> 1
        right -> 3
        bottom -> 5
        left -> 7
        else -> 0
    }
}

internal const val LinuxPiPResizeZone = 8

/** The window after dragging handle [edge] by ([dx], [dy]); the opposite side stays where it is. */
internal fun linuxPiPResizedBounds(start: Rectangle, edge: Int, dx: Int, dy: Int, minWidth: Int = 240, minHeight: Int = 135): Rectangle {
    val bounds = Rectangle(start)
    if (edge == 2 || edge == 3 || edge == 4) bounds.width = (start.width + dx).coerceAtLeast(minWidth)
    if (edge == 4 || edge == 5 || edge == 6) bounds.height = (start.height + dy).coerceAtLeast(minHeight)
    if (edge == 6 || edge == 7 || edge == 8) {
        bounds.width = (start.width - dx).coerceAtLeast(minWidth)
        bounds.x = start.x + start.width - bounds.width
    }
    if (edge == 8 || edge == 1 || edge == 2) {
        bounds.height = (start.height - dy).coerceAtLeast(minHeight)
        bounds.y = start.y + start.height - bounds.height
    }
    return bounds
}

internal fun linuxPiPResizeCursor(edge: Int): Int = when (edge) {
    1 -> Cursor.N_RESIZE_CURSOR
    2 -> Cursor.NE_RESIZE_CURSOR
    3 -> Cursor.E_RESIZE_CURSOR
    4 -> Cursor.SE_RESIZE_CURSOR
    5 -> Cursor.S_RESIZE_CURSOR
    6 -> Cursor.SW_RESIZE_CURSOR
    7 -> Cursor.W_RESIZE_CURSOR
    8 -> Cursor.NW_RESIZE_CURSOR
    else -> Cursor.DEFAULT_CURSOR
}

/** EDT-owned policy; no native player, toolkit, or window access. */
internal class LinuxPiPPointer(
    private val toggle: () -> Unit,
    private val fullscreen: () -> Unit,
    private val move: (Point) -> Unit,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val schedule: (Int, () -> Unit) -> (() -> Unit) = { delay, action ->
        val timer = Timer(delay) { action() }.apply { isRepeats = false; start() }
        val cancel: () -> Unit = { timer.stop() }
        cancel
    },
    private val resize: (Rectangle) -> Unit = {},
) : AutoCloseable {
    private var resizeEdge = 0
    private var resizeStart = Rectangle()
    private var active = false
    private var closed = false
    private var generation = 0L
    private var cancel: (() -> Unit)? = null
    private var pendingAt: Long? = null
    private var down: Point? = null
    private var origin = Point()
    private var dragging = false
    private var second = false
    private var scaleX = 1.0
    private var scaleY = 1.0

    fun setActive(value: Boolean) {
        if (closed || active == value) return
        reset()
        active = value
    }

    fun press(screen: Point, window: Point, sx: Double = 1.0, sy: Double = 1.0) {
        if (!active || closed || down != null) return
        second = pendingAt?.let { now() - it in 0..DelayMs.toLong() } == true
        if (second) cancelSingle()
        down = Point(screen)
        origin = Point(window)
        scaleX = sx; scaleY = sy
        dragging = false
    }

    /** A press on a handle: a resize from the first pixel, and never a click. */
    fun pressResize(screen: Point, window: Rectangle, edge: Int) {
        if (!active || closed || down != null) return
        cancelSingle()
        down = Point(screen)
        resizeEdge = edge
        resizeStart = Rectangle(window)
    }

    fun motion(screen: Point) {
        val start = down ?: return
        if (!active || closed) return
        val dx = screen.x - start.x; val dy = screen.y - start.y
        if (resizeEdge != 0) {
            resize(linuxPiPResizedBounds(resizeStart, resizeEdge, dx, dy))
            return
        }
        if (!dragging && hypot(dx * scaleX, dy * scaleY) >= ThresholdPixels) {
            dragging = true
            second = false
            cancelSingle()
        }
        if (dragging) move(Point(origin.x + dx, origin.y + dy))
    }

    fun release(screen: Point) {
        if (!active || closed || down == null) return
        motion(screen)
        down = null
        if (resizeEdge != 0) { resizeEdge = 0; second = false; return }
        if (dragging) { dragging = false; second = false; return }
        if (second) {
            setActive(false) // Triple clicks and reentrant callbacks cannot arm another action.
            fullscreen()
            return
        }
        cancelSingle()
        pendingAt = now()
        val token = generation
        cancel = schedule(DelayMs) {
            if (closed || !active || token != generation || pendingAt == null) return@schedule
            cancel = null
            pendingAt = null
            toggle()
        }
    }

    fun reset() {
        cancelSingle()
        down = null; dragging = false; second = false; resizeEdge = 0
    }
    private fun cancelSingle() {
        ++generation
        cancel?.invoke(); cancel = null; pendingAt = null
    }
    override fun close() { reset(); active = false; closed = true }

    companion object {
        const val DelayMs = 220
        const val ThresholdPixels = 6.0
    }
}

/** One listener per Linux player controller, attached to its existing Canvas only. */
internal class LinuxPiPSurfaceInput(
    private val host: NativePlayerHost,
    toggle: () -> Unit,
    private val hud: LinuxPiPHud? = null,
) : AutoCloseable {
    companion object {
        fun install(os: DesktopHostOs, host: NativePlayerHost, hud: LinuxPiPHud? = null, toggle: () -> Unit): LinuxPiPSurfaceInput? =
            if (os == DesktopHostOs.LINUX) LinuxPiPSurfaceInput(host, toggle, hud) else null
    }
    private fun current() = host.linuxPiPInteractive && host.isDisplayable && desktopPictureInPictureState.value
    private val policy = LinuxPiPPointer(toggle = { if (current()) toggle() },
        fullscreen = { host.onLinuxPiPFullscreen?.invoke() },
        move = { host.onLinuxPiPMove?.invoke(it) },
        resize = { bounds ->
            SwingUtilities.getWindowAncestor(host)?.bounds = bounds
            // The move hook also syncs Compose's window state, size included, as it does for a drag.
            host.onLinuxPiPMove?.invoke(bounds.location)
        })
    private fun edgeOf(event: MouseEvent) = linuxPiPResizeEdge(event.x, event.y, host.width, host.height)
    private val listener = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) {
            if (!current() || event.button != MouseEvent.BUTTON1) return
            val window = SwingUtilities.getWindowAncestor(host) ?: return
            val scale = host.graphicsConfiguration?.defaultTransform
            val edge = edgeOf(event)
            if (edge != 0) policy.pressResize(event.locationOnScreen, window.bounds, edge)
            else policy.press(event.locationOnScreen, window.location, scale?.scaleX ?: 1.0, scale?.scaleY ?: 1.0)
            event.consume()
        }
        override fun mouseDragged(event: MouseEvent) {
            if (!current()) return
            policy.motion(event.locationOnScreen)
            event.consume()
        }
        override fun mouseReleased(event: MouseEvent) {
            if (!current() || event.button != MouseEvent.BUTTON1) return
            policy.release(event.locationOnScreen)
            event.consume()
        }
        override fun mouseMoved(event: MouseEvent) {
            if (!current()) return
            host.cursor = Cursor.getPredefinedCursor(linuxPiPResizeCursor(edgeOf(event)))
            hud?.pointerActivity()
        }
        override fun mouseEntered(event: MouseEvent) { if (current()) hud?.pointerActivity() }
        override fun mouseExited(event: MouseEvent) { hud?.pointerLeft() }
    }
    init {
        host.onLinuxPiPInteractiveChanged = { interactive ->
            policy.setActive(interactive)
            if (!interactive) {
                host.cursor = Cursor.getDefaultCursor()
                hud?.hide()
            }
        }
        host.onLinuxPiPCancelGesture = policy::reset
        host.addMouseListener(listener)
        host.addMouseMotionListener(listener)
        policy.setActive(host.linuxPiPInteractive)
    }
    override fun close() {
        policy.close()
        hud?.close()
        host.removeMouseListener(listener)
        host.removeMouseMotionListener(listener)
        host.onLinuxPiPInteractiveChanged = null
        host.onLinuxPiPCancelGesture = null
    }
}
