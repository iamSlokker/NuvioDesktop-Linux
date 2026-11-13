package com.nuvio.app.features.player.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.platform.LocalWindowInfo
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

/**
 * The route counts as resumed when it is, or when it is only STARTED because the window lost focus. A window that is
 * not focused holds every route at STARTED, and on some compositors (cage, wlroots) the window loses X focus each time
 * the player's controls window maps: the trailer was disposed, its controls unmapped, focus came back and the trailer
 * was created again, about 16 times a second. Upstream's trailers do not stop for focus either. A route that is
 * STARTED while the window is focused is on its way in or out (a transition), and still may not own the player.
 */
internal fun linuxHeroRouteResumed(state: Lifecycle.State, windowFocused: Boolean): Boolean =
    state == Lifecycle.State.RESUMED || (!windowFocused && state == Lifecycle.State.STARTED)

/** The shared surfaces/timers stay intact; only Linux native ownership is gated. */
@Composable
internal fun linuxHeroPlayWhenReady(playWhenReady: Boolean, home: Boolean = false): Boolean {
    if (DesktopHostOs.current != DesktopHostOs.LINUX) return playWhenReady
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val windowFocused = LocalWindowInfo.current.isWindowFocused
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
        linuxHeroRouteResumed(lifecycle, windowFocused),
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
