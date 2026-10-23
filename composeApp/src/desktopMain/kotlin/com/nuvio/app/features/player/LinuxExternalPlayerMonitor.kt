package com.nuvio.app.features.player

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * A Linux control channel, plus the extra `flatpak run --filesystem` grants it needs when the
 * player is a Flatpak (its /tmp is private, so the socket has to live somewhere both sides see).
 */
internal class LinuxExternalPlayerChannel(
    val channel: ExternalPlayerChannel,
    val sandboxFilesystems: List<String> = emptyList(),
)

/** The `$XDG_RUNTIME_DIR` folder holding Nuvio's mpv sockets; also the Flatpak `xdg-run/` grant. */
private const val LinuxMpvSocketFolder = "nuvio-mpv"

/**
 * Linux replacement for [prepareExternalPlayerChannel]'s Windows named pipe: mpv's JSON IPC goes
 * over a Unix socket. VLC's loopback HTTP interface is the same on both, so it is reused as is.
 */
internal fun prepareLinuxExternalPlayerChannel(
    control: ExternalPlayerControl,
    target: LinuxExternalPlayerTarget,
    runtimeDirectory: String? = System.getenv("XDG_RUNTIME_DIR"),
    mpvConfig: File? = linuxMpvConfigFile(),
): LinuxExternalPlayerChannel = when (control) {
    ExternalPlayerControl.Mpv -> prepareLinuxMpvChannel(target, runtimeDirectory, mpvConfig)
    ExternalPlayerControl.Vlc -> LinuxExternalPlayerChannel(prepareExternalPlayerChannel(control, target.displayPath))
    else -> LinuxExternalPlayerChannel(ExternalPlayerChannel(emptyList(), null))
}

private fun prepareLinuxMpvChannel(
    target: LinuxExternalPlayerTarget,
    runtimeDirectory: String?,
    mpvConfig: File?,
): LinuxExternalPlayerChannel {
    // As on Windows, a socket the user already set in mpv.conf (SVP and similar tools connect to
    // it) is reused rather than overridden: mpv serves only one. A Flatpak mpv reads its own
    // config, and a path inside its sandbox may not exist out here, so it always gets Nuvio's.
    if (target is LinuxExternalPlayerTarget.Executable) {
        configuredLinuxMpvIpcSocket(mpvConfig)?.let { configured ->
            return LinuxExternalPlayerChannel(
                ExternalPlayerChannel(emptyList(), LinuxMpvSocketReader(configured, ownsSocket = false)),
            )
        }
    }
    val runtimeFolder = runtimeDirectory
        ?.let { File(it) }
        ?.takeIf { it.isAbsolute && it.isDirectory }
        ?.let { File(it, LinuxMpvSocketFolder) }
        ?.takeIf(::ensurePrivateDirectory)
    // Without a runtime folder a host mpv can still use a private temp folder; a Flatpak mpv
    // cannot see the host's /tmp, so it falls back to process-lifetime tracking.
    val tempFolder = if (runtimeFolder == null && target is LinuxExternalPlayerTarget.Executable) {
        runCatching { Files.createTempDirectory("nuvio-mpv-").toFile() }.getOrNull()
    } else null
    val folder = runtimeFolder ?: tempFolder
        ?: return LinuxExternalPlayerChannel(ExternalPlayerChannel(emptyList(), null))
    val socket = File(folder, "mpv-${linuxSocketToken()}.sock").toPath()
    return LinuxExternalPlayerChannel(
        channel = ExternalPlayerChannel(
            launchArgs = listOf("--input-ipc-server=$socket"),
            reader = LinuxMpvSocketReader(
                socket,
                ownsSocket = true,
                ownedFolder = tempFolder?.toPath(),
            ),
        ),
        sandboxFilesystems = if (target is LinuxExternalPlayerTarget.Flatpak && runtimeFolder != null) {
            listOf("xdg-run/$LinuxMpvSocketFolder")
        } else emptyList(),
    )
}

private fun ensurePrivateDirectory(folder: File): Boolean = runCatching {
    val path = folder.toPath()
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
    }
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
}.getOrDefault(false)

private val socketRandom = SecureRandom()

private fun linuxSocketToken(): String =
    ByteArray(8).also(socketRandom::nextBytes).joinToString("") { "%02x".format(it) }

/** mpv's user config: `$XDG_CONFIG_HOME/mpv/mpv.conf`, else `~/.config/mpv/mpv.conf`. */
internal fun linuxMpvConfigFile(
    configHome: String? = System.getenv("XDG_CONFIG_HOME"),
    home: String? = System.getProperty("user.home"),
): File? {
    val base = configHome?.takeIf { it.isNotBlank() && File(it).isAbsolute }?.let(::File)
        ?: home?.takeIf { it.isNotBlank() }?.let { File(it, ".config") }
        ?: return null
    return File(base, "mpv/mpv.conf")
}

