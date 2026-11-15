package com.nuvio.app.features.music

import com.nuvio.app.features.music.nebula.NebulaMusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

/** Where a music video is drawn: the bar's thumbnail, a window over the app, or the whole screen. */
/**
 * Where a real video's picture is: [Bar] (the lyric view's frame when it is open, else the bar's thumbnail) or the
 * whole window. The separate expanded video page is gone: the lyric view holds the video above the lyrics.
 */
enum class MusicVideoView { Bar, Fullscreen }

/** fork:music - the lyric view's video frame, in root pixels, where the player draws the video while that view is open. */
data class MusicInlineVideoFrame(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * The app's one [MusicPlayer]. Screens start music through it (`MusicPlayback.player.play(album)`);
 * [MusicPlayerHost] at the app root plays it and draws the mini-player.
 */
object MusicPlayback {
    /** Bumped when the nav bar's album cover asks for the mini player's controls. */
    val barFocusRequests = androidx.compose.runtime.mutableStateOf(0)

    /** The last request a mini player answered. Only the player on screen consumes it, and only once. */
    private var barFocusConsumed = 0

    /**
     * True for the one mini player that is on screen, once per album-row request. The bar, dock and
     * corner card all observe the counter; this keeps a player that re-enters the composition from
     * stealing the keys again with an old request.
     */
    fun consumeBarFocusRequest(): Boolean {
        val current = barFocusRequests.value
        if (current == barFocusConsumed) return false
        barFocusConsumed = current
        return true
    }

    /**
     * The nav bar's album row: bring the mini player back in the person's own style, open it and
     * hand it the keys. The mini player observes [barFocusRequests].
     */
    fun requestBarFocus() {
        // Present the mini player itself, not another surface that would cover it: the full Now
        // Playing view, a ladder's slim pill and a fullscreen video all hide it.
        nowPlayingOpen.value = false
        slimBar.value = false
        setVideoView(MusicVideoView.Bar)
        barCollapsed.value = false
        barFocusRequests.value++
    }

    /** A ladder asked for from outside the Music tab (a genre chip on the Stage); the tab opens it and clears it. */
    internal val pendingLadder = MutableStateFlow<com.nuvio.app.features.music.library.LadderRequest?>(null)

    /** Whether the full-window lyric view is open. [MusicNowPlayingHost] draws it. */
    val nowPlayingOpen = MutableStateFlow(false)

    /** True while the person has tucked the bar away. The music keeps playing; the dock notch brings the bar back. */
    val barCollapsed = MutableStateFlow(false)

    /** fork:music - where the bar and the corner card were dragged and how wide they were made, for this session. */
    val barPlacement = MutableStateFlow(MusicPlayerPlacement.Default)
    val cardPlacement = MutableStateFlow(MusicPlayerPlacement.Default)
    val dockPlacement = MutableStateFlow(MusicPlayerPlacement.Default)

    /**
     * True while a screen that needs the room (the ladder) asks for the bar to shrink to a slim pill. The bar only
     * obeys while there is no real video and the lyric view is closed ([musicBarSlim]).
     */
    val slimBar = MutableStateFlow(false)

    /** How big the music video is. Only a real video leaves [MusicVideoView.Bar]; [MusicPlayerHost] resets it. */
    val videoView = MutableStateFlow(MusicVideoView.Bar)

    private var ownsAppFullscreen = false

    /**
     * Changes [videoView]. Entering [MusicVideoView.Fullscreen] also takes the window fullscreen (unless
     * it already was); leaving it gives the window back only if this call is what took it.
     */
    fun setVideoView(view: MusicVideoView) {
        val previous = videoView.value
        if (view == previous) return
        videoView.value = view
        if (view == MusicVideoView.Fullscreen) {
            if (!isAppFullscreen()) {
                setAppFullscreen(true)
                ownsAppFullscreen = true
            }
        } else if (previous == MusicVideoView.Fullscreen && ownsAppFullscreen) {
            setAppFullscreen(false)
            ownsAppFullscreen = false
        }
        // The lyric view stays open under a fullscreen video, so leaving fullscreen lands back on it.
    }

    /** Opens the lyric view, where a playing video shows above the lyrics (what "Expand video" means now). */
    fun showVideo() {
        setVideoView(MusicVideoView.Bar)
        nowPlayingOpen.value = true
    }

    /** Published by the lyric view while it shows a video frame; null when there is none on screen. */
    val inlineVideoFrame = MutableStateFlow<MusicInlineVideoFrame?>(null)

    /** True while the Stage's Options menu is open: the native video would cover it, so the player parks the picture. */
    val stageMenuOpen = MutableStateFlow(false)

    /** True while the player's glass bezel is laid over the Now Playing video, so the page leaves out the rim it draws itself. */
    val inlineBezel = MutableStateFlow(false)

    val player: MusicPlayer by lazy {
        MusicPlayer(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
            // Music addon tracks (ids like "nebula:<trackId>") get a direct link; everything else is YouTube Music.
            resolver = { track, album, mode ->
                if (NebulaMusicSource.owns(track)) NebulaMusicSource.resolve(track, album, mode)
                // fork:music-playlists - a playlist's song plays from the addon's copy of its album when the person
                // prefers the addon and it has that album; otherwise from YouTube Music as before.
                else if (album.kind == "Playlist") NebulaMusicSource.resolveFromItsAlbum(track, mode) ?: resolveMusicTrack(track, album, mode)
                else resolveMusicTrack(track, album, mode)
            },
            initialVolume = com.nuvio.app.features.music.library.MusicLibraryStorage.loadPlayerVolume() ?: DEFAULT_MUSIC_VOLUME,
            saveVolume = com.nuvio.app.features.music.library.MusicLibraryStorage::savePlayerVolume,
            // fork:nebula - fetch on Play: an album that started from YouTube Music and that the addon has is asked for quietly.
            onAlbumStarted = { album -> com.nuvio.app.features.music.nebula.NebulaFetchOnPlay.start(album, player) },
            // fork:loudness - the next track is measured while this one plays, so it starts at its level.
            prepareAhead = ::measureLoudnessAhead,
        ).also { com.nuvio.app.features.music.library.MusicPlayLog.watch(it) } // fork:shelf - the play log
    }
}

/** Where a first ever session starts: loud enough to hear, not the full-scale blast it used to be. */
private const val DEFAULT_MUSIC_VOLUME = 50

/**
 * Where the keys sit across the nav bar / mini player hand-off (see the sidebar's album row).
 * The mini player's Back hands them back to whichever bar drew the album row.
 */
internal enum class MusicKeysOwner { NavBar, MiniPlayer }

/** The mini player's expansion + key ownership as a pure value, so the transition is testable. */
internal data class MusicBarNavState(
    val collapsed: Boolean,
    val keysOwner: MusicKeysOwner,
)

/**
 * Activating the nav bar's album/now-playing row: expands the mini player at the bottom of the
 * screen and hands it the keys ([MusicPlayback.requestBarFocus]).
 */
internal fun activateAlbumRowFromNav(state: MusicBarNavState): MusicBarNavState =
    state.copy(collapsed = false, keysOwner = MusicKeysOwner.MiniPlayer)

/** Back on the mini player: the keys return to the nav bar; the bar itself stays expanded. */
internal fun backFromMiniPlayer(state: MusicBarNavState): MusicBarNavState =
    state.copy(keysOwner = MusicKeysOwner.NavBar)

/** 3:07 or 1:02:09. Negative and unknown (0) lengths show as 0:00. */
internal fun formatMusicTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$ss" else "$minutes:$ss"
}
