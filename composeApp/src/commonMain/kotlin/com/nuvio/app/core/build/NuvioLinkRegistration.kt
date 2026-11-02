package com.nuvio.app.core.build

/**
 * An install that nothing registers for `nuvio://` links and that can do it for its own user, as a
 * choice: the Linux AppImage, the one format without a package manager to install its desktop entry.
 */
internal expect object NuvioLinkRegistration {
    val offered: Boolean
    fun isRegistered(): Boolean

    /** Null on success, otherwise why it failed. */
    fun setRegistered(enabled: Boolean): String?
}
