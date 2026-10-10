package com.nuvio.app.features.discord

import kotlinx.serialization.json.*
import java.io.EOFException
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Before
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** All endpoints are private temporary fixtures; never calls production environment discovery. */
class LinuxDiscordIpcTest {
    @Before fun linuxFixtureOnly() {
        assumeTrue("Linux filesystem/Unix-socket fixture", System.getProperty("os.name").equals("Linux", true))
    }

    private class Fixture : AutoCloseable {
        val directory = Files.createTempDirectory("nv-rpc-")
        val path = directory.resolve("discord-ipc-0")
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX).bind(UnixDomainSocketAddress.of(path))
        val executor = Executors.newSingleThreadExecutor()
        val peers = java.util.concurrent.CopyOnWriteArrayList<SocketChannel>()
        fun accept(): SocketChannel = server.accept().also { peers += it }
        fun connection(timeout: Long = 1000) = DiscordIpcConnection(LinuxDiscordIpcTransport.connect(path, timeout))
        override fun close() {
            server.close()
            peers.forEach { it.close() }
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS), "fixture worker leaked")
            directory.toFile().deleteRecursively()
        }
    }
    private data class Frame(val opcode: Int, val payload: JsonObject)
    private fun readFully(channel: SocketChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw EOFException()
    }
    private fun read(channel: SocketChannel): Frame {
        val header = ByteArray(8)
        readFully(channel, header)
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val opcode = buffer.int
        val size = buffer.int
        require(size in 0..1024 * 1024)
        val bytes = ByteArray(size)
        readFully(channel, bytes)
        return Frame(opcode, Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject)
    }
    private fun write(channel: SocketChannel, opcode: Int, payload: JsonObject, fragmented: Boolean = false) {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(opcode).putInt(bytes.size).array()
        val buffer = ByteBuffer.wrap(header + bytes)
        if (fragmented) while (buffer.hasRemaining()) {
            val one = ByteBuffer.wrap(byteArrayOf(buffer.get()))
            while (one.hasRemaining()) channel.write(one)
        } else while (buffer.hasRemaining()) channel.write(buffer)
    }
    private fun ready(channel: SocketChannel) {
        val handshake = read(channel)
        assertEquals(0, handshake.opcode)
        assertEquals("fixture-client", handshake.payload["client_id"]?.jsonPrimitive?.content)
        assertEquals(1, handshake.payload["v"]?.jsonPrimitive?.int)
        write(channel, 1, buildJsonObject { put("evt", "READY") }, true)
    }
    private fun ack(channel: SocketChannel, frame: Frame) = write(channel, 1, buildJsonObject {
        put("nonce", frame.payload["nonce"]!!)
    }, true)
    private fun <T> LinkedBlockingQueue<T>.next(): T = poll(4, TimeUnit.SECONDS) ?: error("fixture event timed out")
    private fun backend(fixture: Fixture, refresh: Long = 30): LinuxDiscordPresence = LinuxDiscordPresence(
        "fixture-client", candidates = { listOf(fixture.path) },
        send = { connection, activity -> connection.setActivity(activity.toDiscordActivity()) },
        connect = { DiscordIpcConnection(LinuxDiscordIpcTransport.connect(it, 500)) }, retryMs = 20, refreshMs = refresh,
    )
    private val activity = DiscordRichPresenceActivity(title = "日本 Game", type = DiscordRichPresenceActivityType.Browsing)

    @Test fun discoveryUsesOrderedAbsoluteRuntimeAndTemporaryRoots() {
        val paths = linuxDiscordSocketCandidates(mapOf("XDG_RUNTIME_DIR" to "/fixture/runtime", "TMPDIR" to "/fixture/tmp", "TMP" to "relative", "TEMP" to "/fixture/tmp"))
        assertEquals(Path.of("/fixture/runtime/discord-ipc-0"), paths.first())
        assertTrue(Path.of("/fixture/runtime/discord-ipc-9") in paths)
        assertTrue(Path.of("/fixture/runtime/app/com.discordapp.Discord/discord-ipc-0") in paths)
        assertTrue(paths.indexOf(Path.of("/fixture/tmp/discord-ipc-0")) > 9)
        assertEquals(paths.distinct(), paths)
        assertTrue(paths.all { it.isAbsolute })
        assertTrue(Path.of("/tmp/discord-ipc-0") in linuxDiscordSocketCandidates(emptyMap()))
    }
    @Test fun productionFramingHandshakePingUpdateAndClear(): Unit = Fixture().use { fixture ->
        val server = fixture.executor.submit {
            fixture.accept().use { peer ->
                ready(peer)
                val update = read(peer)
                assertEquals(1, update.opcode)
                assertEquals("SET_ACTIVITY", update.payload["cmd"]?.jsonPrimitive?.content)
                val args = update.payload["args"]!!.jsonObject
                assertEquals(ProcessHandle.current().pid(), args["pid"]!!.jsonPrimitive.long)
                assertEquals("日本 Game", args["activity"]!!.jsonObject["details"]!!.jsonPrimitive.content)
                write(peer, 3, buildJsonObject { put("ping", "value") })
                assertEquals(4, read(peer).opcode)
                write(peer, 1, buildJsonObject { put("nonce", "unrelated") })
                ack(peer, update)
                val clear = read(peer)
                assertEquals(JsonNull, clear.payload["args"]!!.jsonObject["activity"])
                ack(peer, clear)
            }
        }
        val client = fixture.connection()
        try { client.handshake("fixture-client"); client.setActivity(activity.toDiscordActivity()); client.setActivity(null) }
        finally { client.close() }
        server.get(4, TimeUnit.SECONDS)
    }
    @Test fun sharedPlaybackPayloadRetainsPauseAndTimelineSemantics() {
        val playing = activity.copy(type = DiscordRichPresenceActivityType.Playback, isPlaying = true, durationMs = 100_000, positionMs = 25_000)
        assertNotNull(playing.toDiscordActivity()["timestamps"])
        val paused = playing.copy(isPlaying = false).toDiscordActivity()
        assertNull(paused["timestamps"])
        assertTrue(paused["details"]!!.jsonPrimitive.content.contains("Paused"))
    }
    @Test fun absentSocketAndRegularFileAreNotOpenedOrModified() {
        val directory = Files.createTempDirectory("nv-rpc-absent-")
        try {
            assertFailsWith<IOException> { LinuxDiscordIpcTransport.connect(directory.resolve("missing"), 100) }
            val file = directory.resolve("discord-ipc-0")
            Files.writeString(file, "keep")
            assertFailsWith<IOException> { LinuxDiscordIpcTransport.connect(file, 100) }
            assertEquals("keep", Files.readString(file))
        } finally { directory.toFile().deleteRecursively() }
    }
    @Test fun invalidFrameLengthsAreRejected() {
        for (length in listOf(-1, 1024 * 1024 + 1)) Fixture().use { fixture ->
            val server = fixture.executor.submit {
                fixture.accept().use { peer ->
                    read(peer)
                    val buffer = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(length).flip()
                    while (buffer.hasRemaining()) peer.write(buffer)
                }
            }
            val client = fixture.connection()
            try { assertFailsWith<IOException> { client.handshake("fixture-client") } } finally { client.close() }
            server.get(3, TimeUnit.SECONDS)
        }
    }
    @Test fun malformedJsonAndDisconnectPropagateErrors() {
        for (malformed in listOf(false, true)) Fixture().use { fixture ->
            val server = fixture.executor.submit {
                fixture.accept().use { peer ->
                    read(peer)
                    if (malformed) {
                        val buffer = ByteBuffer.allocate(9).order(ByteOrder.LITTLE_ENDIAN).putInt(1).putInt(1).put('x'.code.toByte()).flip()
                        while (buffer.hasRemaining()) peer.write(buffer)
                    }
                }
            }
            val client = fixture.connection()
            try { assertFailsWith<IOException> { client.handshake("fixture-client") } } finally { client.close() }
            server.get(3, TimeUnit.SECONDS)
        }
    }
    @Test fun stalledHandshakeHasBoundedTimeoutAndCleanup(): Unit = Fixture().use { fixture ->
        val ended = LinkedBlockingQueue<Boolean>()
        val server = fixture.executor.submit {
            fixture.accept().use { peer -> read(peer); assertEquals(-1, peer.read(ByteBuffer.allocate(1))); ended.put(true) }
        }
        val client = fixture.connection(100)
        try { assertFailsWith<IOException> { client.handshake("fixture-client") } } finally { client.close() }
        assertTrue(ended.next())
        server.get(3, TimeUnit.SECONDS)
    }
    @Test fun workerRetriesUnchangedActivityAfterClientAppears(): Unit = Fixture().use { fixture ->
        val attempts = LinkedBlockingQueue<Int>()
        val received = LinkedBlockingQueue<Frame>()
        val count = AtomicInteger()
        val server = fixture.executor.submit {
            fixture.accept().use { peer -> ready(peer); val command = read(peer); received.put(command); ack(peer, command) }
        }
        val backend = LinuxDiscordPresence("fixture-client", candidates = { listOf(fixture.path) },
            send = { connection, value -> connection.setActivity(value.toDiscordActivity()) },
            connect = { path ->
                val attempt = count.incrementAndGet(); attempts.put(attempt)
                if (attempt == 1) throw IOException("simulated absence")
                DiscordIpcConnection(LinuxDiscordIpcTransport.connect(path, 500))
            }, retryMs = 20, refreshMs = 500)
        try {
            backend.update(activity)
            assertEquals(1, attempts.next()); assertEquals(2, attempts.next())
            assertEquals("SET_ACTIVITY", received.next().payload["cmd"]!!.jsonPrimitive.content)
            server.get(3, TimeUnit.SECONDS)
        } finally { backend.shutdown() }
    }
    @Test fun workerReconnectsAfterDisconnectWithoutANewActivity(): Unit = Fixture().use { fixture ->
        val received = LinkedBlockingQueue<Int>()
        val server = fixture.executor.submit {
            repeat(2) { index -> fixture.accept().use { peer ->
                ready(peer); val command = read(peer); ack(peer, command); received.put(index)
            } }
        }
        val backend = backend(fixture)
        try {
            backend.update(activity)
            assertEquals(0, received.next()); assertEquals(1, received.next())
            server.get(3, TimeUnit.SECONDS)
        } finally { backend.shutdown() }
    }
    @Test fun workerClearSendsNullAndClosesWithoutReconnecting(): Unit = Fixture().use { fixture ->
        val received = LinkedBlockingQueue<Boolean>()
        val server = fixture.executor.submit {
            fixture.accept().use { peer ->
                ready(peer); val command = read(peer); ack(peer, command); received.put(true)
                val clear = read(peer)
                assertEquals(JsonNull, clear.payload["args"]!!.jsonObject["activity"])
                ack(peer, clear)
                assertEquals(-1, peer.read(ByteBuffer.allocate(1)))
                received.put(true)
            }
        }
        val backend = backend(fixture, refresh = 10_000)
        try { backend.update(activity); assertTrue(received.next()); backend.update(null); assertTrue(received.next()); server.get(3, TimeUnit.SECONDS) }
        finally { backend.shutdown() }
    }
    @Test fun existingSocketWithNoListenerFailsGracefully(): Unit = Fixture().use { fixture ->
        fixture.server.close()
        assertFailsWith<IOException> { LinuxDiscordIpcTransport.connect(fixture.path, 100) }
    }
    @Test fun shutdownInterruptsInFlightHandshakeAndPreventsNewUpdates(): Unit = Fixture().use { fixture ->
        val ready = LinkedBlockingQueue<Boolean>()
        val server = fixture.executor.submit {
            fixture.accept().use { peer -> read(peer); ready.put(true); assertEquals(-1, peer.read(ByteBuffer.allocate(1))) }
        }
        val backend = backend(fixture)
        backend.update(activity)
        assertTrue(ready.next())
        backend.shutdown()
        backend.update(activity.copy(title = "must not reopen"))
        server.get(3, TimeUnit.SECONDS)
    }
    @Test fun failedHandshakeIsClosedBeforeTryingNextCandidate(): Unit = Fixture().use { fixture ->
        val received = LinkedBlockingQueue<Boolean>()
        val server = fixture.executor.submit {
            fixture.accept().use { peer ->
                read(peer)
                write(peer, 1, buildJsonObject { put("evt", "ERROR"); put("data", buildJsonObject { put("code", 4000); put("message", "fixture rejection") }) })
                assertEquals(-1, peer.read(ByteBuffer.allocate(1)))
            }
            fixture.accept().use { peer -> ready(peer); val command = read(peer); ack(peer, command); received.put(true) }
        }
        val backend = LinuxDiscordPresence("fixture-client", candidates = { listOf(fixture.path, fixture.path) },
            send = { connection, value -> connection.setActivity(value.toDiscordActivity()) }, retryMs = 20, refreshMs = 10_000)
        try { backend.update(activity); assertTrue(received.next()); server.get(3, TimeUnit.SECONDS) } finally { backend.shutdown() }
    }
}
