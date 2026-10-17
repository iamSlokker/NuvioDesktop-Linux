package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import java.awt.Color
import java.awt.Graphics
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent

internal class NativePlayerHost : Canvas() {
    // Linux-only hooks; the PiP coordinator owns activation and window movement.
    var linuxPiPInteractive = false
        set(value) { if (field != value) { field = value; onLinuxPiPInteractiveChanged?.invoke(value) } }
    var onLinuxPiPInteractiveChanged: ((Boolean) -> Unit)? = null
    var onLinuxPiPCancelGesture: (() -> Unit)? = null
    var onLinuxPiPMove: ((java.awt.Point) -> Unit)? = null
    var onLinuxPiPFullscreen: (() -> Unit)? = null
    var onPeerReady: (() -> Unit)? = null
    var onDisplayableChanged: ((Boolean) -> Unit)? = null
    var onFirstPaint: (() -> Unit)? = null
    var onFirstFullSizePaint: (() -> Unit)? = null
    private var firstPaintNotified = false
    private var firstFullSizePaintNotified = false
    // Linux mpv owns an X11 child of this peer. Release it before AWT destroys
    // the Canvas (and then its parent window), regardless of Compose disposal order.
    var onBeforeLinuxPeerRemoval: (() -> Unit)? = null

    init {
        background = Color.BLACK
        ignoreRepaint = false
        // The attach waits on a full-size paint, but Windows does not always send one: re-entering
        // the player shortly after leaving it painted once at 0x0 and then got no paint for the
        // resize, leaving playback stalled until something else exposed the window. Ask for one.
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                if (!firstFullSizePaintNotified && width > 1 && height > 1) repaint()
            }
        })
    }

    override fun update(graphics: Graphics) {
        paint(graphics)
    }

    override fun paint(graphics: Graphics) {
        graphics.color = Color.BLACK
        graphics.fillRect(0, 0, width, height)
        if (!firstPaintNotified) {
            firstPaintNotified = true
            onFirstPaint?.invoke()
        }
        if (!firstFullSizePaintNotified && width > 1 && height > 1) {
            firstFullSizePaintNotified = true
            onFirstFullSizePaint?.invoke()
        }
    }

    /**
     * Last resort for when the requested repaint never arrives either: if the surface is on screen
     * at full size, treat it as painted. Call on the EDT. Returns true when this call fired it.
     */
    fun notifyFullSizeIfReady(): Boolean {
        if (firstFullSizePaintNotified || !isShowing || width <= 1 || height <= 1) return false
        firstPaintNotified = true
        firstFullSizePaintNotified = true
        onFirstFullSizePaint?.invoke()
        return true
    }

    override fun addNotify() {
        super.addNotify()
        onDisplayableChanged?.invoke(true)
        repaint()
        onPeerReady?.invoke()
    }

    override fun removeNotify() {
        if (DesktopHostOs.current == DesktopHostOs.LINUX) onBeforeLinuxPeerRemoval?.invoke()
        onDisplayableChanged?.invoke(false)
        firstPaintNotified = false
        firstFullSizePaintNotified = false
        onPeerReady = null
        onFirstPaint = null
        onFirstFullSizePaint = null
        super.removeNotify()
    }
}
