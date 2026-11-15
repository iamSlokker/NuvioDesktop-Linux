package com.nuvio.app.features.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The nav bar's album row asks for the mini player's controls through [MusicPlayback.requestBarFocus].
 * The hand-off, with a focusable nav row holding the keys first and the bar collapsed: the bar comes
 * back on screen and the keys land on play/pause, the control a person reaches for first.
 */
@OptIn(ExperimentalTestApi::class)
class MusicBarFocusRequestTest {
    @AfterTest
    fun reset() {
        MusicPlayback.barPlacement.value = MusicPlayerPlacement.Default
        MusicPlayback.barCollapsed.value = false
    }

    private fun screen(state: MusicPlayerState) = @androidx.compose.runtime.Composable {
        val player = MusicPlayer(
            CoroutineScope(Dispatchers.Unconfined),
            resolver = { track, _, _ -> MusicResolution.Failed(track, "test") },
        )
        var navHasFocus by remember { mutableStateOf(false) }
        val navFocus = remember { FocusRequester() }
        androidx.compose.runtime.LaunchedEffect(Unit) { runCatching { navFocus.requestFocus() } }
        // The host's own visibility rule, as it is in the app.
        val collapsed by MusicPlayback.barCollapsed.collectAsStateWithLifecycle()
        Box(Modifier.size(1000.dp, 700.dp)) {
            Column(Modifier.size(76.dp, 700.dp).testTag("sidebar")) {
                Box(
                    Modifier
                        .size(52.dp, 52.dp)
                        .testTag("albumRow")
                        .focusRequester(navFocus)
                        .onFocusChanged { navHasFocus = it.hasFocus }
                        .focusable()
                        .clickable {
                            // What the real row does.
                            MusicPlayback.barCollapsed.value = false
                            MusicPlayback.requestBarFocus()
                        },
                )
            }
            // The bar lives inside the player host, which registers itself as the keyboard's player
            // root. Keep that wrapper in the test: it is part of the real focus tree.
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .musicKeyboardRoot(takeFocus = false, isPlayer = true),
            ) {
                MusicMiniPlayerBar(
                    state = state,
                    visible = !collapsed,
                    player = player,
                    areaHeight = 700.dp,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }

    @Test
    fun theAlbumRowClickBringsBackAndFocusesTheBarCard() = runComposeUiTest {
        val state = MusicPlayerState(durationMs = 200_000L, positionMs = 18_000L)
        MusicPlayback.barCollapsed.value = true
        setContent { screen(state)() }
        waitForIdle()
        assertEquals(1, onAllNodes(isFocused()).fetchSemanticsNodes().size, "the nav row starts with the keys")

        onNodeWithTag("albumRow").performClick()
        waitForIdle()

        val focused = onAllNodes(isFocused()).fetchSemanticsNodes()
        assertEquals(1, focused.size, "exactly one control should hold the keys after the click")
        onNodeWithTag("musicBarPlayPause").assertIsFocused()
    }

    @Test
    fun theSameRequestWorksWithTheBarAlreadyOnScreen() = runComposeUiTest {
        val state = MusicPlayerState(durationMs = 200_000L, positionMs = 18_000L)
        MusicPlayback.barCollapsed.value = false
        setContent { screen(state)() }
        waitForIdle()

        onNodeWithTag("albumRow").performClick()
        waitForIdle()

        val focused = onAllNodes(isFocused()).fetchSemanticsNodes()
        assertEquals(1, focused.size)
        onNodeWithTag("musicBarPlayPause").assertIsFocused()
    }
}
