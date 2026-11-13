package com.nuvio.app.features.youtubemusic

import com.nuvio.app.core.build.LinuxFlatpakSandbox
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale

internal actual object YouTubeMusicAuthPlatform {
    actual val browserSignInAvailable: Boolean
        get() = youtubeSignInOpensBrowserWindow(browserSignInSealed, browserWindowEnabled())

    // A sealed Flatpak cannot reach a browser at all; everywhere else the window is a choice.
    actual val browserSignInSealed: Boolean get() = LinuxFlatpakSandbox.isActive

    // A UI preference, not a credential: kept apart from the session cookie store below, which the
    // settings backup leaves out unless the user asks for credentials.
    private val preferences = DesktopStorage.store("nuvio_youtube_sign_in")
    private const val BROWSER_WINDOW_KEY = "browser_window"

    /** Default true: the browser window is how sign-in has always worked. */
    actual fun browserWindowEnabled(): Boolean = preferences.getBoolean(BROWSER_WINDOW_KEY) ?: true

    actual fun setBrowserWindowEnabled(enabled: Boolean) {
        preferences.putBoolean(BROWSER_WINDOW_KEY, enabled)
    }

    // Per-account store (not in MACHINE_STORE_NAMES), and listed in the settings backup's credential
    // stores so a backup leaves it out unless the user asks for credentials.
    private val store = DesktopStorage.store("nuvio_youtube_music_auth")
    private const val COOKIE_KEY = "cookie_header"

    actual fun loadCookie(): String? = store.getString(COOKIE_KEY)?.takeIf { it.isNotBlank() }

    actual fun saveCookie(cookieHeader: String?) = store.putString(COOKIE_KEY, cookieHeader)

    actual fun sha1Hex(input: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(Locale.ROOT, it) }

    actual fun nowEpochSeconds(): Long = System.currentTimeMillis() / 1000

    actual suspend fun interactiveLogin(): YouTubeMusicLoginOutcome =
        withContext(Dispatchers.IO) { YouTubeMusicBrowserLogin.run() }

    actual fun canRenew(): Boolean = YouTubeMusicBrowserLogin.hasKeptProfile()

    actual suspend fun renewSession(cookieHeader: String?): String? =
        withContext(Dispatchers.IO) { YouTubeMusicBrowserLogin.renew(cookieHeader) }

    actual fun forgetSession() = YouTubeMusicBrowserLogin.forgetProfile()
}
