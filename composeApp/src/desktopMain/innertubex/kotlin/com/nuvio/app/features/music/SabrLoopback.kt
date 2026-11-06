package com.nuvio.app.features.music

import co.touchlab.kermit.Logger
import com.metrolist.innertubex.sabr.ExperimentalSabrApi
import com.metrolist.innertubex.sabr.SabrAudioStream
import com.metrolist.innertubex.sabr.SabrBootstrap
import com.metrolist.innertubex.sabr.SabrChunk
import com.metrolist.innertubex.sabr.SabrVideoStream
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files
import java.security.SecureRandom
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * fork:innertubex-sabr — plays a SABR stream in the player, which only opens ordinary URLs.
 *
 * YouTube answers web clients' direct stream URLs with 403 after the first ~45 s, whatever PO token is sent
 * (measured on age-restricted videos through WEB_CREATOR, Nov 2026), while SABR with a page-bound token
 * delivers the whole video. SABR is a protocol of its own (UMP over POST, the client says where it is
 * playing), so this serves each track as a plain file on loopback: a random path, a known length and byte
 * ranges, so the player's demuxer seeks as it would on any web file. Behind it, InnerTubeX's SABR stream
 * fills a sparse file on disk; a read the stream will not reach soon restarts it at that point's time.
 * The stream is paced by how far the player has read.
 *
 * One stream at a time: opening one closes the last. Nothing reads for [IDLE_CLOSE_MS] and it closes
 * itself; closing deletes its files.
 */
