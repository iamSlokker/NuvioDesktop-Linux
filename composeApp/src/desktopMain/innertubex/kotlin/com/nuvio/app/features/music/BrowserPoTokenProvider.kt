package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.nuvio.app.features.music.potoken.BotGuardChallenges
import com.nuvio.app.features.music.potoken.BotGuardMinter
import com.nuvio.app.features.music.potoken.BrowserLocator
import com.nuvio.app.features.music.potoken.LinuxWebKitBotGuard
import com.nuvio.app.features.music.potoken.LinuxWebKitBotGuardMinter
import com.nuvio.app.features.music.potoken.PoTokenGenerator

/**
 * fork:potoken — InnerTubeX's [TokenProvider], backed by a headless Edge / Chrome that runs BotGuard.
 *
 * Without PO tokens the library leaves every web client out of its catalog and falls back to clients
 * YouTube bot-checks hardest. With them WEB_REMIX and the other BotGuard clients are tried as well, and
 * the library attaches the tokens to the player request and to the stream URL.
 *
 * `-Dnuvio.potoken=off` switches it off. Nothing starts until the first lookup that needs a token; the
 * browser is closed again after ten idle minutes. When no browser is installed [capabilities] is empty,
 * exactly as before this existed.
 *
 * fork:potoken-log — the first use logs whether tokens are on and which browser was found, and every
 * lookup logs what it got, so a log shows whether the minter was reached at all (BitChord logs the same
 * for its WebView).
 */
internal object BrowserPoTokens {
    private val log = Logger.withTag("PoToken")

    private val enabled: Boolean = System.getProperty("nuvio.potoken") != "off"

    // fork:innertubex-sabr - pages take their challenge from a YouTube page, so their tokens are page-bound.
    init {
        BotGuardChallenges.fromYouTubePage = { YouTubePageBotGuardChallenge.fetch() }
    }

    private val browser: String? by lazy {
        val found = if (enabled) BrowserLocator.find() else null
        log.i {
            when {
                !enabled -> "PO tokens off (-Dnuvio.potoken=off); web clients left out"
                // fork:linux
                found == null && LinuxWebKitBotGuard.available -> "PO tokens on: no Chromium to start, BotGuard runs in WebKitGTK"
                found == null -> "PO tokens unavailable: no Edge, Chrome or Brave found; web clients left out " +
                    "(set -Dnuvio.potoken.browser=<path to the browser>)"
                else -> "PO tokens on: BotGuard runs in $found"
            }
        }
        found
    }

    // fork:linux - with no Chromium to start (the Flatpak's sandbox, or none installed), the bundled WebKitGTK.
    private val webKit: Boolean get() = enabled && browser == null && LinuxWebKitBotGuard.available
    private val canMint: Boolean get() = browser != null || webKit

    private val generator by lazy {
        PoTokenGenerator(
            createMinter = {
                browser?.let { BotGuardMinter.create(it) }
                    ?: if (webKit) LinuxWebKitBotGuardMinter.create() else error("No Edge or Chrome was found")
            },
            available = { canMint },
        )
    }

    /** True when a token can be minted now: a browser was found and tokens are not paused after failures. */
    val ready: Boolean get() = canMint && generator.ready

    val provider: TokenProvider = object : TokenProvider {
        override val capabilities: TokenProviderCapabilities
            get() = if (canMint) {
                TokenProviderCapabilities(
                    // fork:innertubex-sabr - page-bound tokens also satisfy SABR's page attestation.
                    providers = setOf(PoTokenProviderKind.WEB_BOTGUARD, PoTokenProviderKind.WEBPAGE_ATTESTATION),
                    usesWebView = true,
                )
            } else {
                TokenProviderCapabilities(providers = emptySet(), usesWebView = false)
            }

        /** The cookie is not needed: the attestation is anonymous and the tokens bind to [visitorData] and the video. */
        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?): PoTokenResult? {
            val started = System.currentTimeMillis()
            val tokens = generator.tokens(videoId, visitorData)
            val elapsed = System.currentTimeMillis() - started
            if (tokens == null) {
                log.i { "no PO token for $videoId after ${elapsed}ms${if (!canMint) " (no browser)" else ""}" }
                return null
            }
            log.i { "PO tokens for $videoId in ${elapsed}ms" }
            return PoTokenResult(
                playerRequestToken = tokens.sessionToken,
                streamingDataToken = tokens.videoToken,
                visitorData = visitorData,
            )
        }

        override suspend fun prewarm(cookie: String?) = generator.prewarm()

        override suspend fun invalidateAttestation() = generator.close()

        override suspend fun close() = generator.close()
    }

    /** Loads the BotGuard page now, so the first lookup that needs a token does not wait for the browser. */
    suspend fun prewarm() = generator.prewarm()
}
