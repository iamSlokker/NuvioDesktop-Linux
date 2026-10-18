package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

/** The Linux HUD fade depends on hooks in the shared controls.js; an upstream sync must not drop them. */
class LinuxHudAutoHideTest {
    private val controlsJs = checkNotNull(javaClass.getResourceAsStream("/player-ui/controls.js"))
        .bufferedReader().use { it.readText() }

    @Test fun controlsPageDefinesTheHooksTheLinuxHostCalls() {
        assertTrue("window.nuvioConfigureChromeAutoHide = (delayMs, revealsOnKey) =>" in controlsJs)
        assertTrue("window.nuvioRevealChromeForKey = () =>" in controlsJs)
        assertTrue("let chromeAutoHideDelayMs = 5500;" in controlsJs, "upstream's fade, which Linux keeps")
        // null keeps the page's own fade time; Linux only opts into revealing the chrome on any key.
        assertTrue("window.nuvioConfigureChromeAutoHide(null, true)" in LINUX_HUD_AUTO_HIDE_SCRIPT)
        assertTrue("window.nuvioRevealChromeForKey()" in LINUX_HUD_REVEAL_FOR_KEY_SCRIPT)
    }

    @Test fun pageKeysRevealTheChromeBeforeShortcutsRun() {
        val keydown = controlsJs.substringAfter("document.addEventListener(\"keydown\", event => {")
        val reveal = keydown.indexOf("window.nuvioRevealChromeForKey();")
        val command = keydown.indexOf("const command = shortcutCommandForEvent(event);")
        assertTrue(reveal in 0 until command, "reveal=$reveal command=$command")
    }
}
