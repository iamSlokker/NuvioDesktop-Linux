package com.nuvio.app.features.music.potoken

import co.touchlab.kermit.Logger
import com.nuvio.app.features.player.desktop.ensureNativePlayerBridgeLoaded
import com.nuvio.app.features.youtubemusic.linuxLoginHost
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * fork:linux
 *
 * BotGuard in the WebKitGTK the app already ships for the player's controls, for PO tokens where no
 * Chromium can be started: the Flatpak's sandbox has none, and a DEB or AppImage user may have none
 * installed. Same page and same two service calls as [BotGuardMinter]. The page is loaded from a string
 * on the YouTube origin with a policy that refuses every request, as [BotGuardBrowser] refuses them
 * through DevTools. Checked live on 2026-11-05: the service issues its 12 h integrity token to it, and
 * the page cannot reach the network.
 */
internal object LinuxBotGuardPageNative {
    /** Loads [html] at [baseUri] in an offscreen view and returns its handle once loaded. */
    external fun create(html: String, baseUri: String, timeoutMs: Int): Long

    /** Evaluates [expression], awaits a returned promise and returns its string value. */
    external fun evaluate(handle: Long, expression: String, timeoutMs: Int): String

    external fun isDead(handle: Long): Boolean

    external fun close(handle: Long)
}

internal object LinuxWebKitBotGuard {
    /** Linux only: the view lives in the player bridge library. */
    val available: Boolean get() = linuxLoginHost()

    /** No network for the page: Kotlin makes the service calls, the script only has to run. */
    private const val POLICY = "<meta http-equiv=\"Content-Security-Policy\" " +
        "content=\"default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval'\">"

    fun pageHtml(): String = BotGuardPage.HTML.replaceFirst("<head>", "<head>$POLICY")
}

/** A [PoTokenMinter] backed by [LinuxBotGuardPageNative]. */
internal class LinuxWebKitBotGuardMinter private constructor(
    private val handle: Long,
    private val expiresAtMs: Long,
    private val now: () -> Long,
) : PoTokenMinter {
    @Volatile
    private var closed = false

    override val isExpired: Boolean get() = now() >= expiresAtMs
    override val isDead: Boolean get() = closed || LinuxBotGuardPageNative.isDead(handle)

    override suspend fun mint(identifier: String): String {
        val token = evaluate(handle, BotGuardPage.mintExpression(identifier), MINT_TIMEOUT_MS)
        if (token.length < MIN_TOKEN_CHARS) throw PoTokenException("The minter returned a token that is too short")
        return token
    }

    override fun close() {
        if (closed) return
        closed = true
        LinuxBotGuardPageNative.close(handle)
    }

    companion object {
        private val log = Logger.withTag("PoToken")
        private const val LOAD_TIMEOUT_MS = 15_000
        private const val RUN_TIMEOUT_MS = 25_000
        private const val MINT_TIMEOUT_MS = 10_000
        private const val MIN_TOKEN_CHARS = 40
        private const val EXPIRY_MARGIN_MS = 10 * 60_000L

        private suspend fun evaluate(handle: Long, expression: String, timeoutMs: Int): String =
            withContext(Dispatchers.IO) {
                try {
                    LinuxBotGuardPageNative.evaluate(handle, expression, timeoutMs)
                } catch (error: IllegalStateException) {
                    throw PoTokenException(error.message ?: "The page failed", error)
                }
            }

        suspend fun create(
            http: BotGuardHttp = JdkBotGuardHttp,
            now: () -> Long = System::currentTimeMillis,
        ): LinuxWebKitBotGuardMinter {
            val started = now()
            val handle = withContext(Dispatchers.IO) {
                try {
                    ensureNativePlayerBridgeLoaded()
                    LinuxBotGuardPageNative.create(LinuxWebKitBotGuard.pageHtml(), BotGuardBrowser.PAGE_URL, LOAD_TIMEOUT_MS)
                } catch (error: IllegalStateException) {
                    throw PoTokenException("The WebKit page could not be started: ${error.message}", error)
                }
            }
            try {
                val challenge = BotGuardChallenges.next(http)
                val response = evaluate(handle, BotGuardPage.runExpression(challenge), RUN_TIMEOUT_MS)
                val integrity = PoTokenProtocol.parseIntegrityToken(
                    http.post(PoTokenProtocol.GENERATE_IT_URL, PoTokenProtocol.generateItBody(response)),
                )
                evaluate(handle, BotGuardPage.initExpression(PoTokenProtocol.encodeWebSafe(integrity.bytes)), RUN_TIMEOUT_MS)
                val expiresAt = now() + (integrity.ttlSeconds * 1000L - EXPIRY_MARGIN_MS).coerceAtLeast(60_000L)
                log.i { "BotGuard ready in WebKitGTK in ${now() - started}ms, valid for ${integrity.ttlSeconds / 60} min${if (challenge.eventId != null) ", page challenge" else ""}" }
                return LinuxWebKitBotGuardMinter(handle, expiresAt, now)
            } catch (error: Throwable) {
                LinuxBotGuardPageNative.close(handle)
                throw error
            }
        }
    }
}
