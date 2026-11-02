package com.nuvio.app.features.player.desktop

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom mpv options the embedded Linux player cannot honour. linuxX11EmbeddingOptions in the bridge
 * applies its list after every shared and custom option, because the video has to land in the app's
 * own X11 surface. The bundled libmpv has no OpenGL at all (gpu-api=opengl and gpu-context=x11egl are
 * refused), and of its two Vulkan contexts only x11vk can draw into that surface, so there is nothing
 * to switch to. mpv.conf users get a line saying so instead of a setting that silently does nothing.
 */
internal object LinuxEmbeddingOptions {
    /** player_session_options.h: linuxX11EmbeddingOptions. */
    val NAMES = setOf(
        "config", "terminal", "osc", "input-default-bindings", "input-vo-keyboard", "input-cursor",
        "window-dragging", "stop-screensaver", "keep-open", "idle", "vo", "gpu-api", "gpu-context", "wid",
    )

    private val noticeShown = AtomicBoolean(false)

    internal fun overwritten(customOptions: String): List<String> =
        desktopCustomMpvOptionNames(customOptions).filter { it in NAMES }.sorted()

    internal fun message(names: List<String>): String? =
        names.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")}: not used, the player here needs Vulkan in its own window" }

    /** The text to show the first time this applies in a run, once a video is on screen. */
    fun noticeOnce(customOptions: String): String? {
        val text = message(overwritten(customOptions)) ?: return null
        return if (noticeShown.compareAndSet(false, true)) text else null
    }
}
