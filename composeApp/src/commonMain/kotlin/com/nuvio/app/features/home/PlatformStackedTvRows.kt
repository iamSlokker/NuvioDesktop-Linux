package com.nuvio.app.features.home

/**
 * Whether TV Mode's Home uses the stacked-rows layout (hero on top, several catalog rows below)
 * instead of the single bottom shelf. Linux-only for now, so Windows and macOS keep the shelf.
 */
internal expect val platformOffersStackedTvRows: Boolean
