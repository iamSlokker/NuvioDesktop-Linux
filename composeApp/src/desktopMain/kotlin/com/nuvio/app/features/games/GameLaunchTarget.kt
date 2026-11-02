package com.nuvio.app.features.games

import com.nuvio.app.core.i18n.localizedString
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.linux_games_desktop_entry_unavailable

/**
 * What a library entry's launch target actually runs, once a shortcut has been followed.
 *
 * People who launch games from Desktop or Start Menu shortcuts have `.lnk` and `.url` files, not
 * executables, and a shortcut handed to `ProcessBuilder` is refused as "not a valid Win32
 * application". Resolving happens at launch rather than when the entry is saved, so the library
 * keeps pointing at the shortcut and follows it if the user later re-targets it.
 */
sealed interface GameLaunchTarget {
    /** A file to start, with whatever the shortcut (not the entry) says to pass and run from. */
    data class Executable(
        val path: String,
        val arguments: List<String> = emptyList(),
        val workingDirectory: String? = null,
    ) : GameLaunchTarget

    /** A protocol URL for the OS handler: a Steam `.url` shortcut is one of these. */
    data class Protocol(val url: String) : GameLaunchTarget

    /**
     * A shortcut that names no file this launcher could start on its own — a Store app, an
     * MSI-advertised install, or one that could not be read at all. Only the shell can run it,
     * and there is no process to watch afterwards.
     */
    data class ShellShortcut(val path: String) : GameLaunchTarget
}

/** Whether [path] names a Windows shortcut file rather than the thing it launches. */
fun isShortcutFile(path: String): Boolean {
    val name = path.trim().substringAfterLast('\\').substringAfterLast('/')
    return name.endsWith(".lnk", ignoreCase = true) || name.endsWith(".url", ignoreCase = true)
}

fun resolveGameLaunchTarget(path: String): GameLaunchTarget {
    val trimmed = path.trim()
    if (isProtocolLaunchTarget(trimmed)) return GameLaunchTarget.Protocol(trimmed)
    if (linuxGamesHost() && trimmed.endsWith(".desktop", ignoreCase = true)) {
        return LinuxGameDiscovery.desktopTarget(File(trimmed))
            ?: error(localizedString(Res.string.linux_games_desktop_entry_unavailable))
    }
    if (!isShortcutFile(trimmed)) return GameLaunchTarget.Executable(trimmed)
    val shortcut = readShortcut(File(trimmed)) ?: return GameLaunchTarget.ShellShortcut(trimmed)
    shortcut.url?.let { url ->
        return if (isProtocolLaunchTarget(url)) GameLaunchTarget.Protocol(url)
        else GameLaunchTarget.ShellShortcut(trimmed)
    }
    val target = shortcut.targetPath ?: return GameLaunchTarget.ShellShortcut(trimmed)
    return GameLaunchTarget.Executable(
        path = target,
        arguments = parseArguments(shortcut.arguments),
        workingDirectory = shortcut.workingDirectory?.takeIf(String::isNotBlank),
    )
}

/**
 * The folder an entry runs from when the user leaves it blank: the shortcut's own working
 * directory (then its target's folder) for a shortcut, the executable's folder otherwise. A
 * protocol URL has no folder, and `File("steam://…").parent` would invent one.
 */
fun defaultWorkingDirectory(path: String): String? = when (val target = resolveGameLaunchTarget(path)) {
    is GameLaunchTarget.Executable -> target.workingDirectory ?: File(target.path).parent
    is GameLaunchTarget.Protocol, is GameLaunchTarget.ShellShortcut -> null
}

/** What a `.lnk` or `.url` file says, as far as launching is concerned. */
internal data class ShortcutContents(
    /** The launched file, absolute; null when the shortcut only identifies a shell item. */
    val targetPath: String? = null,
    /** The command line as one string, as the shortcut stores it. */
    val arguments: String = "",
    val workingDirectory: String? = null,
    /** Set for a `.url` file, where the target is a URL rather than a path. */
    val url: String? = null,
)

/** Reads [file] as a shortcut, or null when it is not one that can be read. */
internal fun readShortcut(file: File): ShortcutContents? {
    if (!file.isFile) return null
    return runCatching {
        when {
            file.name.endsWith(".url", ignoreCase = true) -> parseInternetShortcut(file.readText(Charsets.ISO_8859_1))
            else -> parseShellLink(file.readBytes(), file.absoluteFile.parentFile)
        }
    }.getOrNull()
}

