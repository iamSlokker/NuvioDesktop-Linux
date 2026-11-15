package com.nuvio.app.features.music

import com.nuvio.app.features.player.desktop.LinuxMprisSession
import com.nuvio.app.features.player.desktop.LinuxMprisState
import com.nuvio.app.features.player.desktop.nativeMprisBackend
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Publishes the music player over MPRIS, under a bus name of its own so the video player keeps its
 * own. Linux desktop media keys (and the media widget) are routed by the desktop to the active
 * MPRIS player; with this, Play/Pause, Next and Previous reach the music on any page in the app.
 * Fast Forward and Rewind arrive as a large relative Seek and are track transport here.
 *
 * The music player publishes Playing while a song plays, Paused while one is loaded but paused, and
 * goes quiet when nothing is loaded — so a film's own player owns the keys while the music is held
 * aside by [MusicAutoPause].
 */
internal object LinuxMusicMpris {
    private const val BusName = "org.mpris.MediaPlayer2.NuvioMusic"
    private const val PositionPublishIntervalMs = 1_000L

    private val session = LinuxMprisSession(nativeMprisBackend(busName = BusName))
    private var job: Job? = null

    /** Starts once. Safe to call from composition; later calls do nothing. */
    fun start() {
        if (job != null) return
        job = CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            val player = MusicPlayback.player
            var client: LinuxMprisSession.Client? = null
            var token = 0L
            var trackKey: String? = null
            var lastStatus = ""
            var lastPublish = TimeSource.Monotonic.markNow() - PositionPublishIntervalMs.milliseconds
            player.state.collect { state ->
                if (state.source == null) {
                    client?.close()
                    client = null
                    trackKey = null
                    lastStatus = ""
                    return@collect
                }
                val active = client ?: session.client { method, value -> command(player, method, value) }
                    .also { client = it }
                val key = "${state.album?.browseId}:${state.track?.videoId}"
                if (key != trackKey) {
                    trackKey = key
                    token = active.newSource()
                }
                val status = if (state.playWhenReady) "Playing" else "Paused"
                val due = lastPublish.elapsedNow().inWholeMilliseconds >= PositionPublishIntervalMs
                if (status == lastStatus && !due) return@collect
                lastStatus = status
                lastPublish = TimeSource.Monotonic.markNow()
                active.publish(
                    linuxMusicMprisState(state = state, token = token, status = status),
                )
            }
        }
    }

    private fun command(player: MusicPlayer, method: String, value: Double) {
        val action = musicMprisCommand(method, value, player.state.value.positionMs) ?: return
        // A key can arrive both here (the desktop routes it) and as an AWT event; only one acts.
        action.gateAction()?.let { gate -> if (!MusicMediaKeyGate.claim(gate)) return }
        when (action) {
            MusicMprisCommand.Play -> player.resume()
            MusicMprisCommand.Pause -> player.pause()
            MusicMprisCommand.PlayPause -> player.togglePlayback()
            MusicMprisCommand.Next -> player.next()
            MusicMprisCommand.Previous -> player.previous()
            is MusicMprisCommand.SeekTo -> player.seekTo(action.positionMs.coerceAtLeast(0L))
            is MusicMprisCommand.SetVolume -> player.setVolume(action.percent)
        }
    }
}

/** The MPRIS snapshot for [state]; pure so the mapping is testable without a bus. */
internal fun linuxMusicMprisState(
    state: MusicPlayerState,
    token: Long,
    status: String,
): LinuxMprisState = LinuxMprisState(
    token = token,
    trackId = "/org/mpris/MediaPlayer2/track/$token",
    title = state.track?.title.orEmpty(),
    album = state.album?.title.orEmpty(),
    artwork = state.coverUrl.orEmpty(),
    status = status,
    durationUs = (state.durationMs * 1000L).coerceAtLeast(0L),
    positionUs = (state.positionMs * 1000L).coerceAtLeast(0L),
    canSeek = state.durationMs > 0L,
    // Offered whenever a track is loaded; next() itself decides at the end of the queue.
    canNext = true,
    // The transport controls are always offered: the player clamps at the ends, and a desktop
    // that is told "cannot go previous" never sends the key at all.
    canPrevious = true,
    rate = 1.0,
    volume = (state.volume / 100.0).coerceIn(0.0, 1.0),
)
