package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.PlayerPlaybackSnapshot
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Cursor
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.Window
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * The strip of controls Windows shows over PiP on hover: play/pause, seek, back to the player.
 *
 * Windows draws them in the WebView HUD. The Linux HUD is an override-redirect WebKit window that is
 * hidden for the whole of PiP (LinuxPictureInPictureTransition), because it maps only while the app
 * has focus and a floating window usually does not, so this is a small owned window of its own,
 * in the same way as the screensaver shade. It never takes focus.
 */
internal enum class LinuxPiPHudTarget { None, Play, Seek, Exit }

/** Where things are in a strip of [width] x [height]. No window or toolkit, so tests can ask. */
internal class LinuxPiPHudLayout(val width: Int, val height: Int) {
    private val button = minOf(height, 40)
    val play = Rectangle(0, (height - button) / 2, button, button)
    val exit = Rectangle(width - button, (height - button) / 2, button, button)
    val bar = Rectangle(button + Gap, height / 2 - BarHeight / 2, (width - 2 * button - 2 * Gap).coerceAtLeast(1), BarHeight)

    fun target(x: Int, y: Int): LinuxPiPHudTarget = when {
        play.contains(x, y) -> LinuxPiPHudTarget.Play
        exit.contains(x, y) -> LinuxPiPHudTarget.Exit
        y in 0 until height && x in play.x + play.width until exit.x -> LinuxPiPHudTarget.Seek
        else -> LinuxPiPHudTarget.None
    }

    fun fractionAt(x: Int): Double = ((x - bar.x).toDouble() / bar.width).coerceIn(0.0, 1.0)

    private companion object {
        const val Gap = 6
        const val BarHeight = 4
    }
}

internal fun linuxPiPHudBounds(host: Rectangle): Rectangle {
    val margin = 8
    val height = 40
    return Rectangle(host.x + margin, host.y + host.height - height - margin, (host.width - 2 * margin).coerceAtLeast(1), height)
}

/** A seekable position from a snapshot; null for a live stream or before the duration is known. */
internal fun linuxPiPSeekTarget(fraction: Double, durationMs: Long): Long? =
    durationMs.takeIf { it > 0 }?.let { (it * fraction.coerceIn(0.0, 1.0)).toLong() }

internal class LinuxPiPHudPanel(
    private val snapshot: () -> PlayerPlaybackSnapshot?,
    private val translucent: Boolean,
) : JPanel() {
    /** Where the seek bar is being dragged to, drawn instead of the player's position. */
    var preview: Double? = null

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val layout = LinuxPiPHudLayout(width, height)
        g2.color = if (translucent) Color(0, 0, 0, 190) else Color(20, 20, 20)
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 12f, 12f))
        val state = snapshot()
        drawPlay(g2, layout.play, playing = state?.isPlaying == true)
        drawExit(g2, layout.exit)
        val duration = state?.durationMs ?: 0L
        val bar = layout.bar
        g2.color = Color(255, 255, 255, 70)
        g2.fillRoundRect(bar.x, bar.y, bar.width, bar.height, bar.height, bar.height)
        if (duration > 0 && state != null) {
            val buffered = (state.bufferedPositionMs.toDouble() / duration).coerceIn(0.0, 1.0)
            g2.color = Color(255, 255, 255, 110)
            g2.fillRoundRect(bar.x, bar.y, (bar.width * buffered).toInt(), bar.height, bar.height, bar.height)
            val played = preview ?: (state.positionMs.toDouble() / duration).coerceIn(0.0, 1.0)
            g2.color = Color.WHITE
            g2.fillRoundRect(bar.x, bar.y, (bar.width * played).toInt(), bar.height, bar.height, bar.height)
            val knob = 10
            g2.fillOval(bar.x + (bar.width * played).toInt() - knob / 2, bar.y + bar.height / 2 - knob / 2, knob, knob)
        }
        g2.dispose()
    }

    private fun drawPlay(g: Graphics2D, r: Rectangle, playing: Boolean) {
        val cx = r.centerX.toFloat()
        val cy = r.centerY.toFloat()
        g.color = Color.WHITE
        if (playing) {
            g.fillRect((cx - 6).toInt(), (cy - 7).toInt(), 4, 14)
            g.fillRect((cx + 2).toInt(), (cy - 7).toInt(), 4, 14)
        } else {
            g.fill(Path2D.Float().apply {
                moveTo(cx - 5, cy - 8)
                lineTo(cx + 8, cy)
                lineTo(cx - 5, cy + 8)
                closePath()
            })
        }
    }

    /** The picture-in-picture glyph: a frame with the small picture in its corner. */
    private fun drawExit(g: Graphics2D, r: Rectangle) {
        val x = r.centerX.toInt() - 9
        val y = r.centerY.toInt() - 7
        g.color = Color.WHITE
        g.stroke = BasicStroke(1.6f)
        g.drawRoundRect(x, y, 18, 14, 3, 3)
        g.fillRoundRect(x + 9, y + 7, 7, 5, 2, 2)
    }
}

