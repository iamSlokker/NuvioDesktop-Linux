package com.nuvio.app.features.player.desktop

import androidx.compose.ui.window.WindowPlacement
import java.awt.Rectangle

/**
 * Bounded EDT observations. Never reveals the HUD on a placement request alone, with one exception:
 * when the deadline passes with the window placed but its content never adopted, a player with no
 * controls is a dead end, so the HUD comes back anyway.
 */
internal class LinuxPiPHudRestore(
    private val window: LinuxPictureInPictureWindow,
    private val placement: WindowPlacement,
    private val now: () -> Long,
    private val schedule: (() -> Unit) -> (() -> Unit),
    private val finished: () -> Unit,
    private val failed: (String) -> Unit,
) : AutoCloseable {
    private var cancel: (() -> Unit)? = null
    private var closed = false
    private var deadline = 0L
    private var checks = 0
    private var previous: LinuxPiPCanvasGeometry? = null
    private var observations = 0
    private val requested = mutableSetOf<Pair<Long, Rectangle>>()

    fun start() { deadline = now() + 2_000_000_000L; queue() }
    private fun queue() { if (!closed) cancel = schedule(::poll) }
    private fun fail(reason: String) { close(); failed(reason) }
    private fun poll() {
        if (closed) return
        cancel = null
        try {
            val placed = window.displayable && window.restoredPlacementReady(placement)
            // Compose's own state can lag too, so the requested placement decides, not full readiness.
            val onPlacement = window.displayable && window.livePlacement == placement
            if (++checks > 128 || now() >= deadline) {
                val stranded = onPlacement && previous == null && window.suppressHud(false)
                fail("geometry settlement timed out" + if (stranded) " with stale content; HUD revealed" else "")
                return
            }
            if (!window.displayable) { fail("player window disposed"); return }
            val canvas = if (placed) window.canvasGeometry()?.takeIf {
                it.outer == window.bounds &&
                    it.component.width == it.outer.width - it.loss.left - it.loss.right &&
                    it.component.height == it.outer.height - it.loss.top - it.loss.bottom
            } else null
            if (canvas == null || canvas != previous) {
                if (onPlacement && canvas == null && checks % RelayoutEveryChecks == 0) window.relayoutContent()
                previous = canvas; observations = 0; queue(); return
            }
            if (!window.hudAligned(canvas)) {
                observations = 0
                val key = canvas.drawable to Rectangle(canvas.root)
                if (key !in requested) {
                    if (requested.size >= 8) { fail("HUD geometry mutation budget exhausted"); return }
                    requested.add(key)
                    when (window.syncHud(canvas, (deadline - now()).coerceAtLeast(0))) {
                        LinuxPiPHudRequest.FAILED -> { fail("HUD alignment failed"); return }
                        LinuxPiPHudRequest.STALE -> { requested.remove(key); previous = null }
                        else -> Unit
                    }
                }
                queue(); return
            }
            // Native Canvas and HUD equality must survive two later observations.
            if (!window.restoredPlacementReady(placement) || window.canvasGeometry() != canvas) {
                previous = null; observations = 0; queue(); return
            }
            if (++observations < 2) { queue(); return }
            if (now() >= deadline) { fail("final verification exceeded deadline"); return }
            if (!window.suppressHud(false)) { fail("HUD reveal rejected"); return }
            // The activation asked for mid-transition can lose to the WM, and the HUD only shows on a focused window.
            window.toFront()
            close()
            finished()
        } catch (error: Exception) { fail(error.javaClass.simpleName) }
    }
    override fun close() {
        if (closed) return
        closed = true
        cancel?.invoke(); cancel = null
        window.cancelGeometry()
    }

    private companion object {
        const val RelayoutEveryChecks = 16
    }
}
