package com.nuvio.app.features.player.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nuvio.app.features.home.components.HomeHeroTrailerGate
import com.nuvio.app.features.music.MusicPlayback
import com.nuvio.app.features.player.PlayerControlsState

internal fun linuxHeroPlaybackEligible(
    playWhenReady: Boolean,
    routeResumed: Boolean,
    homeActive: Boolean = true,
    // A playing music track holds the one Linux player (LinuxPlayerOwnership.Priority.MUSIC); a hero
    // asking for it then would only be refused and leave its black host over the artwork.
    musicPlaying: Boolean = false,
): Boolean = playWhenReady && routeResumed && homeActive && !musicPlaying

/** The shared surfaces/timers stay intact; only Linux native ownership is gated. */
@Composable
internal fun linuxHeroPlayWhenReady(playWhenReady: Boolean, home: Boolean = false): Boolean {
    if (DesktopHostOs.current != DesktopHostOs.LINUX) return playWhenReady
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val homeActive = if (home) {
        val active by HomeHeroTrailerGate.homeActive.collectAsState()
        active
    } else true
    // Only the playing flag: the state also carries the position, rewritten on every music poll.
    val musicPlayingFlow = remember {
        MusicPlayback.player.state.map { it.isActive && it.playWhenReady }.distinctUntilChanged()
    }
    val musicPlaying by musicPlayingFlow.collectAsState(
        initial = MusicPlayback.player.state.value.let { it.isActive && it.playWhenReady },
    )
    return linuxHeroPlaybackEligible(
        playWhenReady,
        lifecycle == Lifecycle.State.RESUMED,
        homeActive,
        musicPlaying = musicPlaying,
    )
}

// Native create loads media before queued properties are replayed. Set the shared
// hero audio state before mpv_initialize/loadfile, including retained-surface resumes.
internal fun linuxHeroStartupAudioOptions(state: PlayerControlsState): List<String> = listOf(
    "mute=${if (state.heroTrailer.muted) "yes" else "no"}",
    "volume=${if (state.heroTrailer.muted) 0 else state.heroTrailer.volume.coerceIn(0, 100)}",
)
