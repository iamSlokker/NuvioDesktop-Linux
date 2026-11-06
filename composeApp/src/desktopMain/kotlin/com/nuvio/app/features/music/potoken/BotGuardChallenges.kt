package com.nuvio.app.features.music.potoken

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CancellationException

/** A BotGuard challenge and the YouTube page event it was served with (null for `Create`). */
internal class BotGuardChallenge(val challenge: PoTokenProtocol.Challenge, val eventId: String?)

/**
 * fork:innertubex-sabr — where a new BotGuard page gets its challenge.
 *
 * `Create` hands out an anonymous challenge. Tokens minted from it pass the player request, but YouTube's
 * media servers serve only the first ~45 s of a web client's stream with them, and SABR wants a token
 * bound to a YouTube page. The InnerTubeX build installs [fromYouTubePage], which reads the challenge
 * a YouTube page carries, as the web player does; without it, or when the page has none, `Create`.
 */
internal object BotGuardChallenges {
    private val log = Logger.withTag("PoToken")

    @Volatile
    var fromYouTubePage: (suspend () -> BotGuardChallenge)? = null

    suspend fun next(http: BotGuardHttp): BotGuardChallenge {
        fromYouTubePage?.let { page ->
            try {
                return page()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "No BotGuard challenge from the YouTube page (${error.message}); using Create" }
            }
        }
        val challenge = PoTokenProtocol.parseChallenge(http.post(PoTokenProtocol.CREATE_URL, PoTokenProtocol.createBody()))
        return BotGuardChallenge(challenge, eventId = null)
    }
}