internal class LinuxPiPHud(
    private val host: NativePlayerHost,
    private val snapshot: () -> PlayerPlaybackSnapshot?,
    private val toggle: () -> Unit,
    private val exit: () -> Unit,
    private val seek: (Long) -> Unit,
) : AutoCloseable {
    private var strip: JWindow? = null
    private var panel: LinuxPiPHudPanel? = null
    private var owner: Window? = null
    private var hovering = false
    private var dragging = false
    private val hideTimer = Timer(HideDelayMs) { if (!hovering && !dragging) hide() }.apply { isRepeats = false }
    private val refresh = Timer(RefreshMs) { panel?.repaint() }
    private val follow = object : ComponentAdapter() {
        override fun componentMoved(e: ComponentEvent) = place()
        override fun componentResized(e: ComponentEvent) = place()
    }

    /** The pointer moved over the picture: show the strip and start the wait to hide it again. */
    fun pointerActivity() {
        val window = SwingUtilities.getWindowAncestor(host) ?: return
        val shown = strip ?: create(window)
        if (!shown.isVisible) {
            place()
            shown.isVisible = true
            refresh.start()
        }
        hideTimer.restart()
    }

    fun pointerLeft() = hideTimer.restart()

    fun hide() {
        hideTimer.stop()
        refresh.stop()
        hovering = false
        dragging = false
        panel?.preview = null
        strip?.isVisible = false
    }

    override fun close() {
        hide()
        owner?.removeComponentListener(follow)
        owner = null
        host.removeComponentListener(follow)
        strip?.dispose()
        strip = null
        panel = null
    }

    private fun place() {
        val shown = strip ?: return
        if (!host.isShowing) return
        val origin = runCatching { host.locationOnScreen }.getOrNull() ?: return
        shown.bounds = linuxPiPHudBounds(Rectangle(origin.x, origin.y, host.width, host.height))
    }

    private fun create(window: Window): JWindow {
        val translucent = runCatching {
            GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice
                .isWindowTranslucencySupported(GraphicsDevice.WindowTranslucency.PERPIXEL_TRANSLUCENT)
        }.getOrDefault(false)
        val content = LinuxPiPHudPanel(snapshot, translucent)
        val created = JWindow(window).apply {
            // As with the shade: POPUP keeps the exact owner-relative place on XWayland.
            type = Window.Type.POPUP
            focusableWindowState = false
            if (translucent) background = Color(0, 0, 0, 0)
            contentPane = content
        }
        val input = object : MouseAdapter() {
            private fun at(event: MouseEvent) = LinuxPiPHudLayout(content.width, content.height).target(event.x, event.y)
            private fun fraction(event: MouseEvent) = LinuxPiPHudLayout(content.width, content.height).fractionAt(event.x)
            override fun mouseEntered(event: MouseEvent) { hovering = true; hideTimer.stop() }
            override fun mouseExited(event: MouseEvent) { hovering = false; hideTimer.restart() }
            override fun mouseMoved(event: MouseEvent) {
                content.cursor = Cursor.getPredefinedCursor(
                    if (at(event) == LinuxPiPHudTarget.None) Cursor.DEFAULT_CURSOR else Cursor.HAND_CURSOR,
                )
            }
            override fun mousePressed(event: MouseEvent) {
                if (event.button != MouseEvent.BUTTON1) return
                when (at(event)) {
                    LinuxPiPHudTarget.Play -> toggle()
                    LinuxPiPHudTarget.Exit -> exit()
                    LinuxPiPHudTarget.Seek -> if (linuxPiPSeekTarget(0.0, snapshot()?.durationMs ?: 0L) != null) {
                        dragging = true
                        content.preview = fraction(event)
                        content.repaint()
                    }
                    LinuxPiPHudTarget.None -> Unit
                }
            }
            override fun mouseDragged(event: MouseEvent) {
                if (!dragging) return
                content.preview = fraction(event)
                content.repaint()
            }
            override fun mouseReleased(event: MouseEvent) {
                if (!dragging || event.button != MouseEvent.BUTTON1) return
                dragging = false
                content.preview = null
                linuxPiPSeekTarget(fraction(event), snapshot()?.durationMs ?: 0L)?.let(seek)
                hideTimer.restart()
            }
        }
        content.addMouseListener(input)
        content.addMouseMotionListener(input)
        window.addComponentListener(follow)
        host.addComponentListener(follow)
        owner = window
        strip = created
        panel = content
        return created
    }

    private companion object {
        const val HideDelayMs = 2_500
        const val RefreshMs = 500
    }
}
