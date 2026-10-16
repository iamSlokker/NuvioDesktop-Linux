package com.nuvio.app.core.storage

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * Keeps the Supabase sign-in on Linux across restarts.
 *
 * supabase-kt's default session manager stores the session in `java.util.prefs`, which on Linux is
 * `~/.java/.userPrefs`. The Flatpak has no home-directory access, so inside the sandbox that path is
 * scratch space that vanishes when the app exits, and every launch started signed out. Windows (the
 * registry) and macOS (a plist) are unaffected, so this is Linux-only and leaves upstream's shared
 * Supabase setup alone.
 *
 * The preferences root moves into the Nuvio data directory, which the Flatpak persists under
 * `~/.var/app`. A session a DEB/AppImage/source build already saved in the old location is copied
 * once, so upgrading does not sign anyone out.
 */
internal object LinuxJavaPreferences {
    const val USER_ROOT_PROPERTY = "java.util.prefs.userRoot"

    /** FileSystemPreferences appends this to the user root. */
    private const val USER_PREFS_SUBPATH = ".java/.userPrefs"

    /** Must run before anything loads `java.util.prefs`: the root is read once, at class init. */
    fun configure(dataDir: Path) {
        if (!System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) return
        // An explicit -D from a launcher or developer wins.
        if (!System.getProperty(USER_ROOT_PROPERTY).isNullOrBlank()) return
        val userHome = Paths.get(System.getProperty("user.home").orEmpty())
        val root = prepareUserRoot(dataDir, userHome)
        System.setProperty(USER_ROOT_PROPERTY, root.toString())
        // FileSystemPreferences writes to disk on a timer (30 s by default) and at a clean exit.
        // Supabase rotates the refresh token on every refresh and revokes the old one, so a crash
        // inside that window leaves a revoked token on disk and signs the user out.
        if (System.getProperty(SYNC_INTERVAL_PROPERTY).isNullOrBlank()) {
            System.setProperty(SYNC_INTERVAL_PROPERTY, SYNC_INTERVAL_SECONDS)
        }
    }

    private const val SYNC_INTERVAL_PROPERTY = "java.util.prefs.syncInterval"
    private const val SYNC_INTERVAL_SECONDS = "5"

    internal fun prepareUserRoot(dataDir: Path, userHome: Path): Path {
        val root = dataDir.resolve("java-prefs")
        runCatching {
            val target = root.resolve(USER_PREFS_SUBPATH).resolve("prefs.xml")
            val legacy = userHome.resolve(USER_PREFS_SUBPATH).resolve("prefs.xml")
            if (!Files.exists(target) && Files.isRegularFile(legacy)) {
                Files.createDirectories(target.parent)
                Files.copy(legacy, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
            // The session holds refresh tokens: keep it as private as FileSystemPreferences' own
            // 0700 directory, including when this migration created the directories.
            if (Files.exists(target)) {
                listOf(root, root.resolve(".java"), target.parent).forEach { dir ->
                    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"))
                }
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
            }
        }.onFailure { error ->
            System.err.println("Warning: (LinuxJavaPreferences) legacy session not migrated: $error")
        }
        return root
    }
}
