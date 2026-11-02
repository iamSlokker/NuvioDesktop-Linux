package com.nuvio.app.features.player.desktop

import java.awt.Rectangle
import java.awt.Window
import javax.swing.Timer

/**
 * AWT leaves fullscreen at the rect the window had when it entered. Going from PiP straight into
 * fullscreen makes that the PiP rect, so the next fullscreen toggle would land on a window too small
 * to use. [current] below the minimum window size is that case; any other size is the user's.
 */
internal fun linuxWindowedBoundsAfterFullscreen(
    current: Rectangle,
    remembered: DesktopWindowGeometry?,
    screen: Rectangle,
): Rectangle? {
    if (current.width >= DesktopWindowMinWidth && current.height >= DesktopWindowMinHeight) return null
    remembered?.let { return Rectangle(it.x.toInt(), it.y.toInt(), it.width.toInt(), it.height.toInt()) }
    val width = minOf(DefaultWidth, screen.width)
    val height = minOf(DefaultHeight, screen.height)
    return Rectangle(screen.x + (screen.width - width) / 2, screen.y + (screen.height - height) / 2, width, height)
}

/** The restore is asynchronous, so watch for it to land, within a bound. */
internal fun repairLinuxWindowedSizeAfterFullscreen(window: Window, remembered: () -> DesktopWindowGeometry?) {
    if (DesktopHostOs.current != DesktopHostOs.LINUX) return
    var checks = 0
    val timer = Timer(CheckIntervalMs, null)
    timer.addActionListener {
        val fullscreen = window.graphicsConfiguration.device.fullScreenWindow === window
        if (!window.isDisplayable || ++checks > MaxChecks) {
            timer.stop()
        } else if (!fullscreen) {
            timer.stop()
            linuxWindowedBoundsAfterFullscreen(window.bounds, remembered(), window.graphicsConfiguration.bounds)
                ?.let { window.bounds = it }
        }
    }
    timer.start()
}

private const val DefaultWidth = 1280
private const val DefaultHeight = 820
private const val CheckIntervalMs = 50
private const val MaxChecks = 40
