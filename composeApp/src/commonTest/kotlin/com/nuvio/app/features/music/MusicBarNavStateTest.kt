package com.nuvio.app.features.music

import kotlin.test.Test
import kotlin.test.assertEquals

class MusicBarNavStateTest {
    private val collapsedWithNav = MusicBarNavState(collapsed = true, keysOwner = MusicKeysOwner.NavBar)
    private val expandedWithBar = MusicBarNavState(collapsed = false, keysOwner = MusicKeysOwner.MiniPlayer)

    @Test
    fun activatingTheAlbumRowExpandsTheBarAndHandsItTheKeys() {
        assertEquals(expandedWithBar, activateAlbumRowFromNav(collapsedWithNav))
    }

    @Test
    fun backFromTheMiniPlayerReturnsTheKeysToTheNavBarAndKeepsItExpanded() {
        assertEquals(
            MusicBarNavState(collapsed = false, keysOwner = MusicKeysOwner.NavBar),
            backFromMiniPlayer(expandedWithBar),
        )
    }
}
