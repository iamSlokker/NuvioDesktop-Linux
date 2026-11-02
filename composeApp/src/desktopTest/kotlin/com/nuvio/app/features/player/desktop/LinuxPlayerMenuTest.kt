package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

/** LINUX_PLAYER_MENU_SCRIPT edits upstream's controls page by name; an upstream rename must fail here. */
class LinuxPlayerMenuTest {
    private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream(path))
        .bufferedReader().use { it.readText() }

    private val controlsJs = resource("/player-ui/controls.js")
    private val controlsHtml = resource("/player-ui/controls.html")

    @Test fun theMenuEntryTheLinuxHostRemovesStillExists() {
        assertTrue("const contextMenuItems = [" in controlsJs, "contextMenuItems is no longer a top-level array")
        val video = controlsJs.substringAfter("const contextMenuItems = [").substringAfter("label: \"Video\"")
            .substringBefore("label: \"Tools\"")
        assertTrue(
            "action: \"send:keyboardCycleAnimeSvp\", toggleKey: \"desktopAnimeSvpEnabled\"" in video,
            "the Video menu has no SVP toggle to remove",
        )
        // The menu is built once at load; the script must remove the built row, found by this attribute.
        assertTrue("button.dataset.toggleKey = item.toggleKey" in controlsJs)
        assertTrue("[data-toggle-key=\"desktopAnimeSvpEnabled\"]" in LINUX_PLAYER_MENU_SCRIPT)
    }

    @Test fun theRtxRowsTheLinuxHostRemovesStillExist() {
        val video = controlsJs.substringAfter("const contextMenuItems = [").substringAfter("label: \"Video\"")
            .substringBefore("label: \"Tools\"")
        for ((action, toggle) in listOf("toggleRtxVsr" to "rtxVsrEnabled", "toggleRtxTrueHdr" to "rtxTrueHdrEnabled")) {
            assertTrue("action: \"send:$action\", toggleKey: \"$toggle\"" in video, "the Video menu has no $action row to remove")
            assertTrue("'send:$action'" in LINUX_PLAYER_MENU_SCRIPT)
            assertTrue("[data-toggle-key=\"$toggle\"]" in LINUX_PLAYER_MENU_SCRIPT)
        }
    }

    @Test fun theHudButtonTheLinuxHostRemovesStillExists() {
        assertTrue("data-command=\"keyboardCycleAnimeSvp\"" in controlsHtml, "the HUD has no SVP button to remove")
        // Captured at load and re-applied by the overflow layout, so the script must edit it too.
        assertTrue("const overflowCapableActions = actionRow" in controlsJs)
        assertTrue("const invalidateActionRowOverflow = () =>" in controlsJs)
        assertTrue("overflowCapableActions.splice" in LINUX_PLAYER_MENU_SCRIPT)
        assertTrue("[data-command=\"keyboardCycleAnimeSvp\"]" in LINUX_PLAYER_MENU_SCRIPT)
    }
}