/**
 * A `.url` file is an INI file with an `[InternetShortcut]` section; `URL=` is the whole point.
 * Steam, Epic and Ubisoft all write these for their desktop shortcuts.
 */
internal fun parseInternetShortcut(text: String): ShortcutContents? {
    var inSection = false
    var url: String? = null
    var workingDirectory: String? = null
    text.lineSequence().forEach { rawLine ->
        val line = rawLine.trim()
        when {
            line.startsWith("[") -> inSection = line.equals("[InternetShortcut]", ignoreCase = true)
            !inSection || '=' !in line -> Unit
            else -> {
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim()
                when {
                    key.equals("URL", ignoreCase = true) && url == null -> url = value.takeIf(String::isNotBlank)
                    key.equals("WorkingDirectory", ignoreCase = true) -> workingDirectory = value.takeIf(String::isNotBlank)
                }
            }
        }
    }
    return url?.let { ShortcutContents(url = it, workingDirectory = workingDirectory) }
}

/**
 * Reads a Shell Link (`.lnk`) file per [MS-SHLLINK], without the shell.
 *
 * The header is followed by, in order and each present only when its flag says so: the item id
 * list (the target as a walk through the shell namespace — drive, folders, file — which is the
 * only handle a Store app shortcut has and, for some installers' shortcuts, the only path at all),
 * the link info (the target's path as it was when the shortcut was made), the string data (name,
 * relative path, working directory, arguments, icon), and extra data blocks, of which only the
 * environment-variable block matters: it carries an unexpanded `%ProgramFiles%\…` form of the
 * target that installers prefer to the literal path.
 *
 * The target is whichever of those forms names a file that exists, so a shortcut made on another
 * machine or before an install moved still resolves when any one of them is right; failing all,
 * whichever form is present, so the launcher can at least say what is missing. The relative path
 * is last in both orders: Riot's shortcuts carry one that is wrong from where they are written,
 * and the shell ignores it in favour of the id list.
 */
internal fun parseShellLink(
    bytes: ByteArray,
    linkDirectory: File?,
    environment: (String) -> String? = System::getenv,
): ShortcutContents? {
    if (bytes.size < SHELL_LINK_HEADER_SIZE) return null
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (buffer.getInt(0) != SHELL_LINK_HEADER_SIZE) return null
    if (!SHELL_LINK_CLSID.contentEquals(bytes.copyOfRange(4, 20))) return null
    val flags = buffer.getInt(20)
    fun has(flag: Int) = flags and flag != 0

    var offset = SHELL_LINK_HEADER_SIZE
    var idListPath: String? = null
    if (has(HAS_LINK_TARGET_ID_LIST)) {
        val size = buffer.getUnsignedShort(offset)
        idListPath = runCatching { parseItemIdListPath(buffer, offset + 2, size) }.getOrNull()
        offset += 2 + size
    }
    var linkInfoPath: String? = null
    if (has(HAS_LINK_INFO)) {
        val size = buffer.getInt(offset)
        linkInfoPath = runCatching { parseLinkInfo(buffer, offset) }.getOrNull()
        offset += size
    }

    val unicode = has(IS_UNICODE)
    fun readStringData(): String {
        val count = buffer.getUnsignedShort(offset)
        offset += 2
        val length = if (unicode) count * 2 else count
        val value = String(bytes, offset, length, if (unicode) Charsets.UTF_16LE else ansiCharset)
        offset += length
        return value
    }
    if (has(HAS_NAME)) readStringData()
    val relativePath = if (has(HAS_RELATIVE_PATH)) readStringData() else null
    val workingDirectory = if (has(HAS_WORKING_DIR)) readStringData() else null
    val arguments = if (has(HAS_ARGUMENTS)) readStringData() else ""
    if (has(HAS_ICON_LOCATION)) readStringData()

    var environmentTarget: String? = null
    var advertised = false
    while (offset + 8 <= bytes.size) {
        val size = buffer.getInt(offset)
        if (size < 4) break
        val signature = buffer.getInt(offset + 4)
        if (signature == DARWIN_BLOCK) advertised = true
        if (signature == ENVIRONMENT_VARIABLE_BLOCK && size >= ENVIRONMENT_VARIABLE_BLOCK_SIZE) {
            environmentTarget = buffer.nullTerminatedUnicode(offset + 8 + 260, limit = 260)
                .ifBlank { buffer.nullTerminatedAnsi(offset + 8, limit = 260) }
                .takeIf(String::isNotBlank)
                ?.let { expandEnvironmentVariables(it, environment) }
        }
        offset += size
    }

    // An MSI-advertised shortcut is launched through the installer service, which locates (or
    // repairs) the product first; the path it stores is a placeholder, usually the icon. Only
    // the shell knows the hand-off, so it gets no target here.
    if (advertised) return ShortcutContents(arguments = arguments, workingDirectory = workingDirectory)

    val candidates = listOfNotNull(
        environmentTarget,
        linkInfoPath,
        idListPath,
        relativePath?.takeIf(String::isNotBlank)?.let { relative ->
            (linkDirectory?.resolve(relative) ?: File(relative)).normalize().absolutePath
        },
    )
    val targetPath = candidates.firstOrNull { File(it).isFile }
        ?: linkInfoPath ?: idListPath ?: candidates.firstOrNull()
    return ShortcutContents(
        targetPath = targetPath,
        arguments = arguments,
        workingDirectory = workingDirectory?.takeIf(String::isNotBlank)?.let { expandEnvironmentVariables(it, environment) },
    )
}

