package com.nuvio.app.features.player.desktop

import java.awt.Toolkit

/** X11 WM_CLASS of every Nuvio window; also the desktop entry's `StartupWMClass`. */
internal const val LinuxWindowClassName = "com.nuvio.media.desktop"

/**
 * Gives Nuvio's X11 windows a stable WM_CLASS. XToolkit names them after the bottom frame of
 * the thread that constructs it: `com-nuvio-app-MainKt` on the JVM, and a generated
 * `java-lang-invoke-LambdaForm$DMH/…` class in the native image, which changes between builds.
 * Docks match windows to the desktop entry by this class and compositor rules (Hyprland's
 * `noblur`, for one) are written against it. The name is read when each window peer is
 * created, so this runs before the first window. Failure keeps AWT's own name.
 */
internal fun applyLinuxWindowClass(): Boolean = runCatching {
    val toolkit = Toolkit.getDefaultToolkit()
    if (toolkit.javaClass.name != "sun.awt.X11.XToolkit") return false
    Class.forName("sun.awt.X11.XToolkit").getDeclaredField("awtAppClassName").apply { isAccessible = true }
        .set(null, LinuxWindowClassName)
    true
}.getOrDefault(false)
