package com.nuvio.app.features.player.desktop

import kotlin.test.Test
import kotlin.test.assertTrue

/** LINUX_PLAYER_MENU_SCRIPT edits upstream's controls page by name; an upstream rename must fail here. */
class LinuxPlayerMenuTest {
    private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream(path))
        .bufferedReader().use { it.readText() }

    private val controlsJs = resource("/player-ui/controls.js")
    private val controlsHtml = resource("/player-ui/controls.html")

    @Test fun theQuickTilesTheLinuxHostRemovesStillExist() {
        assertTrue("const QUICK_TILES = [" in controlsJs, "QUICK_TILES is no longer a top-level array")
        val tiles = controlsJs.substringAfter("const QUICK_TILES = [").substringBefore("\n];")
        for ((action, toggle) in listOf(
            "keyboardCycleAnimeSvp" to "svpInChain",
            "toggleRtxTrueHdr" to "rtxTrueHdrEnabled",
            "toggleRtxVsr" to "rtxVsrEnabled",
        )) {
            assertTrue("send(\"$action\", 0), null, \"$toggle\"]" in tiles, "the Quick actions grid has no $action tile to remove")
            assertTrue("'$toggle'" in LINUX_PLAYER_MENU_SCRIPT)
            assertTrue("[data-quick-toggle=\"$toggle\"]" in LINUX_PLAYER_MENU_SCRIPT)
        }
        // The grid is built once at load (the toggle key in [3]); the script must remove the built tiles.
        assertTrue("buildQuickTiles(contextMenu);" in controlsJs)
        assertTrue("if (toggleKey) tile.dataset.quickToggle = toggleKey;" in controlsJs)
        assertTrue("QUICK_TILES.forEach(([label, run, valueField, toggleKey, youTubeHidden])" in controlsJs)
    }

    @Test fun theHudButtonTheLinuxHostRemovesStillExists() {
        assertTrue("data-command=\"keyboardCycleAnimeSvp\"" in controlsHtml, "the HUD has no SVP button to remove")
        // Captured at load and re-applied by the overflow layout, so the script must edit it too.
        assertTrue("const overflowCapableActions = actionRow" in controlsJs)
        assertTrue("const invalidateActionRowOverflow = () =>" in controlsJs)
        assertTrue("overflowCapableActions.splice" in LINUX_PLAYER_MENU_SCRIPT)
        assertTrue("[data-command=\"keyboardCycleAnimeSvp\"]" in LINUX_PLAYER_MENU_SCRIPT)
    }

    @Test fun theVideoRowsTheLinuxHostRemovesStillExist() {
        // Upstream moved SVP and the RTX switches into the mouse menu's Video rows (2026-11-20); the script
        // filters the Video children by action and removes the rows built from them by toggle key.
        val video = controlsJs.substringAfter("label: \"Video\", textKey: \"player_hud_video\"").substringBefore("\n  },")
        for ((action, toggle) in listOf(
            "keyboardCycleAnimeSvp" to "svpInChain",
            "toggleRtxTrueHdr" to "rtxTrueHdrEnabled",
            "toggleRtxVsr" to "rtxVsrEnabled",
        )) {
            assertTrue("action: \"send:$action:0\", toggleKey: \"$toggle\"" in video, "the Video rows have no $action row to remove")
            assertTrue("'send:$action'" in LINUX_PLAYER_MENU_SCRIPT)
            assertTrue("[data-toggle-key=\"$toggle\"]" in LINUX_PLAYER_MENU_SCRIPT)
        }
        assertTrue("contextMenuItems.find(item => item.label === 'Video')" in LINUX_PLAYER_MENU_SCRIPT)
        assertTrue("if (item.toggleKey) button.dataset.toggleKey = item.toggleKey;" in controlsJs)
    }
}
