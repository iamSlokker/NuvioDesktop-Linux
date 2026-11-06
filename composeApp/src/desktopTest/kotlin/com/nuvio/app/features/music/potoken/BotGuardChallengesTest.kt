package com.nuvio.app.features.music.potoken

import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotGuardChallengesTest {
    private val createAnswer =
        """[["msg-1",[null,null,"var vm={};"],[null,null,"https://x/y.js"],"hash","PROGRAM","vm",null,"blob"]]"""
    private val pageChallenge = BotGuardChallenge(
        PoTokenProtocol.Challenge(messageId = "", script = "var page={};", program = "PAGE", globalName = "page"),
        eventId = "event-1",
    )

    @AfterTest
    fun reset() {
        BotGuardChallenges.fromYouTubePage = null
    }

    @Test
    fun `without a page source the challenge comes from Create`() = runBlocking {
        val posted = mutableListOf<String>()
        val challenge = BotGuardChallenges.next { url, _ -> posted += url; createAnswer }
        assertEquals(listOf(PoTokenProtocol.CREATE_URL), posted)
        assertEquals("vm", challenge.challenge.globalName)
        assertNull(challenge.eventId)
    }

    @Test
    fun `the page's challenge is used when there is one`() = runBlocking {
        BotGuardChallenges.fromYouTubePage = { pageChallenge }
        val challenge = BotGuardChallenges.next { _, _ -> error("Create must not be called") }
        assertEquals("page", challenge.challenge.globalName)
        assertEquals("event-1", challenge.eventId)
    }

    @Test
    fun `a page without a challenge falls back to Create`() = runBlocking {
        BotGuardChallenges.fromYouTubePage = { throw PoTokenException("no challenge") }
        val challenge = BotGuardChallenges.next { _, _ -> createAnswer }
        assertEquals("vm", challenge.challenge.globalName)
        assertNull(challenge.eventId)
    }

    @Test
    fun `a page challenge runs with the page's event id in the YouTube config`() {
        val expression = BotGuardPage.runExpression(pageChallenge)
        assertTrue(expression.startsWith("(window.yt = {config_: {EVENT_ID: \"event-1\"}}, __pot.run("), expression)
        assertEquals(BotGuardPage.runExpression(pageChallenge.challenge), BotGuardPage.runExpression(BotGuardChallenge(pageChallenge.challenge, null)))
    }
}
