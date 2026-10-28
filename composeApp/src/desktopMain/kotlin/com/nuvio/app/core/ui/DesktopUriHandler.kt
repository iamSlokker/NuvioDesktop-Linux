package com.nuvio.app.core.ui

import androidx.compose.ui.platform.UriHandler
import co.touchlab.kermit.Logger
import java.awt.Desktop
import java.net.URI
import java.util.Locale

private val uriHandlerLog = Logger.withTag("UriHandler")

/**
 * Opens links in the default browser the way Windows itself does.
 *
 * Compose's own handler calls `Desktop.browse`, which on Windows does not ask the shell: it reads the
 * default browser's command line from the registry and starts that exe itself. A browser installed
 * as a packaged app (Arc, which ships as MSIX) cannot be started that way, so links (TorBox's device
 * sign-in page, addon configuration pages) did not reach it. `url.dll,FileProtocolHandler` hands the
 * link to ShellExecute, which activates any registered browser, packaged or not.
 *
 * Only http(s) goes that way: ShellExecute also runs executables and hands other schemes to whatever
 * claims them. Everything else, and any failure, keeps the Desktop API.
 */
internal object DesktopUriHandler : UriHandler {
    private val isWindows = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")

    override fun openUri(uri: String) {
        val parsed = runCatching { URI(uri) }.getOrNull()
        val scheme = parsed?.scheme?.lowercase(Locale.ROOT)
        if (isWindows && (scheme == "http" || scheme == "https") && openWithShell(uri)) return
        runCatching { Desktop.getDesktop().browse(parsed ?: URI(uri)) }
            .onFailure { error -> uriHandlerLog.w(error) { "could not open link ${uri.substringBefore('?')}" } }
    }

    private fun openWithShell(uri: String): Boolean =
        runCatching {
            ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", uri)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.onFailure { error ->
            uriHandlerLog.w(error) { "shell open failed for ${uri.substringBefore('?')}; trying Desktop.browse" }
        }.isSuccess
}
