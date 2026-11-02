package com.nuvio.app.features.games

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.nuvio.app.core.build.LinuxFlatpakSandbox
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/*
 * The Flatpak stays sealed: no host filesystem and no org.freedesktop.Flatpak, so it cannot see or
 * start a program on the host. A launcher's link goes out through xdg-open and the OpenURI portal, and
 * that is the one way to start a game from here. The pickers go away rather than offer what cannot run.
 */
internal val gamesLimitedToLaunchLinks: Boolean get() = LinuxFlatpakSandbox.isActive

/** A stored program path (an imported backup, say) has nothing it could start from inside the sandbox. */
internal fun gameTargetOutOfReach(path: String, sandboxed: Boolean = gamesLimitedToLaunchLinks): Boolean =
    sandboxed && !isProtocolLaunchTarget(path)

/** Says what the Flatpak can do before anyone tries; renders nothing in the other packages. */
@Composable
internal fun LinuxFlatpakGamesNotice(modifier: Modifier = Modifier) {
    if (!gamesLimitedToLaunchLinks) return
    Text(
        text = stringResource(Res.string.linux_flatpak_games_notice),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
