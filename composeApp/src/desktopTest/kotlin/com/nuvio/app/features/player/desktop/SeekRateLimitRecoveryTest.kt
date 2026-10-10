package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The mid-playback 429 recovery must never open something that could be a provider's status
 * clip: a clip opened at the resume position ends at once and would read as the title finishing.
 */
class SeekRateLimitRecoveryTest {

    private val source = "https://aio.example/api/v1/debrid/playback/a/b/c/d/Show.S01E01.mkv"

    @Test
    fun recognisesTheBridgeSeekFailureMessage() {
        assertTrue(
            SeekRateLimitRecovery.isRateLimited(
                "https: HTTP error 429 Too Many Requests; Seek failed (to 738209342, size 162)",
            ),
        )
        assertFalse(SeekRateLimitRecovery.isRateLimited("Playback seek failed: Seek failed"))
        assertFalse(SeekRateLimitRecovery.isRateLimited(null))
    }

    @Test
    fun recognisesTheLinuxAdapterPayloadWithoutRequiringFfmpegDetails() {
        assertTrue(SeekRateLimitRecovery.isRateLimited("HTTP error 429; Seek failed"))
        assertTrue(SeekRateLimitRecovery.isRateLimited("HTTP error 429; loading failed"))
        assertFalse(SeekRateLimitRecovery.isRateLimited("HTTP error 403; Seek failed"))
        assertFalse(SeekRateLimitRecovery.isRateLimited("HTTP error 503; loading failed"))
        assertFalse(SeekRateLimitRecovery.isRateLimited("Playback seek failed: Seek failed"))
    }

    @Test
    fun anAmbiguousNativeSeekFallsBackToTheLastRealPlayhead() {
        assertEquals(450_000L, SeekRateLimitRecovery.safeResumeMs(null, 450_000L, 1_800_000L))
        assertEquals(900_000L, SeekRateLimitRecovery.safeResumeMs(900_000L, 450_000L, 1_800_000L))
    }

    @Test
    fun onlyPinnedOrDirectHostLinksReopenWithoutAskingTheResolver() {
        val pinned = PlaybackRedirectResolution(
            source,
            "https://store.tb-cdn.st/dld/1?token=x",
            1,
            PlaybackRedirectResolution.Outcome.Pinned,
        )
        assertTrue(SeekRateLimitRecovery.canReopenWithoutProbe(pinned))
        assertTrue(
            SeekRateLimitRecovery.canReopenWithoutProbe(
                PlaybackRedirectResolution.unchanged(
                    "https://store.tb-cdn.st/dld/1",
                    PlaybackRedirectResolution.Outcome.Skipped,
                ),
            ),
        )
        // A remembered/failed verdict means mpv opens the resolver itself, which may now answer
        // with its /static/429.mp4 — it has to be re-probed first.
        listOf(
            PlaybackRedirectResolution.Outcome.Remembered,
            PlaybackRedirectResolution.Outcome.Failed,
            PlaybackRedirectResolution.Outcome.NoRedirect,
            PlaybackRedirectResolution.Outcome.CacheableRedirect,
        ).forEach { outcome ->
            assertFalse(
                SeekRateLimitRecovery.canReopenWithoutProbe(PlaybackRedirectResolution.unchanged(source, outcome)),
                outcome.name,
            )
        }
    }

    @Test
    fun aFreshProbeLandingOnAStatusClipIsNotReopened() {
        assertFalse(
            SeekRateLimitRecovery.isReopenable(
                PlaybackRedirectResolution.unchanged(source, PlaybackRedirectResolution.Outcome.Placeholder, hops = 1),
            ),
        )
        assertFalse(
            SeekRateLimitRecovery.isReopenable(
                PlaybackRedirectResolution.unchanged(source, PlaybackRedirectResolution.Outcome.Failed),
            ),
        )
        assertTrue(
            SeekRateLimitRecovery.isReopenable(
                PlaybackRedirectResolution(source, "https://cdn.example/x", 1, PlaybackRedirectResolution.Outcome.Pinned),
            ),
        )
    }

    /** The 2026-09-26 log: the bridge reported the duration (1366240 of 1366240) as the target. */
    @Test
    fun aResumeTargetAtTheEndIsNeverUsed() {
        val duration = 1_366_240L
        assertEquals(
            820_000L,
            SeekRateLimitRecovery.safeResumeMs(seekTargetMs = duration, lastKnownPositionMs = 820_000L, durationMs = duration),
        )
        assertEquals(
            870_000L,
            SeekRateLimitRecovery.safeResumeMs(seekTargetMs = 870_000L, lastKnownPositionMs = 820_000L, durationMs = duration),
        )
        assertEquals(
            820_000L,
            SeekRateLimitRecovery.safeResumeMs(seekTargetMs = null, lastKnownPositionMs = 820_000L, durationMs = duration),
        )
        // Genuinely in the last seconds: nothing worth reopening.
        assertNull(
            SeekRateLimitRecovery.safeResumeMs(seekTargetMs = null, lastKnownPositionMs = duration - 5_000L, durationMs = duration),
        )
        // Unknown duration: the target is trusted.
        assertEquals(
            870_000L,
            SeekRateLimitRecovery.safeResumeMs(seekTargetMs = 870_000L, lastKnownPositionMs = 0L, durationMs = 0L),
        )
    }

    @Test
    fun thumbnailSuppressionIsTimeBounded() {
        val start = 1_000_000L
        SeekThumbnailRateLimitGate.onRateLimited(nowMs = start)
        assertTrue(SeekThumbnailRateLimitGate.isSuppressed(nowMs = start + 60_000L))
        assertFalse(SeekThumbnailRateLimitGate.isSuppressed(nowMs = start + 5L * 60L * 1000L))
    }
}
