package com.nuvio.app.features.discord

import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** IO boundary for the existing Discord framing. Windows retains its RandomAccessFile transport. */
internal interface DiscordIpcTransport {
    fun beginCommand() {}
    fun readFully(bytes: ByteArray)
    fun write(bytes: ByteArray)
    fun close()
}

/** Fixed conventional paths only: no directory search, socket creation, or TCP fallback. */
internal fun linuxDiscordSocketCandidates(environment: Map<String, String> = System.getenv()): List<Path> {
    val roots = listOfNotNull(
        environment["XDG_RUNTIME_DIR"], environment["TMPDIR"], environment["TMP"], environment["TEMP"], "/tmp",
    ).mapNotNull { value ->
        runCatching { Path.of(value).takeIf { it.isAbsolute }?.normalize() }.getOrNull()
    }.distinct()
    return roots.flatMap { root ->
        // Flatpak Discord can expose its socket under the per-app runtime directory. Visibility
        // across sandboxes is not guaranteed; do not change permissions or sandbox configuration.
        // Snap keeps its own directory too, and the Flatpak Vesktop publishes under .flatpak/.
        listOf(
            root, root.resolve("app/com.discordapp.Discord"), root.resolve("app/com.discordapp.DiscordCanary"),
            root.resolve("snap.discord"), root.resolve("snap.discord-canary"), root.resolve("snap.vesktop"),
            root.resolve(".flatpak/dev.vencord.Vesktop/xdg-run"), root.resolve("app/dev.vencord.Vesktop"),
        ).flatMap { directory ->
            (0..9).map { directory.resolve("discord-ipc-$it") }
        }
    }.distinct()
}

internal class LinuxDiscordIpcTransport private constructor(
    private val channel: SocketChannel,
    private val selector: Selector,
    private val timeoutMs: Long,
) : DiscordIpcTransport {
    private var deadline = 0L

    override fun beginCommand() { deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs) }
    override fun readFully(bytes: ByteArray) = transfer(ByteBuffer.wrap(bytes), false)
    override fun write(bytes: ByteArray) = transfer(ByteBuffer.wrap(bytes), true)

    private fun transfer(buffer: ByteBuffer, write: Boolean) {
        while (buffer.hasRemaining()) {
            checkDeadline()
            val count = if (write) channel.write(buffer) else channel.read(buffer)
            if (count < 0) throw EOFException("Discord IPC disconnected")
            if (count == 0) await(if (write) SelectionKey.OP_WRITE else SelectionKey.OP_READ)
        }
    }

    private fun checkDeadline(): Long {
        if (Thread.currentThread().isInterrupted) throw IOException("Discord IPC interrupted")
        val remaining = deadline - System.nanoTime()
        if (remaining <= 0) throw IOException("Discord IPC command timed out")
        return remaining
    }

    private fun await(operation: Int) {
        channel.register(selector, operation)
        selector.select(TimeUnit.NANOSECONDS.toMillis(checkDeadline()).coerceAtLeast(1))
        selector.selectedKeys().clear()
    }

    override fun close() {
        try { channel.close() } finally { selector.close() }
    }

    companion object {
        fun connect(path: Path, timeoutMs: Long = 3000): LinuxDiscordIpcTransport {
            require(timeoutMs > 0)
            // Reject regular files without opening them for writing. Follow intentional socket
            // symlinks, commonly used to expose Discord across sandboxes.
            val mode = Files.getAttribute(path, "unix:mode") as Int
            if (mode and 0xf000 != 0xc000) throw IOException("Not a Unix socket")
            val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
            val selector = try { Selector.open() } catch (error: Throwable) { channel.close(); throw error }
            val transport = LinuxDiscordIpcTransport(channel, selector, timeoutMs)
            try {
                channel.configureBlocking(false)
                transport.beginCommand()
                if (!channel.connect(UnixDomainSocketAddress.of(path))) {
                    while (!channel.finishConnect()) transport.await(SelectionKey.OP_CONNECT)
                }
                return transport
            } catch (error: Throwable) {
                transport.close()
                throw error
            }
        }
    }
}
