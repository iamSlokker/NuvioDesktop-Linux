package com.nuvio.app.features.player.desktop

/**
 * Linux HUD fade: the chrome hides after [LINUX_HUD_AUTO_HIDE_DELAY_MS] without input while playing,
 * and mouse movement or any key brings it back for another [LINUX_HUD_AUTO_HIDE_DELAY_MS]. Mouse
 * movement is already handled by the page ("Show Controls on Mouse Move"); keys reach either the page,
 * once a click focused the WebView, or the AWT dispatcher, since the Linux HUD keeps AWT focus.
 */
internal const val LINUX_HUD_AUTO_HIDE_DELAY_MS = 3000

/** Queued by the Linux overlay until `controlsReady`, so it can be sent right after creation. */
internal const val LINUX_HUD_AUTO_HIDE_SCRIPT =
    "window.nuvioConfigureChromeAutoHide && window.nuvioConfigureChromeAutoHide($LINUX_HUD_AUTO_HIDE_DELAY_MS, true)"

internal const val LINUX_HUD_REVEAL_FOR_KEY_SCRIPT =
    "window.nuvioRevealChromeForKey && window.nuvioRevealChromeForKey()"
