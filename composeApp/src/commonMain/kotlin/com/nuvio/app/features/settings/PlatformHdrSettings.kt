package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable

/**
 * Platform-only rows shown directly below the desktop HDR mode choice. Linux adds its
 * default-off "Experimental HDR (Wayland)" switch; other platforms render nothing.
 */
@Composable
internal expect fun PlatformHdrSettingsRows(isTablet: Boolean)
