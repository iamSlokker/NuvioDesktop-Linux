package com.nuvio.app.features.music

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * What an MPRIS method should do to the music player.
 *
 * A desktop's fast-forward/rewind buttons arrive as a large relative `Seek`; they are track
 * transport here, not a scrub. Small offsets still seek inside the song.
 */
internal sealed interface MusicMprisCommand {
    data object Play : MusicMprisCommand
    data object Pause : MusicMprisCommand
    data object PlayPause : MusicMprisCommand
    data object Next : MusicMprisCommand
    data object Previous : MusicMprisCommand
    data class SeekTo(val positionMs: Long) : MusicMprisCommand
    data class SetVolume(val percent: Int) : MusicMprisCommand
}

/** A relative seek at least this large is a transport key, not a scrub. */
internal const val MusicTrackSkipSeekUs = 5_000_000.0

/** Maps an MPRIS method and relative value to the action it asks for. */
internal fun musicMprisCommand(
    method: String,
    value: Double,
    positionMs: Long,
): MusicMprisCommand? = when (method) {
    "Play" -> MusicMprisCommand.Play
    "Pause" -> MusicMprisCommand.Pause
    "PlayPause" -> MusicMprisCommand.PlayPause
    "Next" -> MusicMprisCommand.Next
    "Previous" -> MusicMprisCommand.Previous
    "Seek" -> if (abs(value) >= MusicTrackSkipSeekUs) {
        if (value > 0) MusicMprisCommand.Next else MusicMprisCommand.Previous
    } else {
        MusicMprisCommand.SeekTo(positionMs + (value / 1000.0).toLong())
    }
    "SetPosition" -> MusicMprisCommand.SeekTo((value / 1000.0).toLong().coerceAtLeast(0L))
    "Volume" -> MusicMprisCommand.SetVolume((value * 100.0).roundToInt().coerceIn(0, 100))
    else -> null
}

/** The key-dedup action for a command, or null when a desktop must not be rate-limited. */
internal fun MusicMprisCommand.gateAction(): String? = when (this) {
    // A desktop may send discrete Play/Pause over MPRIS and the toggle as an AWT key. They are
    // the same physical press, so both share one gate.
    MusicMprisCommand.Play,
    MusicMprisCommand.Pause,
    MusicMprisCommand.PlayPause,
    -> "playpause"
    MusicMprisCommand.Next -> "next"
    MusicMprisCommand.Previous -> "previous"
    else -> null
}
