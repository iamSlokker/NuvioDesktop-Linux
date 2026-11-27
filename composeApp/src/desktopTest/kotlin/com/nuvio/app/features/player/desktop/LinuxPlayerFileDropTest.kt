package com.nuvio.app.features.player.desktop

import java.awt.Canvas
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxPlayerFileDropTest {
    @Test
    fun `only the Linux main player takes drops on its video`() {
        val host = Canvas()
        assertNull(LinuxPlayerFileDrop.install(DesktopHostOs.WINDOWS, mainPlayer = true, host) {})
        assertNull(LinuxPlayerFileDrop.install(DesktopHostOs.LINUX, mainPlayer = false, host) {})
        assertNull(host.dropTarget)
    }

    @Test
    fun `every dropped file that exists goes to the player, which picks the subtitles`() {
        val paths = listOf("/run/user/1/doc/a/film.srt", "/run/user/1/doc/b/film.mkv", "/home/u/gone.srt")
        assertEquals(paths.take(2), existingDropPaths(paths) { it != "/home/u/gone.srt" })
    }
}
