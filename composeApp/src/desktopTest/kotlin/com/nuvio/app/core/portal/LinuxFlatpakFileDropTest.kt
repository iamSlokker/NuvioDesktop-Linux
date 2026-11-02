package com.nuvio.app.core.portal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinuxFlatpakFileDropTest {
    @Test
    fun `the transfer key survives GTK's trailing NUL`() {
        assertEquals("1234567", transferKey("1234567\u0000".toByteArray()))
        assertEquals("1234567", transferKey(" 1234567\n".toByteArray()))
        assertEquals("", transferKey(ByteArray(0)))
    }

    @Test
    fun `transferred files come after the success flag and message`() {
        assertEquals(listOf("/a.mkv", "/b.mp4"), transferredFiles(arrayOf("1", "", "/a.mkv", "/b.mp4")))
        assertEquals(emptyList(), transferredFiles(arrayOf("1", "")))
        assertNull(transferredFiles(arrayOf("0", "denied")))
        assertNull(transferredFiles(emptyArray()))
    }

    @Test
    fun `only existing videos are played from a drop`() {
        val paths = listOf("/run/user/1/doc/a/film.MKV", "/run/user/1/doc/b/notes.txt", "/home/u/gone.mkv")
        assertEquals(listOf("/run/user/1/doc/a/film.MKV"), playableDropPaths(paths) { it != "/home/u/gone.mkv" })
    }
}
