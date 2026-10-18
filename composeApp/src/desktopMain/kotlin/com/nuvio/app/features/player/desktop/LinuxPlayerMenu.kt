package com.nuvio.app.features.player.desktop

/**
 * Player controls Linux cannot honour. SVP needs VapourSynth and SVP's Windows-only plugins, and the
 * Linux video profile never applies it, so its toggles would do nothing. controls.js stays
 * upstream's: once the page has loaded (the overlay queues the script until `controlsReady`), the
 * Linux host removes SVP from both places controls.js built at load:
 * - the right-click menu: the entry in `contextMenuItems` and the row already built from it;
 * - the HUD's action row: the SVP button, also taken out of `overflowCapableActions`, which the
 *   overflow layout would otherwise put back into the row or its menu.
 */
internal const val LINUX_PLAYER_MENU_SCRIPT = """(() => {
  const video = typeof contextMenuItems === 'undefined' ? null : contextMenuItems.find(item => item.label === 'Video');
  if (video && video.children) video.children = video.children.filter(item => item.action !== 'send:keyboardCycleAnimeSvp');
  document.querySelectorAll('[data-toggle-key="desktopAnimeSvpEnabled"]').forEach(button => (button.closest('.context-menu-group') || button).remove());
  document.querySelectorAll('[data-command="keyboardCycleAnimeSvp"]').forEach(button => {
    if (typeof overflowCapableActions !== 'undefined') {
      const index = overflowCapableActions.indexOf(button);
      if (index >= 0) overflowCapableActions.splice(index, 1);
    }
    button.remove();
  });
  if (typeof invalidateActionRowOverflow === 'function') invalidateActionRowOverflow();
})()"""
