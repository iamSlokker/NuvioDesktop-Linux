package com.nuvio.app.features.home

import com.nuvio.app.features.player.desktop.DesktopHostOs

internal actual val platformOffersStackedTvRows: Boolean
    get() = DesktopHostOs.current == DesktopHostOs.LINUX
