package com.nuvio.app.features.music

import com.metrolist.innertubex.extraction.potoken.parseAttestationInterpreterUrl
import com.metrolist.innertubex.extraction.potoken.parseWebPageAttestationContext
import com.metrolist.innertubex.extraction.potoken.requireTrustedAttestationInterpreterUrl
import com.nuvio.app.features.music.potoken.BotGuardChallenge
import com.nuvio.app.features.music.potoken.PoTokenException
import com.nuvio.app.features.music.potoken.PoTokenProtocol
import com.nuvio.app.features.youtube.YouTubeWebClient
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * fork:innertubex-sabr — the BotGuard challenge a YouTube page carries, read as the web player reads it,
 * with the account's cookies when one is signed in (the InnerTubeX harness does the same). Tokens minted
 * from it are bound to that page: YouTube's media servers accept them past the first ~45 s, and SABR
 * requires them.
 */
internal object YouTubePageBotGuardChallenge {
    private const val PAGE_URL = "https://www.youtube.com/"
    private const val PAGE_LIMIT = 4 * 1024 * 1024
    private const val INTERPRETER_LIMIT = 8 * 1024 * 1024

    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    suspend fun fetch(): BotGuardChallenge = withContext(Dispatchers.IO) {
        val cookie = YouTubeMusicAuth.authHeaders(PAGE_URL)["cookie"]
        // SOCS: the consent page some regions show first has no challenge.
        val page = get(PAGE_URL, listOfNotNull(cookie, "SOCS=CAI").joinToString("; "), PAGE_LIMIT)
        val context = parseWebPageAttestationContext(page)
        // The page gives it scheme-relative (//www.google.com/js/th/...); this checks the host and adds https.
        val interpreterUrl = requireTrustedAttestationInterpreterUrl(parseAttestationInterpreterUrl(context.challenge))
        val interpreter = get(interpreterUrl, null, INTERPRETER_LIMIT)
        val challenge = Json.parseToJsonElement(context.challenge).jsonObject["bgChallenge"]?.jsonObject
            ?: throw PoTokenException("The page's challenge is empty")
        BotGuardChallenge(
            PoTokenProtocol.Challenge(
                messageId = "",
                script = interpreter,
                program = challenge["program"]?.jsonPrimitive?.content ?: throw PoTokenException("The page's challenge has no program"),
                globalName = challenge["globalName"]?.jsonPrimitive?.content ?: throw PoTokenException("The page's challenge has no VM name"),
            ),
            eventId = context.eventId,
        )
    }

    private fun get(url: String, cookie: String?, limit: Int): String {
        val request = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(20))
            .header("User-Agent", YouTubeWebClient.USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .apply { cookie?.let { header("Cookie", it) } }
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) throw PoTokenException("HTTP ${response.statusCode()} from ${URI(url).host}")
        val body = response.body()
        if (body.length > limit) throw PoTokenException("${URI(url).host} sent more than expected")
        return body
    }
}
