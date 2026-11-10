package com.nuvio.app.core.diagnostics

import com.nuvio.app.features.player.desktop.DesktopHostOs
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxMemoryOverlayTest {
    @Test
    fun linuxLibrariesNameTheSameEngines() {
        assertEquals(
            listOf("mpv", "Whisper", "QuickJS", "torrent engine"),
            notableEngines("libc.so;libc.so.6;libmpv.so;libmpv.so.2.5.0;libnuvio_whisper.so;libquickjs.so;libnuvio_engine.so;libgtk-3.so"),
        )
    }

    @Test
    fun anEngineLoadedUnderBothNamesIsNamedOnce() {
        assertEquals(listOf("mpv"), notableEngines("libmpv-2.dll;libmpv.so"))
    }

    @Test
    fun theTotalIsNamedAfterTheSystemTool() {
        val expected = if (DesktopHostOs.current == DesktopHostOs.LINUX) "Memory (System Monitor)" else "Memory (Task Manager)"
        assertEquals(expected, MemoryTotalLabel)
    }
}
