package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The Supabase session must survive the move of the Java preferences root into the data dir. */
class LinuxJavaPreferencesTest {
    @Test
    fun `a session saved in the old location is carried over`() {
        val home = Files.createTempDirectory("nuvio-home")
        val dataDir = Files.createTempDirectory("nuvio-data")
        val legacy = home.resolve(".java/.userPrefs/prefs.xml")
        Files.createDirectories(legacy.parent)
        Files.writeString(legacy, "legacy-session")

        val root = LinuxJavaPreferences.prepareUserRoot(dataDir, home)

        assertEquals(dataDir.resolve("java-prefs"), root)
        assertEquals("legacy-session", Files.readString(root.resolve(".java/.userPrefs/prefs.xml")))
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(".java/.userPrefs"))))
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(root.resolve(".java/.userPrefs/prefs.xml"))))
    }

    @Test
    fun `an existing session in the data dir is never overwritten`() {
        val home = Files.createTempDirectory("nuvio-home")
        val dataDir = Files.createTempDirectory("nuvio-data")
        Files.createDirectories(home.resolve(".java/.userPrefs"))
        Files.writeString(home.resolve(".java/.userPrefs/prefs.xml"), "stale")
        val current = dataDir.resolve("java-prefs/.java/.userPrefs/prefs.xml")
        Files.createDirectories(current.parent)
        Files.writeString(current, "current")

        LinuxJavaPreferences.prepareUserRoot(dataDir, home)

        assertEquals("current", Files.readString(current))
    }

    @Test
    fun `nothing is created when there is no old session`() {
        val home = Files.createTempDirectory("nuvio-home")
        val dataDir = Files.createTempDirectory("nuvio-data")

        val root = LinuxJavaPreferences.prepareUserRoot(dataDir, home)

        assertFalse(Files.exists(root.resolve(".java/.userPrefs/prefs.xml")))
    }
}
