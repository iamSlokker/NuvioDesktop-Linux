package com.nuvio.app.features.youtubemusic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxLoginBrowsersTest {
    private val home = Path.of("/home/user")
    private val all = LinuxLoginBrowsers.candidates(listOf("/usr/bin"), home)
    private fun at(path: String) = all.single { it.executable == path }

    @Test fun everyChromiumBrowserComesBeforeAnyFirefox() {
        val firstFirefox = all.indexOfFirst { it.firefox }
        assertTrue(firstFirefox > 0)
        assertTrue(all.drop(firstFirefox).all { it.firefox })
        assertEquals("/usr/bin/google-chrome", all.first().executable)
    }

    @Test fun moreBrowserNamesAreKnown() {
        for (name in listOf("brave", "brave-browser-stable", "microsoft-edge-stable", "vivaldi", "vivaldi-stable", "opera", "ungoogled-chromium")) {
            assertFalse(at("/usr/bin/$name").firefox, name)
        }
        assertTrue(at("/usr/bin/firefox").firefox)
        assertTrue(at("/usr/bin/firefox-esr").firefox)
    }

    @Test fun systemBrowsersUseTheSystemTemporaryDirectory() {
        assertNull(at("/usr/bin/chromium").profileParent)
    }

    @Test fun aSnapBrowsersProfileStaysInsideItsSnap() {
        assertEquals(home.resolve("snap/chromium/common"), at("/snap/bin/chromium").profileParent)
        assertEquals(home.resolve("snap/firefox/common"), at("/snap/bin/firefox").profileParent)
    }

    @Test fun aFlatpakBrowsersProfileStaysInsideItsAppFolder() {
        for (root in listOf("/var/lib/flatpak/exports/bin", "/home/user/.local/share/flatpak/exports/bin")) {
            assertEquals(home.resolve(".var/app/com.google.Chrome/cache"), at("$root/com.google.Chrome").profileParent)
            assertEquals(home.resolve(".var/app/org.mozilla.firefox/cache"), at("$root/org.mozilla.firefox").profileParent)
            assertTrue(at("$root/org.mozilla.firefox").firefox)
        }
        assertTrue(LinuxLoginBrowsers.candidates(emptyList(), home, Path.of("/data"))
            .any { it.executable == "/data/flatpak/exports/bin/com.brave.Browser" })
    }

    @Test fun aFlatpakExportsDirectoryOnPathIsNotMistakenForASystemBrowser() {
        val found = LinuxLoginBrowsers.candidates(listOf("/var/lib/flatpak/exports/bin"), home)
        assertTrue(found.none { it.executable == "/var/lib/flatpak/exports/bin/chromium" })
        assertEquals(1, found.count { it.executable == "/var/lib/flatpak/exports/bin/com.google.Chrome" })
    }

    @Test fun createProfileMakesTheFolderABrowserCanReach() {
        val root = Files.createTempDirectory("nuvio-login-test")
        try {
            val parent = root.resolve("snap/chromium/common")
            val profile = LinuxLoginBrowser("/snap/bin/chromium", profileParent = parent).createProfile()
            assertEquals(parent, profile.parent)
            assertTrue(Files.isDirectory(profile))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun theFirefoxSignInWindowCarriesNoAutomationSignals() {
        val profile = Path.of("/p")
        val command = LinuxLoginBrowsers.firefoxSignInCommand("firefox", profile, "https://accounts.google.com/x")
        assertEquals("https://accounts.google.com/x", command.last())
        assertTrue(command.none { it.contains("remote-debugging") || it.contains("headless") || it == "--marionette" }, "$command")
        assertEquals(listOf("--profile", "/p"), command.subList(3, 5))
    }

    @Test fun theFirefoxCookieReaderIsHeadlessOnTheSameProfile() {
        val command = LinuxLoginBrowsers.firefoxCookieReadCommand("firefox", Path.of("/p"))
        assertTrue("--headless" in command)
        assertEquals("0", command[command.indexOf("--remote-debugging-port") + 1])
        assertEquals("/p", command[command.indexOf("--profile") + 1])
        assertFalse(command.any { it.startsWith("https://") })
    }

    @Test fun firefoxProfileSkipsTheWelcomeTab() {
        val root = Files.createTempDirectory("nuvio-login-test")
        try {
            LinuxLoginBrowsers.prepareFirefoxProfile(root)
            val text = Files.readString(root.resolve("user.js"))
            assertTrue("user_pref(\"browser.aboutwelcome.enabled\", false);" in text)
            assertTrue("user_pref(\"browser.startup.homepage_override.mstone\", \"ignore\");" in text)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test fun theEndpointIsReadFromFirefoxsOutput() {
        assertEquals("ws://127.0.0.1:37111", LinuxLoginBrowsers.bidiEndpointIn("WebDriver BiDi listening on ws://127.0.0.1:37111"))
        assertEquals("ws://localhost:9222", LinuxLoginBrowsers.bidiEndpointIn("1700000000 RemoteAgent INFO WebDriver BiDi listening on ws://localhost:9222"))
        assertNull(LinuxLoginBrowsers.bidiEndpointIn("console.warn: nothing here"))
    }

    @Test fun bidiCookiesBecomeTheHeaderDevToolsCookiesDo() {
        val cookies = Json.parseToJsonElement("""[
            {"name":"SAPISID","value":{"type":"string","value":"abc"},"domain":".youtube.com","path":"/"},
            {"name":"binary","value":{"type":"base64","value":"AAA="},"domain":".youtube.com"},
            {"name":"other","value":{"type":"string","value":"x"},"domain":".example.com"}
        ]""").jsonArray
        assertEquals("SAPISID=abc; binary=AAA=", YouTubeMusicBrowserLogin.buildCookieHeader(LinuxLoginBrowsers.plainBidiCookies(cookies)))
    }
}