@OptIn(ExperimentalSabrApi::class)
internal class SabrLoopback private constructor(
    private val http: HttpClient,
    private val bootstrap: SabrBootstrap,
    withVideo: Boolean,
) {
    class Urls(val video: String?, val audio: String)

    private val log = Logger.withTag("SabrLoopback")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Deleted on close; on exit too (files are registered after it, so they go first).
    private val dir: File = Files.createTempDirectory(DIR_PREFIX).toFile().apply { deleteOnExit() }
    private val secret = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val server = ServerSocket(0, 16, InetAddress.getLoopbackAddress())
    private val tracks: Map<String, Track> = buildMap {
        put("audio", Track(video = false, length = bootstrap.contentLengthBytes, mime = bootstrap.mimeType))
        if (withVideo) {
            put(
                "video",
                Track(
                    video = true,
                    length = bootstrap.selectedVideoContentLengthBytes,
                    mime = bootstrap.selectedVideoMimeType ?: "video/webm",
                ),
            )
        }
    }

    @Volatile private var closed = false
    @Volatile private var lastActivityMs = System.currentTimeMillis()
    private val openConnections = java.util.concurrent.atomic.AtomicInteger()

    val urls: Urls = "http://127.0.0.1:${server.localPort}/$secret".let { base ->
        Urls(video = if (withVideo) "$base/video" else null, audio = "$base/audio")
    }

    private fun start() {
        thread(name = "sabr-loopback-accept", isDaemon = true) { acceptLoop() }
        scope.launch {
            while (isActive) {
                delay(IDLE_CHECK_MS)
                if (openConnections.get() == 0 && System.currentTimeMillis() - lastActivityMs > IDLE_CLOSE_MS) {
                    log.i { "${bootstrap.videoId}: idle, closing" }
                    close()
                }
            }
        }
        tracks.values.forEach { it.fetchFrom(0L) }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { server.close() }
        scope.cancel()
        tracks.values.forEach { it.release() }
        dir.deleteRecursively()
    }

    /** One SABR media type: its sparse file, what of it is on disk, and the stream filling it. */
    private inner class Track(val video: Boolean, length: Long?, val mime: String) {
        val name = if (video) "video" else "audio"
        // Without a length (never seen: YouTube sends it) the file is served whole, without seeking.
        @Volatile var length: Long? = length?.takeIf { it > 0 }
        private val file = RandomAccessFile(File(dir, name).apply { deleteOnExit() }, "rw")
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        /** Bytes on disk: start -> end (exclusive), merged. */
        private val have = TreeMap<Long, Long>()
        /** Where media segments start: byte offset -> media time. */
        private val times = TreeMap<Long, Long>()
        private var fetch: Job? = null
        /** Bumped on every restart; a cancelled stream's late chunks are still written but steer nothing. */
        private var generation = 0
        private var fetchStartMs = 0L
        private var complete = false
        /** Highest byte the running stream has written: a read just past it only needs to wait. */
        private var frontier = -1L
        /** Set when the stream was restarted for a read at this offset; checked against the first segment. */
        private var seekTarget: Long? = null
        private var seekAttempts = 0
        @Volatile private var failed: String? = null
        /** Furthest offset a connection is reading at; the stream's playback clock. */
        private val readers = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        fun release() {
            lock.withLock {
                fetch?.cancel()
                changed.signalAll()
            }
            runCatching { file.close() }
        }

        /** Media time at [offset]: between the nearest segment starts seen on each side, else the file's ends. */
        private fun timeAt(offset: Long): Long = lock.withLock {
            val total = length ?: return times.floorEntry(offset)?.value ?: 0L
            val below = times.floorEntry(offset)
            if (below != null && below.key == offset) return below.value
            val above = times.ceilingEntry(offset)
            val (fromByte, fromMs) = below?.let { it.key to it.value } ?: (0L to 0L)
            val (toByte, toMs) = above?.let { it.key to it.value } ?: (total to bootstrap.durationMs)
            if (toByte <= fromByte) return fromMs
            fromMs + (toMs - fromMs) * (offset - fromByte) / (toByte - fromByte)
        }

        private fun readerTimeMs(): Long = timeAt(readers.values.maxOrNull() ?: 0L)

        fun fetchFrom(timeMs: Long) {
            lock.withLock {
                fetch?.cancel()
                frontier = -1L
                fetchStartMs = timeMs
                failed = null
                complete = false
                val run = ++generation
                fetch = scope.launch { run(run, timeMs) }
            }
        }

        private suspend fun run(run: Int, timeMs: Long) {
            try {
                val flow = if (video) {
                    SabrVideoStream(http, bootstrap, playbackPositionMs = ::readerTimeMs, initialPlayerTimeMs = timeMs).chunks()
                } else {
                    SabrAudioStream(http, bootstrap, playbackPositionMs = ::readerTimeMs, initialPlayerTimeMs = timeMs).chunks()
                }
                flow.collect { write(run, it) }
                lock.withLock {
                    if (run == generation) complete = true
                    changed.signalAll()
                }
                log.i { "${bootstrap.videoId} $name: stream from ${timeMs}ms complete" }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                log.w { "${bootstrap.videoId} $name: stream from ${timeMs}ms failed: ${error.message}" }
                lock.withLock {
                    if (run == generation) failed = error.message ?: error::class.simpleName
                    changed.signalAll()
                }
            }
        }

        private fun write(run: Int, chunk: SabrChunk) {
            val restartAt = lock.withLock {
                if (closed) return
                file.seek(chunk.startRange)
                file.write(chunk.data)
                val end = chunk.endRangeExclusive
                add(chunk.startRange, end)
                if (!chunk.isInitialization) chunk.startMs?.let { times[chunk.startRange] = it }
                changed.signalAll()
                if (run != generation) return
                if (!chunk.isInitialization) frontier = maxOf(frontier, end)
                val target = seekTarget
                if (target != null && !chunk.isInitialization) {
                    seekTarget = null
                    // Started past the read: go back. The estimate is a proportion of the file at first.
                    if (chunk.startRange > target && seekAttempts < MAX_SEEK_ATTEMPTS) {
                        seekAttempts++
                        seekTarget = target
                        ((chunk.startMs ?: fetchStartMs) - SEEK_BACKOFF_MS * seekAttempts).coerceAtLeast(0L)
                    } else {
                        null
                    }
                } else {
                    null
                }
            }
            restartAt?.let { fetchFrom(it) }
        }

        /** Must hold [lock]. */
        private fun add(start: Long, end: Long) {
            var newStart = start
            var newEnd = end
            have.floorEntry(start)?.let { if (it.value >= start) { newStart = it.key; newEnd = maxOf(newEnd, it.value) } }
            while (true) {
                val next = have.ceilingEntry(newStart) ?: break
                if (next.key > newEnd) break
                newEnd = maxOf(newEnd, next.value)
                have.remove(next.key)
            }
            have[newStart] = newEnd
        }

        /** How many bytes from [offset] are on disk now. Must hold [lock]. */
        private fun availableAt(offset: Long): Long {
            val entry = have.floorEntry(offset) ?: return 0
            return (entry.value - offset).coerceAtLeast(0)
        }

        /**
         * Waits until bytes at [offset] are on disk and returns how many, or 0 at the end, or -1 when the
         * stream failed or nothing arrived for [STALL_MS]. Restarts the stream when the read is somewhere
         * it will not reach soon.
         */
        fun await(reader: Int, offset: Long): Long {
            readers[reader] = offset
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STALL_MS)
            var restarted = false
            lock.withLock {
                while (!closed) {
                    length?.let { if (offset >= it) return 0 }
                    val ready = availableAt(offset)
                    if (ready > 0) return ready
                    if (failed != null && restarted) return -1
                    val running = fetch?.isActive == true
                    // Nothing more is coming past the end of a stream that ran from the start.
                    if (complete && fetchStartMs == 0L && have.ceilingKey(offset) == null && offset >= frontier) return 0
                    // A stream that has not delivered yet is waited for; it is checked again once it has.
                    val starting = running && frontier < 0
                    val near = running && frontier >= 0 && offset >= frontier - 1 && offset < frontier + NEAR_BYTES
                    if (!restarted && (failed != null || (!near && !starting))) {
                        restarted = true
                        seekAttempts = 0
                        seekTarget = offset
                        val at = (timeAt(offset) - SEEK_BACKOFF_MS).coerceAtLeast(0L)
                        log.i { "${bootstrap.videoId} $name: read at $offset, restarting the stream at ${at}ms" }
                        fetchFrom(at)
                        continue
                    }
                    val left = deadline - System.nanoTime()
                    if (left <= 0) return -1
                    changed.awaitNanos(minOf(left, TimeUnit.MILLISECONDS.toNanos(500)))
                }
            }
            return -1
        }

        fun read(offset: Long, into: ByteArray, count: Int): Int = lock.withLock {
            file.seek(offset)
            file.read(into, 0, count)
        }

        fun done(reader: Int) {
            readers.remove(reader)
        }
    }

    private fun acceptLoop() {
        var nextId = 0
        while (!closed) {
            val socket = try {
                server.accept()
            } catch (_: IOException) {
                break
            }
            val id = nextId++
            thread(name = "sabr-loopback-$id", isDaemon = true) {
                openConnections.incrementAndGet()
                try {
                    socket.use { serve(it, id) }
                } catch (_: IOException) {
                    // The player closed the connection to seek elsewhere.
                } finally {
                    openConnections.decrementAndGet()
                    lastActivityMs = System.currentTimeMillis()
                }
            }
        }
    }

    private fun serve(socket: Socket, id: Int) {
        socket.soTimeout = 30_000
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .mapNotNull { line -> line.indexOf(':').takeIf { it > 0 }?.let { line.substring(0, it).trim().lowercase() to line.substring(it + 1).trim() } }
            .toMap()
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)
        val path = parts.getOrNull(1).orEmpty()
        val out = socket.getOutputStream()
        val track = tracks[path.removePrefix("/$secret/")]?.takeIf { path.startsWith("/$secret/") }
        if (track == null || (method != "GET" && method != "HEAD")) {
            respond(out, "404 Not Found", mapOf("Content-Length" to "0"))
            return
        }
        lastActivityMs = System.currentTimeMillis()
        val length = track.length
        val range = headers["range"]?.let { Regex("""bytes=(\d+)-(\d*)""").matchEntire(it) }
        val start = range?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val requestedEnd = range?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toLongOrNull()
        if (length != null && start >= length) {
            respond(out, "416 Range Not Satisfiable", mapOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
            return
        }
        val end = listOfNotNull(requestedEnd?.plus(1), length).minOrNull()
        val responseHeaders = buildMap {
            put("Content-Type", track.mime.substringBefore(';').trim())
            put("Accept-Ranges", "bytes")
            put("Connection", "close")
            if (end != null) put("Content-Length", (end - start).toString())
            if (range != null && length != null) put("Content-Range", "bytes $start-${(end ?: length) - 1}/$length")
        }
        respond(out, if (range != null) "206 Partial Content" else "200 OK", responseHeaders)
        if (method == "HEAD") return
        val buffer = ByteArray(BLOCK_BYTES)
        var position = start
        try {
            while (!closed && (end == null || position < end)) {
                val ready = track.await(id, position)
                if (ready <= 0) break
                val count = minOf(ready, buffer.size.toLong(), (end ?: Long.MAX_VALUE) - position).toInt()
                val read = track.read(position, buffer, count)
                if (read <= 0) break
                out.write(buffer, 0, read)
                position += read
                lastActivityMs = System.currentTimeMillis()
            }
            out.flush()
        } catch (_: SocketException) {
            // The player seeks by closing and reconnecting.
        } finally {
            track.done(id)
        }
    }

    private fun respond(out: OutputStream, status: String, headers: Map<String, String>) {
        val head = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (key, value) -> append(key).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8192) throw IOException("request line too long")
            line.append(c.toChar())
        }
    }

    companion object {
        private const val IDLE_CHECK_MS = 30_000L
        private const val IDLE_CLOSE_MS = 3 * 60_000L
        private const val STALL_MS = 45_000L
        private const val BLOCK_BYTES = 256 * 1024
        /** A read this far past what the stream has written waits for it instead of restarting it. */
        private const val NEAR_BYTES = 8L * 1024 * 1024
        private const val SEEK_BACKOFF_MS = 5_000L
        private const val MAX_SEEK_ATTEMPTS = 4
        private const val DIR_PREFIX = "nuvio-sabr-"
        /** A directory of ours this old belongs to a run that did not exit cleanly. */
        private const val STALE_DIR_MS = 60 * 60_000L

        private var current: SabrLoopback? = null

        /** Starts serving [bootstrap]'s audio (and its selected video when [withVideo]); closes the last one. */
        @Synchronized
        fun open(http: HttpClient, bootstrap: SabrBootstrap, withVideo: Boolean): Urls {
            current?.close()
            sweepStaleDirs()
            val loopback = SabrLoopback(http, bootstrap, withVideo)
            current = loopback
            loopback.start()
            return loopback.urls
        }

        private fun sweepStaleDirs() {
            val now = System.currentTimeMillis()
            File(System.getProperty("java.io.tmpdir")).listFiles { file ->
                file.isDirectory && file.name.startsWith(DIR_PREFIX) && now - file.lastModified() > STALE_DIR_MS
            }?.forEach { it.deleteRecursively() }
        }
    }
}
