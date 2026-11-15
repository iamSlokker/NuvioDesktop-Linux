package com.nuvio.app.features.player.desktop

/**
 * Player controls Linux cannot honour. SVP needs VapourSynth and SVP's Windows-only plugins, RTX Video
 * needs Direct3D 11's video processor, and the Linux video profile applies neither, so their toggles
 * would do nothing but say "On". controls.js stays upstream's: once the page has loaded (the overlay
 * queues the script until `controlsReady`), the Linux host removes them from where controls.js built
 * them at load:
 * - the player menu's Quick actions grid: their entries in `QUICK_TILES` and the tiles already built from them
 *   (upstream moved the switches there from the Video rows, 2026-11-15; 12 tiles become a 3 x 3 grid);
 * - the right-click menu: any entries left in `contextMenuItems` and the rows already built from them;
 * - the HUD's action row (SVP only, RTX has no button): the SVP button, also taken out of `overflowCapableActions`, which the
 *   overflow layout would otherwise put back into the row or its menu.
 */
internal const val LINUX_PLAYER_MENU_SCRIPT = """(() => {
  const video = typeof contextMenuItems === 'undefined' ? null : contextMenuItems.find(item => item.label === 'Video');
  const unusable = ['send:keyboardCycleAnimeSvp', 'send:toggleRtxVsr', 'send:toggleRtxTrueHdr'];
  if (video && video.children) video.children = video.children.filter(item => !unusable.includes(item.action));
  document.querySelectorAll('[data-toggle-key="svpInChain"], [data-toggle-key="rtxVsrEnabled"], [data-toggle-key="rtxTrueHdrEnabled"]').forEach(button => (button.closest('.context-menu-group') || button).remove());
  const unusableTiles = ['svpInChain', 'rtxTrueHdrEnabled', 'rtxVsrEnabled'];
  if (typeof QUICK_TILES !== 'undefined') {
    for (let index = QUICK_TILES.length - 1; index >= 0; index--) {
      if (unusableTiles.includes(QUICK_TILES[index][3])) QUICK_TILES.splice(index, 1);
    }
  }
  document.querySelectorAll('.context-menu-quick-tile[data-quick-toggle="svpInChain"], .context-menu-quick-tile[data-quick-toggle="rtxTrueHdrEnabled"], .context-menu-quick-tile[data-quick-toggle="rtxVsrEnabled"]').forEach(tile => tile.remove());
  document.querySelectorAll('[data-command="keyboardCycleAnimeSvp"]').forEach(button => {
    if (typeof overflowCapableActions !== 'undefined') {
      const index = overflowCapableActions.indexOf(button);
      if (index >= 0) overflowCapableActions.splice(index, 1);
    }
    button.remove();
  });
  if (typeof invalidateActionRowOverflow === 'function') invalidateActionRowOverflow();
})()"""
