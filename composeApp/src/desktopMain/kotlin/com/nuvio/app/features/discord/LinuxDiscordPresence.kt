package com.nuvio.app.features.discord

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Linux connection ownership and retry only; activities and RPC framing remain shared. */
internal class LinuxDiscordPresence(
    private val clientId: String,
    private val candidates: () -> List<Path> = { linuxDiscordSocketCandidates() },
    private val send: (DiscordIpcConnection, DiscordRichPresenceActivity) -> Unit,
    private val connect: (Path) -> DiscordIpcConnection = { DiscordIpcConnection(LinuxDiscordIpcTransport.connect(it)) },
    private val retryMs: Long = 1000,
    private val refreshMs: Long = 15_000,
) {
    private data class Update(val activity: DiscordRichPresenceActivity?)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val updates = Channel<Update>(Channel.CONFLATED)
    private val closed = AtomicBoolean(false)
    private val connectionLock = Any()
    private var connection: DiscordIpcConnection? = null

    init {
        require(retryMs > 0 && refreshMs > 0)
        scope.launch {
            var desired: DiscordRichPresenceActivity? = null
            var lastSent: String? = null
            try {
                while (isActive && !closed.get()) {
                    var update = if (desired == null) updates.receive() else withTimeoutOrNull(
                        if (currentConnection() == null) retryMs else refreshMs,
                    ) { updates.receive() }
                    while (true) {
                        val newer = updates.tryReceive().getOrNull() ?: break
                        update = newer
                    }
                    if (update != null) desired = update.activity
                    val activity = desired
                    if (activity == null) {
                        // Clearing never discovers/opens a client just to send an empty activity.
                        runCatching { currentConnection()?.setActivity(null) }
                        closeConnection()
                        lastSent = null
                        continue
                    }
                    try {
                        val active = currentConnection() ?: openConnection() ?: continue
                        val key = activity.toPayloadKey()
                        // Periodic refresh also detects a Discord restart with a static activity.
                        if (update == null || key != lastSent) {
                            send(active, activity)
                            lastSent = key
                        }
                    } catch (_: Exception) {
                        closeConnection()
                        lastSent = null
                        // Deliberately omit payloads, RPC errors and private socket paths from logs.
                    }
                }
            } finally { closeConnection() }
        }
    }

    fun update(activity: DiscordRichPresenceActivity?) {
        if (!closed.get()) updates.trySend(Update(activity))
    }

    fun shutdown() {
        closed.set(true)
        closeConnection() // Interrupt an in-flight handshake/read before cancelling the worker.
        scope.cancel()
        updates.close()
    }

    private fun currentConnection(): DiscordIpcConnection? = synchronized(connectionLock) { connection }

    private fun openConnection(): DiscordIpcConnection? {
        for (path in candidates()) {
            if (closed.get()) return null
            val candidate = runCatching { connect(path) }.getOrNull() ?: continue
            val owned = synchronized(connectionLock) {
                if (closed.get()) false else { connection = candidate; true }
            }
            if (!owned) { candidate.close(); return null }
            try {
                candidate.handshake(clientId)
                if (!closed.get()) return candidate
            } catch (_: Exception) {
                // Failed handshakes never leak a connected socket.
            }
            closeConnection()
        }
        return null
    }

    private fun closeConnection() {
        val previous = synchronized(connectionLock) { connection.also { connection = null } }
        runCatching { previous?.close() }
    }
}
