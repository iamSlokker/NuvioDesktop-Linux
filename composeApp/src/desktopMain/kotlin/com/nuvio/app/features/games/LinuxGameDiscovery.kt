package com.nuvio.app.features.games

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** Conservative discovery in a user-selected games folder; never executes a probe. */
internal object LinuxGameDiscovery {
    private val systemRoots = listOf("/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc", "/proc", "/sys", "/dev", "/run")
    private val launchNames = setOf("start", "start.sh", "run", "run.sh", "launch", "launch.sh")
    private val ignored = setOf("tools", "sdk", "redist", "support", "engine", "__installer", ".git")
    private val wrappers = setOf("sh", "bash", "dash", "zsh", "ksh", "fish", "env", "flatpak", "snap", "xdg-open", "gtk-launch", "steam", "wine", "wine64")
    private const val MaxEntries = 4096

    fun allowedRoot(root: File): Boolean = runCatching {
        val path = root.canonicalPath
        path != "/" && systemRoots.none { path == it || path.startsWith("$it/") } && root.isDirectory && root.canRead()
    }.getOrDefault(false)

    fun children(root: File): List<File> = runCatching {
        Files.newDirectoryStream(root.toPath()).use { stream ->
            stream.asSequence().take(MaxEntries).map { it.toFile() }.sortedBy { it.name }.toList()
        }
    }.getOrDefault(emptyList())

    fun directory(file: File): Boolean = Files.isDirectory(file.toPath(), NOFOLLOW_LINKS)

    fun runnable(file: File): Boolean = runCatching {
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || !file.canRead() || !file.canExecute()) return false
        val header = file.inputStream().use { it.readNBytes(128) }
        // AppImages are executable ELF files too; do not trust the extension alone.
        header.take(4).toByteArray().contentEquals(byteArrayOf(0x7f, 0x45, 0x4c, 0x46)) ||
            (header.size > 3 && header[0] == '#'.code.toByte() && header[1] == '!'.code.toByte() &&
                header.toString(Charsets.UTF_8).substringAfter("#!").trimStart().startsWith('/'))
    }.getOrDefault(false)

    fun choose(folder: File): File? {
        var remaining = MaxEntries
        val found = mutableListOf<Pair<File, Int>>()
        val title = folder.name.normalizedGameTitle()
        fun collect(directory: File, depth: Int) {
            if (remaining <= 0) return
            for (file in children(directory)) {
                if (--remaining < 0) break
                if (LinuxGameDiscovery.directory(file) && depth < 3 && !file.isHidden && file.name.lowercase() !in ignored) {
                    collect(file, depth + 1)
                } else if (!file.isHidden && !GameFolderScanner.isHelperExecutable(file.name) && runnable(file)) {
                    val affinity = GameFolderScanner.nameAffinity(file.nameWithoutExtension, title)
                    val launcher = file.name.lowercase() in launchNames &&
                        (depth == 0 || file.parentFile.name.lowercase() in setOf("bin", "linux", "linux64"))
                    if (affinity > 0 || launcher) found += file to depth
                }
            }
        }
        collect(folder, 0)
        return found.sortedWith(compareByDescending<Pair<File, Int>> {
            GameFolderScanner.nameAffinity(it.first.nameWithoutExtension, title)
        }.thenBy { it.second }.thenBy { it.first.name }).firstOrNull()?.first
    }

    /** Strict subset: game Application entries, direct local executable, literal arguments.
     * Shell/env wrappers, PATH lookup, terminal/D-Bus activation and complex field expansion
     * are deliberately left to manual configuration rather than silently reinterpreted.
     */
    fun desktopTarget(file: File): GameLaunchTarget.Executable? = runCatching {
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) || !file.canRead() || file.length() > 64 * 1024) return null
        val values = linkedMapOf<String, String>()
        var inEntry = false
        val bytes = file.inputStream().use { it.readNBytes(64 * 1024 + 1) }
        if (bytes.size > 64 * 1024) return null
        bytes.toString(Charsets.UTF_8).lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith('[')) inEntry = line == "[Desktop Entry]"
            else if (inEntry && !line.startsWith('#') && '=' in line) {
                val key = line.substringBefore('=').trim()
                if (values.putIfAbsent(key, line.substringAfter('=').trim()) != null) return null
            }
        }
        if (values["Type"] != "Application" || values["Categories"].orEmpty().split(';').none { it == "Game" } ||
            listOf("Hidden", "NoDisplay", "Terminal", "DBusActivatable").any { values[it].equals("true", true) }) return null
        val tokens = desktopArguments(values["Exec"] ?: return null) ?: return null
        val executable = File(tokens.firstOrNull() ?: return null)
        if (!executable.isAbsolute || !runnable(executable) || GameFolderScanner.isHelperExecutable(executable.name) ||
            executable.name.lowercase() in wrappers) return null
        if (values["TryExec"] != null && values["TryExec"] != executable.absolutePath) return null
        val working = values["Path"]?.takeIf { it.isNotEmpty() }?.let {
            File(it).takeIf { path -> path.isAbsolute && path.isDirectory && path.canRead() }?.absolutePath ?: return null
        }
        GameLaunchTarget.Executable(executable.absolutePath, tokens.drop(1), working)
    }.getOrNull()

    private fun desktopArguments(value: String): List<String>? {
        // General desktop-entry string escapes precede Exec quoting. Restrict the subset to
        // whitespace/quotes and literal text; backslash escapes and shell syntax are rejected.
        if (value.any { it in "\\\n\r\u0000'`$|;&<>~*?#()" }) return null
        val tokens = mutableListOf<String>()
        val token = StringBuilder()
        var quoted = false
        var started = false
        for (c in value) when {
            c == '"' -> { quoted = !quoted; started = true }
            c.isWhitespace() && !quoted -> if (started) { tokens += token.toString(); token.clear(); started = false }
            else -> { if (quoted && c == '%') return null; token.append(c); started = true }
        }
        if (quoted) return null
        if (started) tokens += token.toString()
        if (tokens.isEmpty() || tokens.first().contains('%') || tokens.first().contains('=')) return null
        if (tokens.count { it in setOf("%f", "%F", "%u", "%U") } > 1) return null
        return tokens.mapNotNull { tokenValue ->
            when {
                tokenValue in setOf("%f", "%F", "%u", "%U") -> null // No file/URL is being opened.
                tokenValue.replace("%%", "").contains('%') -> return null
                else -> tokenValue.replace("%%", "%")
            }
        }
    }
}

internal fun linuxGamesHost(): Boolean = System.getProperty("os.name").equals("Linux", ignoreCase = true)
