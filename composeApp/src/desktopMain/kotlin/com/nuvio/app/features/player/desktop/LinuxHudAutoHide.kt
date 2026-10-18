package com.nuvio.app.features.player.desktop

/**
 * Linux HUD fade: the chrome keeps upstream's fade (5.5 s without input while playing, with the
 * cursor hidden alongside it), and mouse movement or any key brings it back. Mouse movement is
 * already handled by the page ("Show Controls on Mouse Move"); keys reach either the page, once a
 * click focused the WebView, or the AWT dispatcher, since the Linux HUD keeps AWT focus.
 */

/** Queued by the Linux overlay until `controlsReady`, so it can be sent right after creation. A null
 * delay leaves controls.js's own fade time in place. */
internal const val LINUX_HUD_AUTO_HIDE_SCRIPT =
    "window.nuvioConfigureChromeAutoHide && window.nuvioConfigureChromeAutoHide(null, true)"

internal const val LINUX_HUD_REVEAL_FOR_KEY_SCRIPT =
    "window.nuvioRevealChromeForKey && window.nuvioRevealChromeForKey()"
