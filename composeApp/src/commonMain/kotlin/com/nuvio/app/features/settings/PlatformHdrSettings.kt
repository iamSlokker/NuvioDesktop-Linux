package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

/**
 * Platform-only rows shown directly below the desktop HDR mode choice. Linux adds its
 * default-off "Experimental HDR (Wayland)" switch; other platforms render nothing.
 */
@Composable
internal expect fun PlatformHdrSettingsRows(isTablet: Boolean)

/**
 * Whether the SDR-to-HDR (inverse tone mapping) switch is offered. It only takes effect once the
 * player reports an HDR display, which the Linux embedded player never does, so Linux hides it.
 */
internal expect val platformOffersInverseToneMapping: Boolean
