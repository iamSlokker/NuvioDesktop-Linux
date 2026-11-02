package com.nuvio.app.features.player.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxEmbeddingOptionsTest {
    @Test fun namesExactlyWhatTheBridgeOverwrites() {
        val header = File("src/desktopMain/native/linux/player_session_options.h").readText()
        val list = header.substringAfter("linuxX11EmbeddingOptions(long long canvasXid)").substringBefore("};")
        val names = Regex("""\{"([a-z0-9-]+)",""").findAll(list).map { it.groupValues[1] }.toSet()
        assertEquals(names, LinuxEmbeddingOptions.NAMES)
    }

    @Test fun onlyOverwrittenOptionsAreMentioned() {
        val custom = "vo=gpu\n# gpu-api=opengl\ngpu-api=opengl\nscale=spline36\nvulkan-device=Foo"
        assertEquals(listOf("gpu-api", "vo"), LinuxEmbeddingOptions.overwritten(custom))
        assertEquals(emptyList(), LinuxEmbeddingOptions.overwritten("scale=spline36\nvulkan-device=Foo"))
    }

    @Test fun theMessageNamesThemAndNothingMeansNoMessage() {
        assertNull(LinuxEmbeddingOptions.message(emptyList()))
        assertTrue(LinuxEmbeddingOptions.message(listOf("gpu-api", "vo"))!!.startsWith("gpu-api, vo: "))
    }
}
