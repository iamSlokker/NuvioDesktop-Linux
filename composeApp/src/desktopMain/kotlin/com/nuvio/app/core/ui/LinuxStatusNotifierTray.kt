package com.nuvio.app.core.ui

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.DesktopIdleHeapTrim
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.awt.EventQueue
import java.awt.Frame
import java.awt.SystemTray
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

internal object LinuxTrayNative {
    external fun available(): Boolean
    external fun open(title: String, openLabel: String, info: String, exitLabel: String, sizes: IntArray, icons: ByteArray): Long
    external fun next(handle: Long, timeoutMs: Int): Int
    external fun close(handle: Long)
}

/** What AWT's `TrayIcon` cannot give Linux: AWT only speaks XEmbed, and GNOME has no XEmbed tray. */
internal object LinuxStatusNotifierTray {
    const val EventOpen = 1
    const val EventExit = 2
    const val EventHostLost = 3
    const val EventHostBack = 4

    private val IconSizes = intArrayOf(22, 32, 48, 64)
    private const val PollMs = 250
    private val log = Logger.withTag("LinuxTray")

    private fun nativeAvailable(): Boolean = runCatching {
        NativePlayerBridge.ensureNativeLibraryLoaded()
        LinuxTrayNative.available()
    }.getOrDefault(false)

    /**
     * Whether "close to tray" can leave the window anywhere on this desktop. Asked once: the answer
     * decides a settings row, and an extension switched on later takes a restart to be offered.
     * The sandbox hides the watcher from the Flatpak, so there only AWT's XEmbed tray can answer yes.
     */
    val trayExists: Boolean by lazy { nativeAvailable() || runCatching { SystemTray.isSupported() }.getOrDefault(false) }

    /** Network-order ARGB32, the bytes a StatusNotifierItem pixmap is made of. */
    internal fun argbBytes(image: BufferedImage): ByteArray {
        val out = ByteArrayOutputStream(image.width * image.height * 4)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = image.getRGB(x, y)
                out.write(pixel ushr 24)
                out.write(pixel ushr 16)
                out.write(pixel ushr 8)
                out.write(pixel)
            }
        }
        return out.toByteArray()
    }

    /** Runs one event from the native item; false when it was not one of ours. */
    internal fun dispatch(event: Int, onOpen: () -> Unit, onExit: () -> Unit, onHostChanged: (Boolean) -> Unit): Boolean {
        when (event) {
            EventOpen -> onOpen()
            EventExit -> onExit()
            EventHostLost -> onHostChanged(false)
            EventHostBack -> onHostChanged(true)
            else -> return false
        }
        return true
    }

    /**
     * Shows the icon, or returns null when this desktop has nothing to show it, so the XEmbed path
     * can try. When the host goes away (a shell restart, the extension switched off) the window comes
     * back rather than staying hidden behind an icon nobody draws.
     */
    fun install(
        window: Window,
        iconUrl: URL,
        title: String,
        openLabel: String,
        info: String,
        exitLabel: String,
        onExit: () -> Unit,
        onAvailableChanged: (Boolean) -> Unit,
    ): (() -> Unit)? {
        val icons = IconSizes.toList().mapNotNull { size -> trayIconVariant(iconUrl, size)?.let { size to argbBytes(it) } }
        if (icons.isEmpty()) return null
        val handle = runCatching {
            NativePlayerBridge.ensureNativeLibraryLoaded()
            LinuxTrayNative.open(title, openLabel, info, exitLabel, icons.map { it.first }.toIntArray(),
                icons.flatMap { it.second.asIterable() }.toByteArray())
        }.onFailure { log.w(it) { "status notifier unavailable" } }.getOrDefault(0L)
        if (handle == 0L) return null
        val restoreWindow: () -> Unit = {
            window.isVisible = true
            if (window is Frame && window.extendedState and Frame.ICONIFIED != 0) window.extendedState = Frame.NORMAL
            window.toFront()
            window.requestFocus()
            DesktopIdleHeapTrim.onWindowVisibilityChanged(visible = true)
        }
        val running = AtomicBoolean(true)
        val worker = kotlin.concurrent.thread(isDaemon = true, name = "Nuvio-StatusNotifier") {
            while (running.get()) {
                val event = LinuxTrayNative.next(handle, PollMs)
                if (event != 0) EventQueue.invokeLater {
                    if (running.get()) dispatch(event, onOpen = restoreWindow, onExit = onExit) { hosted ->
                        onAvailableChanged(hosted)
                        if (!hosted) restoreWindow()
                    }
                }
            }
            LinuxTrayNative.close(handle)
        }
        log.i { "status notifier icon installed" }
        return {
            running.set(false)
            // The item closes on its own thread after the next poll; waiting keeps a toggle in the
            // settings from showing two icons for a moment. Bounded: the poll is short.
            runCatching { worker.join(1_000) }
            Unit
        }
    }
}