/**
 * The LinkInfo structure's path: `LocalBasePath + CommonPathSuffix` for a local target, or
 * `NetName\CommonPathSuffix` for one on a share. Header sizes of 0x24 and up carry Unicode copies
 * of the same strings, which win over the ANSI ones when present.
 */
private fun parseLinkInfo(buffer: ByteBuffer, base: Int): String? {
    val headerSize = buffer.getInt(base + 4)
    val infoFlags = buffer.getInt(base + 8)
    val localBasePathOffset = buffer.getInt(base + 16)
    val networkLinkOffset = buffer.getInt(base + 20)
    val commonPathSuffixOffset = buffer.getInt(base + 24)
    val hasUnicodeOffsets = headerSize >= 0x24
    val localBasePathOffsetUnicode = if (hasUnicodeOffsets) buffer.getInt(base + 28) else 0
    val commonPathSuffixOffsetUnicode = if (hasUnicodeOffsets) buffer.getInt(base + 32) else 0

    val suffix = if (commonPathSuffixOffsetUnicode > 0) buffer.nullTerminatedUnicode(base + commonPathSuffixOffsetUnicode)
    else buffer.nullTerminatedAnsi(base + commonPathSuffixOffset)

    if (infoFlags and LINK_INFO_VOLUME_ID_AND_LOCAL_BASE_PATH != 0) {
        val localBase = if (localBasePathOffsetUnicode > 0) buffer.nullTerminatedUnicode(base + localBasePathOffsetUnicode)
        else buffer.nullTerminatedAnsi(base + localBasePathOffset)
        return (localBase + suffix).takeIf(String::isNotBlank)
    }
    if (infoFlags and LINK_INFO_COMMON_NETWORK_RELATIVE_LINK != 0) {
        val networkBase = base + networkLinkOffset
        val netNameOffset = buffer.getInt(networkBase + 8)
        val netNameOffsetUnicode = if (netNameOffset > 0x14) buffer.getInt(networkBase + 20) else 0
        val netName = if (netNameOffsetUnicode > 0) buffer.nullTerminatedUnicode(networkBase + netNameOffsetUnicode)
        else buffer.nullTerminatedAnsi(networkBase + netNameOffset)
        return listOf(netName.trimEnd('\\'), suffix).filter(String::isNotBlank).joinToString("\\")
            .takeIf(String::isNotBlank)
    }
    return null
}

/**
 * The file-system path an item id list spells out, or null when it walks somewhere else (a Store
 * app, a Control Panel item, a network place with no drive).
 *
 * Each item is a length-prefixed shell item. A drive item (`0x2F`) carries `C:\` as ANSI text; a
 * file or folder item (`0x3x`) carries the 8.3 short name as ANSI text at +12, and its long name
 * in the `0xBEEF0004` extension block that follows, at an offset that depends on the block's
 * version. None of this is in the published spec — it is the shape every shell-item reader
 * agrees on and the one Windows has written since XP.
 */
private fun parseItemIdListPath(buffer: ByteBuffer, start: Int, size: Int): String? {
    var drive: String? = null
    val names = mutableListOf<String>()
    var position = start
    val end = start + size
    while (position + 2 <= end) {
        val itemSize = buffer.getUnsignedShort(position)
        if (itemSize < 3) break
        val itemStart = position + 2
        val itemEnd = position + itemSize
        val type = buffer.get(itemStart).toInt() and 0xFF
        when {
            type == 0x2F -> drive = buffer.nullTerminatedAnsi(itemStart + 1, limit = itemEnd - itemStart - 1)
            type and 0x70 == 0x30 && itemEnd - itemStart > 14 -> {
                val shortName = buffer.nullTerminatedAnsi(itemStart + 12, limit = itemEnd - itemStart - 12)
                names += itemLongName(buffer, itemStart + 12, itemEnd) ?: shortName
            }
        }
        position = itemEnd
    }
    if (drive == null || names.isEmpty()) return null
    return drive.trimEnd('\\') + "\\" + names.joinToString("\\")
}

