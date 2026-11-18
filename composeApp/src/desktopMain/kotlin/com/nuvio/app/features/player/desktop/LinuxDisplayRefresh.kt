package com.nuvio.app.features.player.desktop

/**
 * Where Match display refresh rate switches the monitor. Linux's bridge implements upstream's two
 * calls (display_refresh.cpp): through Mutter on GNOME and XRandR in an X11 session; elsewhere it
 * offers no rates, so `DisplayRefreshSwitcher` leaves the display alone.
 */
internal val displayRefreshHosts = setOf(DesktopHostOs.WINDOWS, DesktopHostOs.LINUX)
