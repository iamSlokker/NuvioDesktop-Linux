package com.nuvio.app.features.streams

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackUrlCredentialsTest {
    @Test
    fun detectsCommonExpiringCredentialParameters() {
        assertTrue("https://example.com/playlist/1234.m3u8?token=abc&expires=1234".hasLikelyExpiringPlaybackCredentials())
        assertTrue("https://example.com/proxy?ext=m3u8&t=abc".hasLikelyExpiringPlaybackCredentials())
        assertTrue("https://example.com/video.mp4?expiresIn=300&signature=sig".hasLikelyExpiringPlaybackCredentials())
    }

    @Test
    fun staticApiKeysDoNotExpirePlaybackButRealTokensStillDo() {
        assertFalse("https://example.test/video?api_key=public-static".hasLikelyExpiringPlaybackCredentials())
        assertFalse("https://example.test/video?apikey=public-static".hasLikelyExpiringPlaybackCredentials())
        assertTrue("https://example.test/video?api_key=public-static&expires=1234".hasLikelyExpiringPlaybackCredentials())
    }

    @Test
    fun ignoresStableFormatHints() {
        assertFalse("https://example.com/proxy?ext=m3u8".hasLikelyExpiringPlaybackCredentials())
        assertFalse("https://example.com/video.mp4?quality=1080p&format=mp4".hasLikelyExpiringPlaybackCredentials())
    }
}
