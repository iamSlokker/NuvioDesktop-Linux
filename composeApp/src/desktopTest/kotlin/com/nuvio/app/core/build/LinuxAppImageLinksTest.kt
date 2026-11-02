package com.nuvio.app.core.build

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalPathApi::class)
class LinuxAppImageLinksTest {
    private val ours = LinuxAppImageLinks.DesktopFileName

    private fun withLinks(appImage: String = "/opt/Nuvio.AppImage", block: (LinuxAppImageLinks, Path) -> Unit) {
        val root = createTempDirectory("nuvio-links")
        try {
            block(LinuxAppImageLinks(Path.of(appImage), root.resolve("data/applications"), root.resolve("config/mimeapps.list")), root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `the entry opens this AppImage with the link, hidden from menus`() {
        assertEquals(
            "[Desktop Entry]\nType=Application\nName=Nuvio Desktop\nComment=Opens nuvio:// links in this AppImage\n" +
                "Exec=\"/home/me/My Apps/Nuvio.AppImage\" %u\nTerminal=false\nNoDisplay=true\nMimeType=x-scheme-handler/nuvio;\n",
            LinuxAppImageLinks.desktopEntry("/home/me/My Apps/Nuvio.AppImage"),
        )
    }

    @Test
    fun `reserved characters in the path are quoted per the Desktop Entry spec`() {
        assertEquals("\"/a\\\\\"b\"", LinuxAppImageLinks.desktopExecArgument("/a\"b"))
        assertEquals("\"/a\\\\\$b\"", LinuxAppImageLinks.desktopExecArgument("/a\$b"))
        assertEquals("\"/a\\\\`b\"", LinuxAppImageLinks.desktopExecArgument("/a`b"))
        assertEquals("\"/a\\\\\\\\b\"", LinuxAppImageLinks.desktopExecArgument("/a\\b"))
        assertNull(LinuxAppImageLinks.desktopExecArgument("/a\nb"))
        assertNull(LinuxAppImageLinks.desktopExecArgument("/a%b"))
    }

    @Test
    fun `a missing mimeapps list is created`() {
        assertEquals("[Default Applications]\nx-scheme-handler/nuvio=$ours\n", LinuxAppImageLinks.withDefaultHandler(null))
        assertEquals("[Default Applications]\nx-scheme-handler/nuvio=$ours\n", LinuxAppImageLinks.withDefaultHandler(""))
    }

    @Test
    fun `the other handlers and sections survive`() {
        val before = "[Added Associations]\nvideo/mp4=mpv.desktop;\n\n[Default Applications]\nvideo/mp4=vlc.desktop\ntext/plain=gedit.desktop\n"
        val after = LinuxAppImageLinks.withDefaultHandler(before)
        assertEquals(
            "[Added Associations]\nvideo/mp4=mpv.desktop;\n\n[Default Applications]\nx-scheme-handler/nuvio=$ours\nvideo/mp4=vlc.desktop\ntext/plain=gedit.desktop\n",
            after,
        )
        assertEquals(before, LinuxAppImageLinks.withoutOurHandler(after))
    }

    @Test
    fun `a file without the section gets one at the end`() {
        assertEquals(
            "[Added Associations]\nvideo/mp4=mpv.desktop;\n[Default Applications]\nx-scheme-handler/nuvio=$ours\n",
            LinuxAppImageLinks.withDefaultHandler("[Added Associations]\nvideo/mp4=mpv.desktop;\n"),
        )
    }

    @Test
    fun `registering replaces a previous handler, and only our handler is ever removed`() {
        val other = "[Default Applications]\nx-scheme-handler/nuvio=com.nuvio.media.desktop.desktop\n"
        assertEquals("[Default Applications]\nx-scheme-handler/nuvio=$ours\n", LinuxAppImageLinks.withDefaultHandler(other))
        assertEquals(other, LinuxAppImageLinks.withoutOurHandler(other))
    }

    @Test
    fun `the same key in another section is not the default handler`() {
        assertNull(LinuxAppImageLinks.defaultHandler("[Added Associations]\nx-scheme-handler/nuvio=$ours\n[Default Applications]\n"))
    }

    @Test
    fun `register and unregister round trip on disk`() = withLinks { links, root ->
        assertFalse(links.isRegistered())
        links.register()
        assertTrue(links.isRegistered())
        assertTrue(Files.readString(root.resolve("data/applications/$ours")).contains("Exec=\"/opt/Nuvio.AppImage\" %u"))
        links.unregister()
        assertFalse(links.isRegistered())
        assertFalse(Files.exists(root.resolve("data/applications/$ours")))
        assertEquals("[Default Applications]\n", Files.readString(root.resolve("config/mimeapps.list")))
        assertEquals(emptyList<Path>(), Files.list(root.resolve("config")).use { s -> s.filter { it.fileName.toString().endsWith(".tmp") }.toList() })
    }

    @Test
    fun `refresh follows a moved AppImage only for someone who opted in`() {
        val root = createTempDirectory("nuvio-links")
        try {
            val data = root.resolve("data/applications")
            val config = root.resolve("config/mimeapps.list")
            LinuxAppImageLinks(Path.of("/new/Nuvio.AppImage"), data, config).refresh()
            assertFalse(Files.exists(data.resolve(ours)))
            LinuxAppImageLinks(Path.of("/old/Nuvio.AppImage"), data, config).register()
            LinuxAppImageLinks(Path.of("/new/Nuvio.AppImage"), data, config).refresh()
            assertTrue(Files.readString(data.resolve(ours)).contains("/new/Nuvio.AppImage"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a symlinked mimeapps list stays a symlink`() = withLinks { links, root ->
        val real = root.resolve("dotfiles/mimeapps.list")
        Files.createDirectories(real.parent)
        Files.writeString(real, "[Default Applications]\n")
        Files.createDirectories(root.resolve("config"))
        Files.createSymbolicLink(root.resolve("config/mimeapps.list"), real)
        links.register()
        assertTrue(Files.isSymbolicLink(root.resolve("config/mimeapps.list")))
        assertTrue(Files.readString(real).contains(ours))
    }

    @Test
    fun `an environment without APPIMAGE or a home has nothing to register`() {
        assertNull(LinuxAppImageLinks.forEnvironment(emptyMap(), "/home/me"))
        assertNull(LinuxAppImageLinks.forEnvironment(mapOf("APPIMAGE" to "/a.AppImage"), ""))
    }
}
