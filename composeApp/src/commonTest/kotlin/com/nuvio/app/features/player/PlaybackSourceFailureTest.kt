package com.nuvio.app.features.player

import com.nuvio.app.features.p2p.P2pStreamingState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlaybackSourceFailureTest {

    private fun streaming(peers: Int = 1, seeds: Int = 43, downloadedBytes: Long = 0L, deliveredBytes: Long = 0L) =
        P2pStreamingState.Streaming(
            localUrl = "http://127.0.0.1/stream",
            downloadSpeed = 0L,
            uploadSpeed = 0L,
            peers = peers,
            seeds = seeds,
            bufferProgress = 0f,
            totalProgress = 0f,
            downloadedBytes = downloadedBytes,
            deliveredBytes = deliveredBytes,
        )

    @Test
    fun `a torrent that delivered nothing is named as the cause`() {
        assertEquals(43, torrentStallPeerCount("Playback loading failed: loading failed", streaming()))
    }

    @Test
    fun `a torrent that delivered data or a different failure keeps the original message`() {
        assertNull(torrentStallPeerCount("Playback loading failed: loading failed", streaming(deliveredBytes = 1L)))
        assertNull(torrentStallPeerCount("Playback loading failed: loading failed", streaming(downloadedBytes = 1L)))
        assertNull(torrentStallPeerCount("unrecognized file format", streaming()))
        assertNull(torrentStallPeerCount("Playback loading failed: loading failed", P2pStreamingState.Idle))
    }

    @Test
    fun `only the bridge's video output report skips failover`() {
        assertEquals(true, playbackErrorIsVideoOutput("$PLAYBACK_VIDEO_OUTPUT_FAILURE: Nuvio needs a working Vulkan driver"))
        assertEquals(false, playbackErrorIsVideoOutput("Playback loading failed: no audio or video data played"))
        assertEquals(false, playbackErrorIsVideoOutput("https: HTTP error 429 Too Many Requests"))
    }

    @Test
    fun `recognizes AIOStreams debrid rate limit placeholder`() {
        val url = "https://aio.myaio.xyz/cache/hash/seg.ts?loop=1&title=Too+many+requests&" +
            "body=Your+debrid+provider+is+rate-limiting+requests."

        assertEquals(PlaybackSourceFailure.DebridRateLimited, playbackSourceFailure(url))
    }

    @Test
    fun `does not classify normal AIOStreams playback URL as an error`() {
        assertNull(playbackSourceFailure("https://aio.myaio.xyz/cache/hash/video.mkv"))
    }

    @Test
    fun `recognizes FFmpeg HTTP 429 playback failure`() {
        assertEquals(
            PlaybackSourceFailure.DebridRateLimited,
            playbackErrorFailure("https: HTTP error 429 Too Many Requests"),
        )
    }

    @Test
    fun `recognizes HTTP 429 attached to a post-load seek failure`() {
        assertEquals(
            PlaybackSourceFailure.DebridRateLimited,
            playbackErrorFailure(
                "https: HTTP error 429 Too Many Requests; Seek failed (to 738209342, size 162)",
            ),
        )
    }

    @Test
    fun `does not classify an unrelated playback error as rate limited`() {
        assertNull(playbackErrorFailure("Playback loading failed: loading failed"))
    }
}
