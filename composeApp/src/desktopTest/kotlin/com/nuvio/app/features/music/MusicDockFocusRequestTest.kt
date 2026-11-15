package com.nuvio.app.features.music

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.RootNavFocus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The default mini player is the dock notch. The nav bar's album row must open it and put the keys
 * on play/pause, so the control is usable without the pointer or multimedia keys.
 */
@OptIn(ExperimentalTestApi::class)
class MusicDockFocusRequestTest {
    @AfterTest
    fun reset() {
        MusicPlayback.barCollapsed.value = false
        MusicPlayback.nowPlayingOpen.value = false
        MusicPlayback.slimBar.value = false
        RootNavFocus.topBarAvailable = false
    }

    private fun screen(state: MusicPlayerState, onRestore: () -> Unit = {}) = @androidx.compose.runtime.Composable {
        val player = MusicPlayer(
            CoroutineScope(Dispatchers.Unconfined),
            resolver = { track, _, _ -> MusicResolution.Failed(track, "test") },
        )
        val topBarFocus = androidx.compose.ui.focus.FocusRequester()
        androidx.compose.runtime.LaunchedEffect(Unit) {
            RootNavFocus.topBarEnterRequests.collect { runCatching { topBarFocus.requestFocus() } }
        }
        Box(Modifier.size(1000.dp, 700.dp)) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .size(200.dp, 40.dp)
                    .testTag("topBar")
                    .focusRequester(topBarFocus)
                    .focusable(),
            )
            MusicDockNotch(
                state = state,
                player = player,
                onRestore = onRestore,
                modifier = Modifier.align(Alignment.BottomCenter).testTag("musicDock"),
            )
        }
    }

    @Test
    fun theAlbumRowRequestOpensTheDockOnItsPlayButton() = runComposeUiTest {
        val state = MusicPlayerState(durationMs = 200_000L, positionMs = 18_000L)
        setContent { screen(state)() }
        waitForIdle()

        MusicPlayback.requestBarFocus()
        waitForIdle()

        onNodeWithTag("musicDockPlayPause").assertIsFocused()
    }

    @Test
    fun theDockFoldsOnceFocusLeavesIt() = runComposeUiTest {
        val state = MusicPlayerState(durationMs = 200_000L, positionMs = 18_000L)
        setContent { screen(state)() }
        waitForIdle()

        MusicPlayback.requestBarFocus()
        waitForIdle()
        onNodeWithTag("musicDockPlayPause").assertIsFocused()

        // Another control takes the keys: the panel folds rather than staying expanded.
        onNodeWithTag("topBar").requestFocus()
        waitForIdle()
        // The dock waits a moment before folding so a brush past its edge does not shut it.
        mainClock.advanceTimeBy(600)
        waitForIdle()

        onNodeWithTag("musicDockPlayPause").assertDoesNotExist()
    }

    @Test
    fun theDockCoverOpensTheFullMusicPage() = runComposeUiTest {
        var opened = false
        val state = MusicPlayerState(durationMs = 200_000L, positionMs = 18_000L)
        setContent { screen(state, onRestore = { opened = true })() }
        waitForIdle()

        MusicPlayback.requestBarFocus()
        waitForIdle()
        onNodeWithTag("musicDockCover").performClick()
        waitForIdle()

        kotlin.test.assertTrue(opened, "selecting the cover opens the full Now Playing page")
    }

    @Test
    fun backFromTheDockHandsTheKeysToTheTopBar() = runComposeUiTest {
        RootNavFocus.topBarAvailable = true
        val state = MusicPlayerState(durationMs = 200_000L, positionMs = 18_000L)
        setContent { screen(state)() }
        waitForIdle()

        MusicPlayback.requestBarFocus()
        waitForIdle()
        onNodeWithTag("musicDockPlayPause").assertIsFocused()

        onNodeWithTag("musicDockPlayPause").performKeyInput { pressKey(Key.Backspace) }
        waitForIdle()

        onNodeWithTag("topBar").assertIsFocused()
    }
}
