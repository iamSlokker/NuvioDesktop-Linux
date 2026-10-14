package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopHdrMode

/**
 * Hands one main-player source from the embedded X11 player to the experimental
 * native-Wayland HDR session and back.
 *
 * Lifecycle (all state on the UI thread; native create/dispose on workers):
 * 1. The embedded player reports its decoded transfer. The pure policy gates run; only
 *    when all pass is the out-of-process capability probe started.
 * 2. Eligible: the embedded player is paused and an HDR session is created *paused* at
 *    the same position. It must load, transfer the user's track/subtitle/volume state,
 *    configure its window and report a PQ/HLG BT.2020 render target before it is
 *    promoted to the controller's handle; only then is the embedded player retired and
 *    playback resumed in the HDR window.
 * 3. Any failure before promotion disposes the HDR session and resumes the still-alive
 *    embedded player in place. Any failure or user exit afterwards re-creates the embedded
 *    player at the HDR session's position. Either way the source is not offered again for
 *    this player, so a broken path cannot flap.
 */
internal class LinuxHdrSessionCoordinator(
    private val host: Host,
    private val environment: Environment,
    private val worker: (String, () -> Unit) -> Unit,
    private val ui: (() -> Unit) -> Unit,
    private val schedule: (Long, () -> Unit) -> Unit,
) {
    data class PlaybackPoint(val positionMs: Long, val paused: Boolean)

    data class StartRequest(
        val generation: Long,
        val sourceKey: String,
        val positionMs: Long,
        val vulkanDevice: String,
        val targetOptions: List<String>,
    )

    interface Host {
        /** Changes whenever the controller attaches, replaces or loses its native player. */
        fun generation(): Long
        /** Source of the current main-player attach; null for trailers/hero surfaces. */
        fun sourceKey(): String?
        fun embeddedHandle(): Long
        fun playbackPoint(handle: Long): PlaybackPoint?
        fun setPaused(handle: Long, paused: Boolean)
        /** Worker thread. Returns 0 when the request is no longer current. */
        fun createHdrSession(request: StartRequest, sink: NativePlayerEventSink): Long
        /** Worker thread: copy live user state from the embedded player and replay app-owned properties. */
        fun prepareHdrSession(hdrHandle: Long, embeddedHandle: Long)
        /** UI thread: publish [hdrHandle] as the controller handle and retire [embeddedHandle]. */
        fun promoteHdrSession(hdrHandle: Long, embeddedHandle: Long, generation: Long): Boolean
        /** Asynchronous native disposal of a session that was never (or is no longer) published. */
        fun disposeDetachedHdrSession(hdrHandle: Long)
        /** UI thread: re-create the embedded player for the same source at [positionMs]. */
        fun resumeEmbeddedPlayer(positionMs: Long, playWhenReady: Boolean)
        fun forwardPlayerEvent(type: String, value: Double)
        fun handleHdrKey(hdrHandle: Long, code: Int)
        /** Installs (non-null) or removes the gamepad route into the HDR window. */
        fun setGamepadRoute(hdrHandle: Long?)
        fun applyHdrTargetOptions(hdrHandle: Long, options: List<Pair<String, String>>)
        fun showHdrMessage(hdrHandle: Long, text: String)
    }

    interface Environment {
        fun experimentalEnabled(): Boolean
        fun waylandDisplay(): String?
        fun hdrMode(): DesktopHdrMode
        fun libmpvWaylandSupported(): Boolean?
        fun probeHelperAvailable(): Boolean
        /** Worker thread, bounded by its own timeout; null when the probe cannot complete. */
        fun probe(): LinuxHdrCapabilities?
        fun targetOptions(mode: DesktopHdrMode): List<Pair<String, String>>
        fun log(message: String)
    }

    private sealed interface State {
        data object Idle : State
        data class Probing(val generation: Long, val source: String) : State
        class Starting(
            val generation: Long,
            val source: String,
            val embeddedHandle: Long,
            val resumePlaying: Boolean,
            val positionMs: Long,
        ) : State {
            var hdrHandle = 0L
            var fileLoaded = false
            var prepared = false
            var voConfigured = false
            var hdrTarget: Boolean? = null
        }
        data class Active(val generation: Long, val source: String, val hdrHandle: Long, val positionMs: Long) : State
        data object Disposed : State
    }

    private var state: State = State.Idle
    /** Sources already decided for the current attach generation. */
    private var evaluatedGeneration = -1L
    /** Sources whose HDR session failed or was left by the user: never offered again here. */
    private val declinedSources = mutableSetOf<String>()

    /** The published HDR session handle, when one owns playback. */
    val activeHdrHandle: Long?
        get() = (state as? State.Active)?.hdrHandle

    /** A new source/re-attach from the engine. An unpublished HDR start is abandoned. */
    fun onAttach() {
        abandonUnpublished("player re-attached")
        if (state !is State.Disposed) state = State.Idle
    }

    /** Ownership moved elsewhere (e.g. a hero surface): the controller handle is already retired. */
    fun onPlayerReplaced() = onAttach()

    fun dispose() {
        abandonUnpublished("player disposed")
        state = State.Disposed
    }

    /** Non-consuming observer of every event the controller handles. */
    fun onPlayerEvent(type: String) {
        if (!type.startsWith(LinuxShaderMedia.EVENT_PREFIX) || state !is State.Idle) return
        val source = host.sourceKey() ?: return
        val generation = host.generation()
        if (generation == evaluatedGeneration || source in declinedSources) return
        val transfer = linuxShaderMediaTransfer(type) ?: return
        evaluatedGeneration = generation
        val inputs = inputs(transfer)
        when (val gate = LinuxHdrSessionPolicy.preProbe(inputs)) {
            is LinuxHdrSessionDecision.Sdr -> {
                if (inputs.experimentalEnabled) environment.log("HDR session not selected: ${gate.reason}")
                return
            }
            else -> Unit
        }
        state = State.Probing(generation, source)
        worker("Nuvio-HDR-Probe") {
            val capabilities = runCatching { environment.probe() }.getOrNull()
            ui {
                val probing = state as? State.Probing ?: return@ui
                if (probing.generation != generation || host.generation() != generation) return@ui
                state = State.Idle
                // Re-read the gates: settings may have changed while the probe ran.
                when (val decision = LinuxHdrSessionPolicy.decide(inputs(transfer), capabilities)) {
                    is LinuxHdrSessionDecision.Hdr -> begin(generation, source, decision.vulkanDevice)
                    is LinuxHdrSessionDecision.Sdr -> environment.log("HDR session not selected: ${decision.reason}")
                    LinuxHdrSessionDecision.ProbeRequired -> Unit
                }
            }
        }
    }

    /** Leaves the HDR session at the current position (user request, settings change). */
    fun leave(reason: String) {
        when (val current = state) {
            is State.Starting -> failStart(current, reason)
            is State.Active -> fallback(current, reason)
            else -> Unit
        }
    }

    fun onHdrModeChanged(mode: DesktopHdrMode) {
        val active = state as? State.Active ?: return
        if (mode == DesktopHdrMode.AlwaysTonemap) {
            fallback(active, "HDR mode changed to Always Tonemap")
        } else {
            host.applyHdrTargetOptions(active.hdrHandle, environment.targetOptions(mode))
        }
    }

    fun showMessage(text: String): Boolean {
        val active = state as? State.Active ?: return false
        host.showHdrMessage(active.hdrHandle, text)
        return true
    }

    private fun inputs(transfer: String) = LinuxHdrSessionInputs(
        experimentalEnabled = environment.experimentalEnabled(),
        waylandDisplay = environment.waylandDisplay(),
        hdrMode = environment.hdrMode(),
        sourceTransfer = transfer,
        libmpvWaylandSupported = runCatching { environment.libmpvWaylandSupported() }.getOrNull(),
        probeHelperAvailable = environment.probeHelperAvailable(),
    )

    private fun begin(generation: Long, source: String, vulkanDevice: String) {
        val embedded = host.embeddedHandle()
        val point = embedded.takeIf { it != 0L }?.let(host::playbackPoint)
        if (point == null) {
            environment.log("HDR session not started: embedded player state is unavailable")
            return
        }
        host.setPaused(embedded, true)
        val starting = State.Starting(generation, source, embedded, !point.paused, point.positionMs)
        state = starting
        val request = StartRequest(
            generation = generation,
            sourceKey = source,
            positionMs = point.positionMs,
            vulkanDevice = vulkanDevice,
            targetOptions = environment.targetOptions(environment.hdrMode()).map { (key, value) -> "$key=$value" },
        )
        val sink = NativePlayerEventSink { type, value -> ui { onHdrEvent(starting, type, value) } }
        environment.log("Starting experimental HDR session on $vulkanDevice at ${point.positionMs} ms")
        worker("Nuvio-HDR-Create") {
            val created = runCatching { host.createHdrSession(request, sink) }
            ui {
                val handle = created.getOrDefault(0L)
                if (state !== starting) {
                    if (handle != 0L) host.disposeDetachedHdrSession(handle)
                    return@ui
                }
                if (handle == 0L) {
                    failStart(starting, created.exceptionOrNull()?.message ?: "HDR session was not created")
                    return@ui
                }
                starting.hdrHandle = handle
                schedule(START_TIMEOUT_MS) {
                    if (state === starting) failStart(starting, "HDR session did not confirm an HDR target in time")
                }
            }
        }
    }

    private fun onHdrEvent(owner: State, type: String, value: Double) {
        if (state !== owner) {
            // An HDR session that was promoted keeps its Active state object instead.
            val active = state as? State.Active ?: return
            if (owner !is State.Starting || owner.hdrHandle != active.hdrHandle) return
            onActiveEvent(active, type, value)
            return
        }
        val starting = owner as? State.Starting ?: return
        if (host.generation() != starting.generation) {
            failStart(starting, "player re-attached during HDR start")
            return
        }
        when {
            type == "fileLoaded" -> {
                if (starting.fileLoaded) return
                starting.fileLoaded = true
                val hdr = starting.hdrHandle
                worker("Nuvio-HDR-Prepare") {
                    val prepared = runCatching { host.prepareHdrSession(hdr, starting.embeddedHandle) }
                    ui {
                        if (state !== starting) return@ui
                        prepared.exceptionOrNull()?.let { environment.log("HDR state transfer incomplete: ${it.message}") }
                        starting.prepared = true
                        promoteIfReady(starting)
                    }
                }
            }
            type == "linuxHdrVoConfigured" -> {
                starting.voConfigured = value != 0.0
                promoteIfReady(starting)
            }
            type.startsWith(LINUX_HDR_TARGET_EVENT_PREFIX) -> {
                val verdict = linuxHdrTargetVerdict(type)
                if (verdict == false) {
                    failStart(starting, "Compositor/driver negotiated a non-HDR target (${type.removePrefix(LINUX_HDR_TARGET_EVENT_PREFIX)})")
                    return
                }
                starting.hdrTarget = verdict
                promoteIfReady(starting)
            }
            type == "linuxHdrVoFailed" -> failStart(starting, "HDR window/Vulkan output failed to initialise")
            type == "linuxHdrSessionEnded" -> failStart(starting, "HDR session ended during start")
            type.startsWith("mpvStartupError:") || type.startsWith("mpvPlaybackError:") ->
                failStart(starting, "HDR session playback error")
            type == "linuxHdrKey" -> {
                // Until promotion the embedded player owns playback; only exits are honoured.
                when (value.toInt()) {
                    LINUX_HDR_KEY_LEAVE -> failStart(starting, "user left during HDR start")
                    java.awt.event.KeyEvent.VK_ESCAPE -> {
                        failStart(starting, "user pressed Back during HDR start")
                        host.forwardPlayerEvent("back", 0.0)
                    }
                }
            }
        }
    }

    private fun promoteIfReady(starting: State.Starting) {
        if (!starting.fileLoaded || !starting.prepared || !starting.voConfigured || starting.hdrTarget != true) return
        val hdr = starting.hdrHandle
        if (!host.promoteHdrSession(hdr, starting.embeddedHandle, starting.generation)) {
            state = State.Idle
            host.disposeDetachedHdrSession(hdr)
            environment.log("HDR session discarded: the player changed before promotion")
            return
        }
        val active = State.Active(starting.generation, starting.source, hdr, starting.positionMs)
        state = active
        if (starting.resumePlaying) host.setPaused(hdr, false)
        host.setGamepadRoute(hdr)
        host.forwardPlayerEvent("displayHdr", 1.0)
        environment.log("Experimental HDR session active (PQ/HLG BT.2020 target confirmed by mpv)")
    }

    private fun onActiveEvent(active: State.Active, type: String, value: Double) {
        if (host.generation() != active.generation) return
        when {
            type == "linuxHdrKey" -> host.handleHdrKey(active.hdrHandle, value.toInt())
            type == "linuxHdrVoConfigured" -> if (value == 0.0) fallback(active, "HDR window output was lost")
            type.startsWith(LINUX_HDR_TARGET_EVENT_PREFIX) ->
                if (linuxHdrTargetVerdict(type) == false) fallback(active, "HDR target became SDR (${type.removePrefix(LINUX_HDR_TARGET_EVENT_PREFIX)})")
            type == "linuxHdrSessionEnded" -> fallback(active, "HDR session ended")
            // Start-only diagnostics; a running VO may log recoverable errors.
            type == "linuxHdrVoFailed" -> Unit
            else -> host.forwardPlayerEvent(type, value)
        }
    }

    private fun failStart(starting: State.Starting, reason: String) {
        if (state !== starting) return
        state = State.Idle
        declinedSources += starting.source
        environment.log("HDR session abandoned; continuing in the embedded player: $reason")
        if (starting.hdrHandle != 0L) host.disposeDetachedHdrSession(starting.hdrHandle)
        if (host.generation() == starting.generation && host.embeddedHandle() == starting.embeddedHandle &&
            starting.resumePlaying
        ) {
            host.setPaused(starting.embeddedHandle, false)
        }
    }

    private fun fallback(active: State.Active, reason: String) {
        if (state !== active) return
        val point = host.playbackPoint(active.hdrHandle)
        state = State.Idle
        declinedSources += active.source
        host.setGamepadRoute(null)
        environment.log("Leaving HDR session; continuing in the embedded player: $reason")
        if (host.generation() != active.generation) return
        host.resumeEmbeddedPlayer(point?.positionMs ?: active.positionMs, point?.paused?.not() ?: true)
    }

    private fun abandonUnpublished(reason: String) {
        when (val current = state) {
            is State.Starting -> {
                state = State.Idle
                if (current.hdrHandle != 0L) host.disposeDetachedHdrSession(current.hdrHandle)
                environment.log("HDR session start cancelled: $reason")
            }
            // A published session is the controller handle; its normal disposal path owns it.
            is State.Active -> host.setGamepadRoute(null)
            else -> Unit
        }
    }

    companion object {
        const val START_TIMEOUT_MS = 15_000L
    }
}