/**
 * The `input-ipc-server` set in [config], as an absolute socket path. mpv expands `~/` to the home
 * folder and `~~/` to its config folder; any other relative value depends on mpv's working
 * folder, which is not ours to know, so it is ignored and Nuvio's own socket is used instead.
 */
internal fun configuredLinuxMpvIpcSocket(
    config: File?,
    home: String? = System.getProperty("user.home"),
): Path? {
    if (config == null || !config.isFile) return null
    val value = runCatching { config.readLines() }.getOrNull()
        ?.asSequence()
        ?.map { it.substringBefore('#').trim() }
        ?.mapNotNull { LINUX_MPV_IPC_OPTION.matchEntire(it)?.groupValues?.get(1) }
        ?.lastOrNull()
        ?.trim()
        ?.trim('"')
        ?.takeIf { it.isNotBlank() }
        ?: return null
    val resolved = when {
        value.startsWith("~~/") -> File(config.parentFile, value.removePrefix("~~/"))
        value.startsWith("~/") -> home?.takeIf { it.isNotBlank() }?.let { File(it, value.removePrefix("~/")) }
        value.startsWith("/") -> File(value)
        else -> null
    }
    return resolved?.toPath()
}

private val LINUX_MPV_IPC_OPTION = Regex("""(?i)-{0,2}input-ipc-server\s*=\s*(.+)""")

private val linuxIpcJson = Json { ignoreUnknownKeys = true }

/**
 * Polls mpv's JSON IPC over its Unix socket, strictly request-then-read on one thread like the
 * Windows pipe reader. mpv also writes unrequested event lines; those are skipped by request id.
 */
internal class LinuxMpvSocketReader(
    private val socket: Path,
    private val ownsSocket: Boolean,
    private val ownedFolder: Path? = null,
) : ExternalPlayerReader {
    private var channel: SocketChannel? = null
    private var input: InputStream? = null
    private var nextRequestId = 1

    override fun read(): ExternalPlaybackSample? {
        if (channel == null) {
            // mpv creates the socket once it has started; until then there is nothing to read.
            if (!Files.exists(socket)) return null
            val opened = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                opened.connect(UnixDomainSocketAddress.of(socket))
            } catch (error: IOException) {
                runCatching { opened.close() }
                throw error
            }
            channel = opened
            input = BufferedInputStream(Channels.newInputStream(opened))
        }
        try {
            val positionSeconds = property("time-pos")?.doubleOrNull ?: return null
            val durationSeconds = property("duration")?.doubleOrNull ?: return null
            val paused = property("pause")?.booleanOrNull ?: false
            return ExternalPlaybackSample(
                positionMs = (positionSeconds * 1000.0).toLong(),
                durationMs = (durationSeconds * 1000.0).toLong(),
                isPaused = paused,
            )
        } catch (error: IOException) {
            disconnect()
            throw error
        }
    }

    private fun property(name: String): JsonPrimitive? {
        val connection = channel ?: throw EOFException("mpv IPC socket is not connected")
        val stream = input ?: throw EOFException("mpv IPC socket is not connected")
        val id = nextRequestId++
        val request = ByteBuffer.wrap(
            "{\"command\":[\"get_property\",\"$name\"],\"request_id\":$id}\n".toByteArray(Charsets.UTF_8),
        )
        while (request.hasRemaining()) connection.write(request)
        while (true) {
            val line = stream.readUtf8Line() ?: throw EOFException("mpv closed its IPC socket")
            val reply = runCatching { linuxIpcJson.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
            if (reply["request_id"]?.jsonPrimitive?.intOrNull != id) continue
            if (reply["error"]?.jsonPrimitive?.content != "success") return null
            return reply["data"] as? JsonPrimitive
        }
    }

    private fun disconnect() {
        runCatching { channel?.close() }
        channel = null
        input = null
    }

    override fun close() {
        disconnect()
        // A socket the user configured belongs to their setup; only Nuvio's own is removed.
        if (ownsSocket) runCatching { Files.deleteIfExists(socket) }
        ownedFolder?.let { folder -> runCatching { Files.deleteIfExists(folder) } }
    }
}

private fun InputStream.readUtf8Line(): String? {
    val buffer = ByteArrayOutputStream(128)
    while (true) {
        val byte = read()
        if (byte < 0) return if (buffer.size() > 0) buffer.toString(Charsets.UTF_8) else null
        if (byte == '\n'.code) return buffer.toString(Charsets.UTF_8)
        buffer.write(byte)
    }
}
