package com.nuvio.app.features.youtubemusic

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Whether a YouTube sign-in may open the browser window. The window is the sign-in form itself, so
 * "closing it early" would leave no way to finish: the only way to never see it is to sign in by
 * pasting cookies instead, which is what turning the window off selects.
 */
class YouTubeSignInWindowChoiceTest {
    @Test
    fun theWindowOpensOnlyWhenTheSandboxAllowsItAndThePersonWantsIt() {
        assertTrue(youtubeSignInOpensBrowserWindow(sealed = false, windowEnabled = true))
        // Turned off: the paste path is used and no window is ever started.
        assertFalse(youtubeSignInOpensBrowserWindow(sealed = false, windowEnabled = false))
        // A sealed Flatpak cannot reach a browser at all, whatever the preference says.
        assertFalse(youtubeSignInOpensBrowserWindow(sealed = true, windowEnabled = true))
        assertFalse(youtubeSignInOpensBrowserWindow(sealed = true, windowEnabled = false))
    }
}
