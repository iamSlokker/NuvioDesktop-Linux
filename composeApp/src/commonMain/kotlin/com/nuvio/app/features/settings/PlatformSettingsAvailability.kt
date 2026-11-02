package com.nuvio.app.features.settings

/*
 * Desktop settings rows that nothing reads on Linux, so Linux hides them (and their search entries)
 * instead of showing a control that does nothing. Each flag is false only on Linux: Windows and macOS
 * keep every row they had.
 */

/** "Interface Renderer": Linux always starts the interface on OpenGL, whatever was saved. */
internal expect val platformOffersInterfaceRenderer: Boolean

/** "HDR display correction": the correction only ever runs on Windows (DWM's HDR composition). */
internal expect val platformOffersHdrUiCompensation: Boolean

/** "Low VRAM Mode": only the Windows player bridge reads it. */
internal expect val platformOffersLowVramMode: Boolean

/** The screensaver's "Shut down the PC" section: the Linux screensaver only dims. */
internal expect val platformOffersScreensaverShutdown: Boolean

/** SVP's shortcut and HUD button: Linux has no SVP (LinuxPlayerMenu.kt), so they would do nothing. */
internal expect val platformOffersSvp: Boolean

/**
 * Whether settings search lists the Windows-only Playback rows (NVIDIA RTX Video, SVP). The Playback
 * page shows those rows only on Windows while search lists them on every desktop; Linux drops the
 * entries that lead nowhere.
 */
internal expect val platformListsWindowsOnlyPlaybackRowsInSearch: Boolean
