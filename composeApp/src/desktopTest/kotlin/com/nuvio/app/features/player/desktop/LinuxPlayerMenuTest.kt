package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

/** LINUX_PLAYER_MENU_SCRIPT edits upstream's controls.js menu by name; an upstream rename must fail here. */
class LinuxPlayerMenuTest {
    private val controlsJs = checkNotNull(javaClass.getResourceAsStream("/player-ui/controls.js"))
        .bufferedReader().use { it.readText() }

    @Test fun theMenuEntryTheLinuxHostRemovesStillExists() {
        assertTrue("const contextMenuItems = [" in controlsJs, "contextMenuItems is no longer a top-level array")
        val video = controlsJs.substringAfter("const contextMenuItems = [").substringAfter("label: \"Video\"")
            .substringBefore("label: \"Tools\"")
        assertTrue("action: \"send:keyboardCycleAnimeSvp\"" in video, "the Video menu has no SVP toggle to remove")
        assertTrue("send:keyboardCycleAnimeSvp" in LINUX_PLAYER_MENU_SCRIPT)
        assertTrue("item.label === 'Video'" in LINUX_PLAYER_MENU_SCRIPT)
    }
}
