package com.nuvio.app.core.build

import com.nuvio.app.features.updater.LinuxInstallFormat

internal actual object NuvioLinkRegistration {
    private val links: LinuxAppImageLinks? by lazy {
        val environment = System.getenv()
        if (LinuxInstallFormat.detect(environment, System.getProperty("jpackage.app-path")) != LinuxInstallFormat.AppImage) {
            null
        } else {
            LinuxAppImageLinks.forEnvironment(environment, System.getProperty("user.home").orEmpty())
        }
    }

    actual val offered: Boolean get() = links != null

    actual fun isRegistered(): Boolean = links?.isRegistered() == true

    actual fun setRegistered(enabled: Boolean): String? = runCatching {
        val current = checkNotNull(links) { "this is not an AppImage" }
        if (enabled) current.register() else current.unregister()
    }.exceptionOrNull()?.let { it.message ?: it.toString() }

    /** Follows a moved or renamed AppImage on the next start; does nothing unless the user opted in. */
    fun refreshInBackground() {
        Thread({
            runCatching { links?.refresh() }
                .onFailure { System.err.println("Warn: (UrlScheme) couldn't refresh nuvio:// links: $it") }
        }, "nuvio-url-scheme").apply { isDaemon = true }.start()
    }
}
