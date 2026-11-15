package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The music player's MPRIS snapshot, the same shape the film player publishes. */
class LinuxMusicMprisTest {
    private val track = MusicTrack(
        videoId = "track1",
        title = "test-track-title",
        artists = listOf(MusicArtistRef("test-artist")),
        durationSeconds = 200,
        trackNumber = 1,
        isAvailable = true,
    )
    private val album = MusicAlbum(
        browseId = "album1",
        title = "test-album-title",
        artists = listOf(MusicArtistRef("test-artist")),
        year = "2022",
        kind = "Album",
        thumbnailUrl = "https://example.test/cover.jpg",
        tracks = listOf(track),
        audioPlaylistId = null,
    )

    @Test
    fun theSnapshotCarriesTheTrackAndTheTransportState() {
        val state = MusicPlayerState(
            album = album,
            track = track,
            trackIndex = 0,
            durationMs = 200_000L,
            positionMs = 25_000L,
            volume = 60,
            playWhenReady = true,
            hasNext = true,
        )

        val snapshot = linuxMusicMprisState(state, token = 7L, status = "Playing")

        assertEquals("/org/mpris/MediaPlayer2/track/7", snapshot.trackId)
        assertEquals("test-track-title", snapshot.title)
        assertEquals("test-album-title", snapshot.album)
        assertEquals("https://example.test/cover.jpg", snapshot.artwork)
        assertEquals("Playing", snapshot.status)
        assertEquals(200_000_000L, snapshot.durationUs)
        assertEquals(25_000_000L, snapshot.positionUs)
        assertEquals(0.6, snapshot.volume)
        assertTrue(snapshot.canSeek)
        assertTrue(snapshot.canNext)
        assertTrue(snapshot.canPrevious)
        assertTrue(snapshot.active)
    }

    @Test
    fun aPausedSongIsStillPublished() {
        val state = MusicPlayerState(
            album = album,
            track = track,
            durationMs = 200_000L,
            playWhenReady = false,
        )

        val snapshot = linuxMusicMprisState(state, token = 1L, status = "Paused")

        assertEquals("Paused", snapshot.status)
        assertTrue(snapshot.active, "the desktop must see the paused player and its play command")
        assertTrue(snapshot.canPrevious)
    }

    @Test
    fun playPauseAndTransportMethodsMapToTheirActions() {
        assertEquals(MusicMprisCommand.PlayPause, musicMprisCommand("PlayPause", 0.0, 10_000L))
        assertEquals(MusicMprisCommand.Play, musicMprisCommand("Play", 0.0, 10_000L))
        assertEquals(MusicMprisCommand.Pause, musicMprisCommand("Pause", 0.0, 10_000L))
        assertEquals(MusicMprisCommand.Next, musicMprisCommand("Next", 0.0, 10_000L))
        assertEquals(MusicMprisCommand.Previous, musicMprisCommand("Previous", 0.0, 10_000L))
    }

    @Test
    fun aLargeRelativeSeekIsATrackSkipInItsDirection() {
        assertEquals(
            MusicMprisCommand.Next,
            musicMprisCommand("Seek", MusicTrackSkipSeekUs, positionMs = 10_000L),
        )
        assertEquals(
            MusicMprisCommand.Previous,
            musicMprisCommand("Seek", -MusicTrackSkipSeekUs, positionMs = 10_000L),
        )
    }

    @Test
    fun aSmallRelativeSeekStaysInsideTheSong() {
        assertEquals(
            MusicMprisCommand.SeekTo(positionMs = 12_500L),
            musicMprisCommand("Seek", 2_500_000.0, positionMs = 10_000L),
        )
    }

    @Test
    fun setPositionAndVolumeUseTheDesktopValues() {
        assertEquals(
            MusicMprisCommand.SeekTo(positionMs = 42_000L),
            musicMprisCommand("SetPosition", 42_000_000.0, positionMs = 10_000L),
        )
        assertEquals(
            MusicMprisCommand.SetVolume(percent = 65),
            musicMprisCommand("Volume", 0.65, positionMs = 10_000L),
        )
    }

    @Test
    fun playPauseVariantsShareOneGate() {
        assertEquals("playpause", MusicMprisCommand.Play.gateAction())
        assertEquals("playpause", MusicMprisCommand.Pause.gateAction())
        assertEquals("playpause", MusicMprisCommand.PlayPause.gateAction())
        assertEquals("next", MusicMprisCommand.Next.gateAction())
        assertEquals("previous", MusicMprisCommand.Previous.gateAction())
        assertEquals(null, MusicMprisCommand.SeekTo(0L).gateAction())
    }
}
