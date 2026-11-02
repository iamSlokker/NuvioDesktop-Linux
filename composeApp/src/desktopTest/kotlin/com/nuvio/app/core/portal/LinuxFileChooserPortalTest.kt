package com.nuvio.app.core.portal

import com.nuvio.app.core.build.LinuxFlatpakSandbox
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxFileChooserPortalTest {
    @Test
    fun `a pick maps to the plain document portal path`() {
        val choice = portalChoice(arrayOf("0", "file:///run/user/1000/doc/ab12cd34/My%20Filmes%20%C3%A9", ""))
        assertEquals(PortalChoice.Picked("/run/user/1000/doc/ab12cd34/My Filmes é"), choice)
    }

    @Test
    fun `cancelling is not a failure`() {
        assertEquals(PortalChoice.Cancelled, portalChoice(arrayOf("1", "", "")))
    }

    @Test
    fun `every other status is a failure carrying the portal's message`() {
        for (status in listOf("2", "-1", "-2", "-3", "-4")) {
            assertEquals(PortalChoice.Failed("why"), portalChoice(arrayOf(status, "", "why")))
        }
        assertIs<PortalChoice.Failed>(portalChoice(arrayOf("2", "", "")))
        assertIs<PortalChoice.Failed>(portalChoice(emptyArray()))
    }

    @Test
    fun `a pick without a usable file uri is a failure, not a silent null`() {
        assertIs<PortalChoice.Failed>(portalChoice(arrayOf("0", "", "")))
        assertIs<PortalChoice.Failed>(portalChoice(arrayOf("0", "https://example.com/a.mkv", "")))
        assertIs<PortalChoice.Failed>(portalChoice(arrayOf("0", "not a uri", "")))
    }

    @OptIn(ExperimentalPathApi::class)
    @Test
    fun `only an existing folder is offered as the starting point`() {
        val folder = createTempDirectory("nuvio-portal")
        try {
            val file = Files.createFile(folder.resolve("a.zip"))
            assertEquals(folder.toString(), portalStartingFolder(folder.toString()))
            assertEquals(folder.toString(), portalStartingFolder(file.toString()))
            assertNull(portalStartingFolder(folder.resolve("gone/deeper").toString()))
            assertNull(portalStartingFolder(""))
            assertNull(portalStartingFolder(null))
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test
    fun `the sandbox is detected from the environment or its info file`() {
        val infoFile = Path.of("/.flatpak-info")
        assertTrue(LinuxFlatpakSandbox.detect(mapOf("FLATPAK_ID" to "com.nuvio.media.desktop")) { false })
        assertTrue(LinuxFlatpakSandbox.detect(emptyMap()) { it == infoFile })
        assertFalse(LinuxFlatpakSandbox.detect(mapOf("FLATPAK_ID" to " ")) { false })
        assertFalse(LinuxFlatpakSandbox.detect(emptyMap()) { false })
    }
}
