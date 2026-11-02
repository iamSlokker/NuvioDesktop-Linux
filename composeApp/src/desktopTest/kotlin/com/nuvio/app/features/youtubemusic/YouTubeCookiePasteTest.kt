package com.nuvio.app.features.youtubemusic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class YouTubeCookiePasteTest {
    private val jar = "VISITOR=abc; SAPISID=secret/value; SID=xyz"

    @Test
    fun `a bare header is kept as it is`() {
        assertEquals(jar, normalizedCookieHeader(jar))
    }

    @Test
    fun `the header name and surrounding space are dropped`() {
        assertEquals(jar, normalizedCookieHeader("  cookie: $jar\n"))
        assertEquals(jar, normalizedCookieHeader("Cookie:$jar"))
    }

    @Test
    fun `a paste wrapped across lines becomes one line`() {
        assertEquals("VISITOR=abc; SAPISID=secret", normalizedCookieHeader("VISITOR=abc;\r\n  SAPISID=secret\n"))
    }

    @Test
    fun `the secure Google cookies also count as a session`() {
        assertEquals("__Secure-3PAPISID=a", normalizedCookieHeader("__Secure-3PAPISID=a"))
    }

    @Test
    fun `text without a Google session is refused`() {
        assertNull(normalizedCookieHeader(""))
        assertNull(normalizedCookieHeader("VISITOR=abc; SID=xyz"))
        assertNull(normalizedCookieHeader("SAPISID="))
        assertNull(normalizedCookieHeader("not cookies at all"))
    }

    @Test
    fun `control characters cannot reach a request header`() {
        assertNull(normalizedCookieHeader("SAPISID=a\u0000b"))
        assertNull(normalizedCookieHeader("SAPISID=a\tb"))
    }
}
