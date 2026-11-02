package com.nuvio.app.core.i18n

import nuvio.composeapp.generated.resources.*
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LinuxStringsTest {
    private val resources = File("src/commonMain/composeResources")
    private val languages = listOf("cs", "de", "el", "es", "fr", "id", "in", "it", "nb", "pl", "pt", "tr")

    private fun strings(file: File): Map<String, String> {
        val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to node.textContent
        }
    }
    private fun placeholders(text: String) = Regex("%\\d+\\$[sd]").findAll(text).map { it.value }.toSet()

    @Test fun everyLanguageHasEveryLinuxString() {
        val base = strings(File(resources, "values/strings_linux.xml"))
        val linuxUpdates = strings(File(resources, "values/strings.xml")).filterKeys { it.startsWith("updates_linux_") }
        assertEquals(17, linuxUpdates.size)
        val english = base + linuxUpdates
        for (language in languages) {
            val translated = strings(File(resources, "values-$language/strings_linux.xml"))
            assertEquals(english.keys, translated.keys, language)
            for ((key, text) in translated) {
                assertTrue(text.isNotBlank(), "$language $key")
                assertEquals(placeholders(english.getValue(key)), placeholders(text), "$language $key")
            }
        }
    }

    @Test fun noLinuxStringStillTalksAboutWindows() {
        for (directory in listOf("values") + languages.map { "values-$it" }) {
            for ((key, text) in strings(File(resources, "$directory/strings_linux.xml"))) {
                assertFalse(Regex("Windows|C:\\\\|winget|\\.exe|\\.lnk").containsMatchIn(text), "$directory $key: $text")
            }
        }
    }

    @Test fun indonesianMirrorsStayIdentical() {
        assertEquals(File(resources, "values-id/strings_linux.xml").readText(), File(resources, "values-in/strings_linux.xml").readText())
    }

    @Test fun linuxWordingReplacesTheWindowsOnes() {
        assertSame(Res.string.linux_cast_no_devices, linuxString(Res.string.cast_no_devices, flatpak = false))
        assertSame(Res.string.linux_cast_dolby_vision_needs_ffmpeg, linuxString(Res.string.cast_dolby_vision_needs_ffmpeg, flatpak = false))
        assertSame(Res.string.linux_cast_dolby_vision_flatpak, linuxString(Res.string.cast_dolby_vision_needs_ffmpeg, flatpak = true))
        assertSame(Res.string.action_cancel, linuxString(Res.string.action_cancel, flatpak = false))
    }
}
