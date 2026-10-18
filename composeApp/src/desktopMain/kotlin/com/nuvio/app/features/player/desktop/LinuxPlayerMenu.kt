package com.nuvio.app.features.player.desktop

/**
 * Player menu entries Linux cannot honour. SVP needs VapourSynth and SVP's Windows-only plugins, and
 * the Linux video profile never applies it, so its "SVP interpolation" toggle would do nothing.
 * controls.js stays upstream's: the Linux host prunes the entry from its `contextMenuItems` once the
 * page has loaded (the overlay queues the script until `controlsReady`).
 */
internal const val LINUX_PLAYER_MENU_SCRIPT =
    "(() => { const video = typeof contextMenuItems === 'undefined' ? null : " +
        "contextMenuItems.find(item => item.label === 'Video'); " +
        "if (video && video.children) video.children = video.children.filter(" +
        "item => item.action !== 'send:keyboardCycleAnimeSvp'); })()"
