package com.nuvio.app.features.yamtrack

import com.nuvio.app.testing.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import kotlin.test.*

class LinuxYamtrackHttpTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxYamtrackHttpProbe::class.java, case) }
    @Test fun configuredEndpointHeadersAndStartPauseStopBodies() = probe("wire")
    @Test fun progressClampsBeforeStopCompletionDecision() = probe("clamp")
    @Test fun duplicateSuppressionUsesActionItemAndProgressWindow() = probe("duplicate")
    @Test fun unauthorizedTokenCanBeReplacedAndRetried() = probe("unauthorized")
    @Test fun stopRetriesTransientServerFailure() = probe("retry")
    @Test fun stopRetriesAreBounded() = probe("bounded")
    @Test fun nonDurableFailureDoesNotRetryOrPoisonLaterRequest() = probe("failure")
    @Test fun disabledAndUnconfiguredSettingsSendNothing() = probe("disabled")
    @Test fun settingsSurviveFreshProcessReload() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxYamtrackHttpProbe::class.java, "write")
        runHeadlessProbe(it, LinuxYamtrackHttpProbe::class.java, "read")
    }
}

internal object LinuxYamtrackHttpProbe {
    private fun movie(id: String = "tt123") = YamtrackScrobbleItem.Movie(
        YamtrackScrobbleRepository.YamtrackIds(imdb = id, tmdb = "42"), "Fixture Movie",
    )
    private suspend fun send(action: String, progress: Float = 50f, id: String = "tt123") = withTimeout(25_000) {
        YamtrackScrobbleRepository.scrobble(action, movie(id), progress, 25, 100)
    }
    private fun configure(url: String) {
        YamtrackSettingsRepository.setBaseUrl(" $url/settings/ ")
        YamtrackSettingsRepository.setApiToken(" fixture-token ")
        YamtrackSettingsRepository.setEnabled(true)
    }
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val case = args[1]
        if (case == "write" || case == "read") {
            if (case == "write") configure("http://127.0.0.1:12345")
            val settings = YamtrackSettingsRepository.snapshot()
            assertTrue(settings.isActive)
            assertEquals("http://127.0.0.1:12345", settings.baseUrl)
            assertEquals("fixture-token", settings.apiToken)
            return@runBlocking
        }
        LocalHttpFixture().use { http ->
            var requests = 0
            http.route("/api/v1/scrobble/") { exchange, _ ->
                requests++
                val status = when (case) {
                    "unauthorized" -> if (requests == 1) 401 else 200
                    "retry", "failure" -> if (requests == 1) 503 else 200
                    "bounded" -> 503
                    else -> 200
                }
                exchange.respond(status, """{"action":"scrobble"}""".toByteArray())
            }
            http.start()
            configure(http.url)
            when (case) {
                "wire" -> {
                    assertTrue(send("start").handled)
                    assertTrue(send("pause").handled)
                    val episode = YamtrackScrobbleItem.Episode(movie().ids, "Series", "Episode", 2, 3)
                    assertTrue(YamtrackScrobbleRepository.scrobble("stop", episode, 90f, 90, 100).handled)
                    val captured = http.drainRequests()
                    assertEquals(3, captured.size)
                    captured.forEach {
                        assertEquals("POST", it.method)
                        assertEquals("/api/v1/scrobble/", it.path)
                        assertEquals(listOf("Bearer fixture-token"), it.headers["authorization"])
                        assertEquals(listOf("application/json"), it.headers["accept"])
                        assertTrue(it.headers["content-type"]!!.single().startsWith("application/json"))
                    }
                    val bodies = captured.map { Json.parseToJsonElement(it.body).jsonObject }
                    assertEquals(listOf("start", "pause", "stop"), bodies.map { it["action"]!!.jsonPrimitive.content })
                    assertEquals("movie", bodies[0]["media_type"]!!.jsonPrimitive.content)
                    assertEquals("Fixture Movie", bodies[0]["title"]!!.jsonPrimitive.content)
                    assertEquals("tt123", bodies[0]["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
                    assertEquals("42", bodies[0]["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.content)
                    assertEquals(25, bodies[0]["position_seconds"]!!.jsonPrimitive.int)
                    assertEquals(100, bodies[0]["duration_seconds"]!!.jsonPrimitive.int)
                    assertNull(bodies[0]["completed"])
                    assertEquals("episode", bodies[2]["media_type"]!!.jsonPrimitive.content)
                    assertEquals(2, bodies[2]["season_number"]!!.jsonPrimitive.int)
                    assertEquals(3, bodies[2]["episode_number"]!!.jsonPrimitive.int)
                    assertEquals("Series", bodies[2]["series_title"]!!.jsonPrimitive.content)
                    assertEquals(JsonPrimitive(true), bodies[2]["completed"])
                }
                "clamp" -> {
                    listOf(-50f, 79.9f, 80f, 150f).forEachIndexed { index, progress ->
                        send("stop", progress, "tt$index")
                    }
                    assertEquals(listOf(false, false, true, true), http.drainRequests().map {
                        Json.parseToJsonElement(it.body).jsonObject["completed"]!!.jsonPrimitive.boolean
                    })
                }
                "duplicate" -> {
                    assertTrue(send("start", 150f).handled)
                    assertFalse(send("start", 100f).handled) // Clamp occurs before duplicate comparison.
                    assertFalse(send("start", 99f).handled)
                    assertTrue(send("start", 90f).handled)
                    assertTrue(send("pause", 90f).handled)
                    assertTrue(send("pause", 90f, "tt456").handled)
                    assertTrue(send("stop", -50f).handled)
                    assertFalse(send("stop", 0f).handled) // Lower clamp also precedes duplicate comparison.
                    assertEquals(5, http.drainRequests().size)
                }
                "unauthorized" -> {
                    assertTrue(send("start").handled)
                    assertEquals(YamtrackConnectionState.Unauthorized, YamtrackSettingsRepository.snapshot().connectionState)
                    YamtrackSettingsRepository.setApiToken("replacement")
                    assertEquals(YamtrackConnectionState.Unknown, YamtrackSettingsRepository.snapshot().connectionState)
                    assertTrue(send("start").handled)
                    val captured = http.drainRequests()
                    assertEquals(2, captured.size)
                    assertEquals(listOf("Bearer replacement"), captured[1].headers["authorization"])
                }
                "retry", "bounded" -> {
                    assertTrue(send("stop", 90f).handled)
                    val captured = http.drainRequests()
                    assertEquals(if (case == "retry") 2 else 3, captured.size)
                    assertEquals(1, captured.map { it.body }.distinct().size)
                    if (case == "retry") {
                        assertFalse(send("stop", 90f).handled)
                        assertTrue(http.drainRequests().isEmpty())
                    }
                }
                "failure" -> {
                    assertTrue(send("pause").handled)
                    assertEquals(1, http.drainRequests().size)
                    assertTrue(send("pause").handled)
                    assertEquals(1, http.drainRequests().size)
                }
                "disabled" -> {
                    YamtrackSettingsRepository.setEnabled(false)
                    assertFalse(send("start").handled)
                    YamtrackSettingsRepository.setApiToken("")
                    YamtrackSettingsRepository.setEnabled(true)
                    assertFalse(send("start").handled)
                    YamtrackSettingsRepository.clearLocalState()
                    assertFalse(send("stop").handled)
                    assertTrue(http.drainRequests().isEmpty())
                }
            }
        }
    }
}
