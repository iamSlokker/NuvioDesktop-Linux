package com.nuvio.app.core.build

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/*
 * A DEB and a Flatpak install com.nuvio.media.desktop.desktop, whose MimeType makes nuvio:// links
 * open them. An AppImage installs nothing, so its Watch Party invites open nothing, until the user
 * asks for this: a hidden desktop entry pointing at this AppImage, set as the handler in their own
 * mimeapps.list. No xdg-mime: the launcher's environment is the image's, and both files are plain.
 * Registering twice, or over another handler, simply makes this AppImage the handler; unregistering
 * leaves a handler that is not ours alone.
 */
internal class LinuxAppImageLinks(
    private val appImage: Path,
    private val applicationsDir: Path,
    private val mimeAppsFile: Path,
) {
    private val desktopFile: Path get() = applicationsDir.resolve(DesktopFileName)

    fun isRegistered(): Boolean =
        Files.isRegularFile(desktopFile) && defaultHandler(readOrNull(mimeAppsFile)) == DesktopFileName

    fun register() {
        val entry = checkNotNull(desktopEntry(appImage.toString())) { "the AppImage's path cannot go in a desktop entry" }
        writeAtomically(desktopFile, entry)
        writeAtomically(mimeAppsFile, withDefaultHandler(readOrNull(mimeAppsFile)))
    }

    fun unregister() {
        readOrNull(mimeAppsFile)?.let { text ->
            val updated = withoutOurHandler(text)
            if (updated != text) writeAtomically(mimeAppsFile, updated)
        }
        Files.deleteIfExists(desktopFile)
    }

    /** Rewrites the entry only for someone who opted in, so a moved AppImage keeps working. */
    fun refresh() {
        if (!isRegistered()) return
        val entry = desktopEntry(appImage.toString()) ?: return
        if (readOrNull(desktopFile) != entry) writeAtomically(desktopFile, entry)
    }

    private fun readOrNull(path: Path): String? = runCatching { Files.readString(path) }.getOrNull()

    private fun writeAtomically(path: Path, text: String) {
        // Follow a symlinked mimeapps.list (dotfile managers) instead of replacing the link.
        val target = if (Files.exists(path)) path.toRealPath() else path
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".${target.fileName}-", ".tmp")
        try {
            Files.writeString(temporary, text)
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    companion object {
        const val DesktopFileName = "nuvio-appimage-links.desktop"
        private const val Scheme = "x-scheme-handler/nuvio"
        private const val DefaultsHeader = "[Default Applications]"

        fun forEnvironment(environment: Map<String, String>, home: String): LinuxAppImageLinks? {
            val appImage = environment["APPIMAGE"]?.takeIf(String::isNotBlank)?.let { Path.of(it) } ?: return null
            fun base(variable: String, fallback: String): Path? =
                environment[variable]?.takeIf { it.startsWith("/") }?.let { Path.of(it) }
                    ?: home.takeIf(String::isNotBlank)?.let { Path.of(it).resolve(fallback) }
            val data = base("XDG_DATA_HOME", ".local/share") ?: return null
            val config = base("XDG_CONFIG_HOME", ".config") ?: return null
            return LinuxAppImageLinks(appImage, data.resolve("applications"), config.resolve("mimeapps.list"))
        }

        /**
         * The Desktop Entry spec's Exec quoting, then its string escaping. Null when no entry can work: a
         * line break, or a `%`, which the spec writes as `%%` but GLib then looks for literally and drops
         * the entry (tried with `gio open`).
         */
        internal fun desktopExecArgument(path: String): String? {
            if ('%' in path || path.any { it.isISOControl() }) return null
            val quoted = path.replace("\\", "\\\\").replace("\"", "\\\"").replace("`", "\\`").replace("$", "\\$")
            return "\"" + quoted.replace("\\", "\\\\").replace("%", "%%") + "\""
        }

        internal fun desktopEntry(appImage: String): String? {
            val exec = desktopExecArgument(appImage) ?: return null
            return """
                [Desktop Entry]
                Type=Application
                Name=Nuvio Desktop
                Comment=Opens nuvio:// links in this AppImage
                Exec=$exec %u
                Terminal=false
                NoDisplay=true
                MimeType=$Scheme;
            """.trimIndent() + "\n"
        }

        private fun String.isSectionHeader(): Boolean = trimStart().startsWith("[")

        private fun handlerLineIndex(lines: List<String>): Int {
            val header = lines.indexOfFirst { it.trim() == DefaultsHeader }
            if (header < 0) return -1
            for (index in header + 1 until lines.size) {
                if (lines[index].isSectionHeader()) return -1
                if (lines[index].substringBefore('=').trim() == Scheme) return index
            }
            return -1
        }

        internal fun defaultHandler(mimeApps: String?): String? {
            val lines = mimeApps?.lines() ?: return null
            val index = handlerLineIndex(lines)
            if (index < 0) return null
            return lines[index].substringAfter('=').split(';').firstOrNull()?.trim()?.takeIf(String::isNotEmpty)
        }

        internal fun withDefaultHandler(mimeApps: String?): String {
            val line = "$Scheme=$DesktopFileName"
            if (mimeApps.isNullOrBlank()) return "$DefaultsHeader\n$line\n"
            val lines = mimeApps.lines().toMutableList()
            val existing = handlerLineIndex(lines)
            val header = lines.indexOfFirst { it.trim() == DefaultsHeader }
            when {
                existing >= 0 -> lines[existing] = line
                header >= 0 -> lines.add(header + 1, line)
                else -> {
                    if (lines.last().isNotEmpty()) lines.add("")
                    lines.add(lines.size - 1, DefaultsHeader)
                    lines.add(lines.size - 1, line)
                }
            }
            return lines.joinToString("\n")
        }

        internal fun withoutOurHandler(mimeApps: String): String {
            if (defaultHandler(mimeApps) != DesktopFileName) return mimeApps
            val lines = mimeApps.lines().toMutableList()
            lines.removeAt(handlerLineIndex(lines))
            return lines.joinToString("\n")
        }
    }
}