/** The long name from a file item's `0xBEEF0004` extension block, if the item has one. */
private fun itemLongName(buffer: ByteBuffer, from: Int, itemEnd: Int): String? {
    var position = from
    while (position + 8 <= itemEnd) {
        val blockSize = buffer.getUnsignedShort(position)
        if (blockSize >= 8 && position + blockSize <= itemEnd &&
            buffer.getInt(position + 4) == FILE_ENTRY_EXTENSION_SIGNATURE
        ) {
            val version = buffer.getUnsignedShort(position + 2)
            val nameOffset = when {
                version >= 9 -> 46
                version == 8 -> 42
                version == 7 -> 38
                version >= 3 -> 20
                else -> return null
            }
            return buffer.nullTerminatedUnicode(position + nameOffset, limit = (blockSize - nameOffset) / 2)
                .takeIf(String::isNotBlank)
        }
        position += 1
    }
    return null
}

private fun ByteBuffer.getUnsignedShort(index: Int): Int = getShort(index).toInt() and 0xFFFF

private fun ByteBuffer.nullTerminatedAnsi(start: Int, limit: Int = Int.MAX_VALUE): String {
    if (start < 0 || start >= capacity()) return ""
    var end = start
    val stop = minOf(capacity().toLong(), start + limit.toLong()).toInt()
    while (end < stop && get(end) != 0.toByte()) end++
    return String(array(), start, end - start, ansiCharset)
}

private fun ByteBuffer.nullTerminatedUnicode(start: Int, limit: Int = Int.MAX_VALUE): String {
    if (start < 0 || start + 1 >= capacity()) return ""
    var end = start
    val stop = minOf((capacity() - 1).toLong(), start + limit.toLong() * 2).toInt()
    while (end < stop && getShort(end) != 0.toShort()) end += 2
    return String(array(), start, end - start, Charsets.UTF_16LE)
}

/** `%ProgramFiles(x86)%\Steam\steam.exe` and the like; an unknown variable is left as written. */
internal fun expandEnvironmentVariables(value: String, lookup: (String) -> String? = System::getenv): String =
    ENVIRONMENT_VARIABLE.replace(value) { match ->
        lookup(match.groupValues[1]) ?: match.value
    }

private val ENVIRONMENT_VARIABLE = Regex("%([^%]+)%")

/**
 * The code page the shell wrote the ANSI strings in. `sun.jnu.encoding` is the JVM's name for the
 * system ANSI code page (`file.encoding` has been UTF-8 regardless since JDK 18).
 */
private val ansiCharset: Charset = runCatching {
    Charset.forName(System.getProperty("sun.jnu.encoding"))
}.getOrDefault(Charsets.ISO_8859_1)

private const val SHELL_LINK_HEADER_SIZE = 0x4C
private val SHELL_LINK_CLSID = byteArrayOf(
    0x01, 0x14, 0x02, 0x00, 0x00, 0x00, 0x00, 0x00,
    0xC0.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x46,
)
private const val HAS_LINK_TARGET_ID_LIST = 1 shl 0
private const val HAS_LINK_INFO = 1 shl 1
private const val HAS_NAME = 1 shl 2
private const val HAS_RELATIVE_PATH = 1 shl 3
private const val HAS_WORKING_DIR = 1 shl 4
private const val HAS_ARGUMENTS = 1 shl 5
private const val HAS_ICON_LOCATION = 1 shl 6
private const val IS_UNICODE = 1 shl 7
private const val LINK_INFO_VOLUME_ID_AND_LOCAL_BASE_PATH = 1 shl 0
private const val LINK_INFO_COMMON_NETWORK_RELATIVE_LINK = 1 shl 1
private const val ENVIRONMENT_VARIABLE_BLOCK = 0xA0000001.toInt()
private const val DARWIN_BLOCK = 0xA0000006.toInt()
private const val FILE_ENTRY_EXTENSION_SIGNATURE = 0xBEEF0004.toInt()
private const val ENVIRONMENT_VARIABLE_BLOCK_SIZE = 0x314
