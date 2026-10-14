package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopHdrMode
import com.nuvio.app.features.player.desktopHdrTargetMpvProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Handoff/fallback state machine with a fake controller and native session; no display or JNI. */
class LinuxHdrSessionCoordinatorTest {
    private class FakeHost : LinuxHdrSessionCoordinator.Host {
        var generation = 1L
        var source: String? = "https://example.invalid/movie.mkv"
        var embedded = 10L
        val points = mutableMapOf(10L to LinuxHdrSessionCoordinator.PlaybackPoint(42_000L, paused = false))
        val paused = mutableMapOf<Long, Boolean>()
        val requests = mutableListOf<LinuxHdrSessionCoordinator.StartRequest>()
        var nextHdr = 20L
        var createFailure: Throwable? = null
        var sink: NativePlayerEventSink? = null
        val prepared = mutableListOf<Pair<Long, Long>>()
        var promoteAllowed = true
        val promoted = mutableListOf<Long>()
        val disposedDetached = mutableListOf<Long>()
        val resumed = mutableListOf<Pair<Long, Boolean>>()
        val forwarded = mutableListOf<Pair<String, Double>>()
        val keys = mutableListOf<Int>()
        val routes = mutableListOf<Long?>()
        val targetOptions = mutableListOf<List<Pair<String, String>>>()
        val messages = mutableListOf<String>()

        override fun generation() = generation
        override fun sourceKey() = source
        override fun embeddedHandle() = embedded
        override fun playbackPoint(handle: Long) = points[handle]
        override fun setPaused(handle: Long, paused: Boolean) { this.paused[handle] = paused }
        override fun createHdrSession(request: LinuxHdrSessionCoordinator.StartRequest, sink: NativePlayerEventSink): Long {
            createFailure?.let { throw it }
            requests += request
            this.sink = sink
            return nextHdr
        }
        override fun prepareHdrSession(hdrHandle: Long, embeddedHandle: Long) { prepared += hdrHandle to embeddedHandle }
        override fun promoteHdrSession(hdrHandle: Long, embeddedHandle: Long, generation: Long): Boolean {
            if (!promoteAllowed || generation != this.generation || embeddedHandle != embedded) return false
            promoted += hdrHandle
            embedded = hdrHandle
            return true
        }
        override fun disposeDetachedHdrSession(hdrHandle: Long) { disposedDetached += hdrHandle }
        override fun resumeEmbeddedPlayer(positionMs: Long, playWhenReady: Boolean) {
            resumed += positionMs to playWhenReady
            generation += 1
        }
        override fun forwardPlayerEvent(type: String, value: Double) { forwarded += type to value }
        override fun handleHdrKey(hdrHandle: Long, code: Int) { keys += code }
        override fun setGamepadRoute(hdrHandle: Long?) { routes += hdrHandle }
        override fun applyHdrTargetOptions(hdrHandle: Long, options: List<Pair<String, String>>) { targetOptions += options }
        override fun showHdrMessage(hdrHandle: Long, text: String) { messages += text }

        fun hdrEvent(type: String, value: Double = 0.0) = sink!!.onPlayerEvent(type, value)
    }

    private class FakeEnvironment : LinuxHdrSessionCoordinator.Environment {
        var enabled = true
        var wayland: String? = "wayland-0"
        var mode = DesktopHdrMode.Auto
        var libmpvWayland: Boolean? = true
        var helper = true
        var capabilities: LinuxHdrCapabilities? = eligibleCapabilities
        var probes = 0
        val logs = mutableListOf<String>()
        override fun experimentalEnabled() = enabled
        override fun waylandDisplay() = wayland
        override fun hdrMode() = mode
        override fun libmpvWaylandSupported() = libmpvWayland
        override fun probeHelperAvailable() = helper
        override fun probe(): LinuxHdrCapabilities? { probes += 1; return capabilities }
        override fun targetOptions(mode: DesktopHdrMode) = desktopHdrTargetMpvProperties(mode)
        override fun log(message: String) { logs += message }
    }

    private companion object {
        val hdr = LinuxHdrImageDescription(ready = true, transfer = "st2084_pq", primaries = "bt2020", maxNits = 1000.0, referenceNits = 203.0)
        val eligibleCapabilities = LinuxHdrCapabilities(
            schema = 1, probeComplete = true, colorManagerVersion = 1, pqAdvertised = true, bt2020Advertised = true,
            preferred = hdr, outputs = listOf(hdr),
            devices = listOf(LinuxHdrVulkanDevice("Example GPU", hdrMetadata = true, hdr10 = true, hdr10TenBit = true, waylandPresent = true)),
        )
        const val PQ_SOURCE = "linuxShaderMedia/pq/bt.2020/bt.2020-ncl"
    }

    private val host = FakeHost()
    private val environment = FakeEnvironment()
    private val scheduled = mutableListOf<Pair<Long, () -> Unit>>()
    private val coordinator = LinuxHdrSessionCoordinator(
        host = host,
        environment = environment,
        worker = { _, block -> block() },
        ui = { block -> block() },
        schedule = { delay, block -> scheduled += delay to block },
    )

    private fun startToConfirmation() {
        coordinator.onPlayerEvent(PQ_SOURCE)
        host.hdrEvent("fileLoaded")
        host.hdrEvent("linuxHdrVoConfigured", 1.0)
        host.hdrEvent("linuxHdrTarget/pq/bt.2020")
    }

    @Test
    fun eligibleSourceIsHandedOffOnlyAfterTheHdrTargetIsConfirmed() {
        coordinator.onPlayerEvent(PQ_SOURCE)
        assertEquals(1, environment.probes)
        assertEquals(true, host.paused[10L], "embedded player paused before the HDR session starts")
        val request = host.requests.single()
        assertEquals(42_000L, request.positionMs)
        assertEquals("Example GPU", request.vulkanDevice)
        assertEquals(desktopHdrTargetMpvProperties(DesktopHdrMode.Auto).map { "${it.first}=${it.second}" }, request.targetOptions)
        assertEquals(LinuxHdrSessionCoordinator.START_TIMEOUT_MS, scheduled.single().first)

        host.hdrEvent("fileLoaded")
        assertEquals(listOf(20L to 10L), host.prepared)
        host.hdrEvent("linuxHdrVoConfigured", 1.0)
        host.hdrEvent("linuxHdrTarget//")
        assertTrue(host.promoted.isEmpty(), "no promotion before mpv reports a target")
        assertNull(coordinator.activeHdrHandle)
        host.hdrEvent("linuxHdrTarget/pq/bt.2020")

        assertEquals(listOf(20L), host.promoted)
        assertEquals(20L, coordinator.activeHdrHandle)
        assertEquals(false, host.paused[20L], "playback resumes in the HDR window")
        assertEquals(listOf<Long?>(20L), host.routes)
        assertEquals(listOf("displayHdr" to 1.0), host.forwarded)
        assertTrue(host.disposedDetached.isEmpty())
        // The timeout after promotion is inert.
        scheduled.single().second()
        assertEquals(20L, coordinator.activeHdrHandle)
    }

    @Test
    fun pausedEmbeddedPlayerStaysPausedInTheHdrSession() {
        host.points[10L] = LinuxHdrSessionCoordinator.PlaybackPoint(5_000L, paused = true)
        startToConfirmation()
        assertEquals(listOf(20L), host.promoted)
        assertNull(host.paused[20L])
    }

    @Test
    fun noProbeOrPauseWhenAnyCheapGateFails() {
        val gates = listOf<FakeEnvironment.() -> Unit>(
            { enabled = false }, { wayland = null }, { mode = DesktopHdrMode.AlwaysTonemap },
            { libmpvWayland = false }, { libmpvWayland = null }, { helper = false },
        )
        for (gate in gates) {
            val localHost = FakeHost()
            val localEnvironment = FakeEnvironment().apply(gate)
            val local = LinuxHdrSessionCoordinator(localHost, localEnvironment, { _, b -> b() }, { it() }, { _, _ -> })
            local.onPlayerEvent(PQ_SOURCE)
            assertEquals(0, localEnvironment.probes)
            assertTrue(localHost.paused.isEmpty())
            assertTrue(localHost.requests.isEmpty())
        }
    }

    @Test
    fun sdrSourcesTrailersAndUnknownTransfersNeverStartAProbe() {
        coordinator.onPlayerEvent("linuxShaderMedia/bt.1886/bt.709/bt.709")
        coordinator.onPlayerEvent("videoParams")
        assertEquals(0, environment.probes)
        host.generation += 1
        host.source = null
        coordinator.onPlayerEvent(PQ_SOURCE)
        assertEquals(0, environment.probes)
        host.generation += 1
        host.source = "s"
        coordinator.onPlayerEvent("linuxShaderMedia///")
        assertEquals(0, environment.probes)
    }

    @Test
    fun failedOrIneligibleProbeLeavesTheEmbeddedPlayerUntouched() {
        environment.capabilities = null
        coordinator.onPlayerEvent(PQ_SOURCE)
        environment.capabilities = eligibleCapabilities.copy(preferred = hdr.copy(transfer = "srgb"))
        host.generation += 1
        coordinator.onPlayerEvent(PQ_SOURCE)
        assertEquals(2, environment.probes)
        assertTrue(host.paused.isEmpty())
        assertTrue(host.requests.isEmpty())
    }

    @Test
    fun theSameAttachIsEvaluatedOnce() {
        environment.capabilities = null
        coordinator.onPlayerEvent(PQ_SOURCE)
        coordinator.onPlayerEvent(PQ_SOURCE)
        assertEquals(1, environment.probes)
    }

    @Test
    fun sdrTargetDuringStartDisposesTheSessionAndResumesInPlace() {
        coordinator.onPlayerEvent(PQ_SOURCE)
        host.hdrEvent("fileLoaded")
        host.hdrEvent("linuxHdrVoConfigured", 1.0)
        host.hdrEvent("linuxHdrTarget/srgb/bt.709")
        assertEquals(listOf(20L), host.disposedDetached)
        assertEquals(false, host.paused[10L], "embedded player resumes where it was")
        assertTrue(host.promoted.isEmpty())
        assertTrue(host.resumed.isEmpty(), "no re-creation needed before promotion")
        // Declined for this player: a later re-attach of the source is not offered again.
        host.generation += 1
        coordinator.onPlayerEvent(PQ_SOURCE)
        assertEquals(1, environment.probes)
    }

    @Test
    fun everyStartFailureFallsBackExactlyOnce() {
        val failures = listOf(
            "linuxHdrVoFailed" to 0.0, "linuxHdrSessionEnded" to 0.0,
            "mpvStartupError:boom" to 0.0, "mpvPlaybackError:boom" to 0.0,
        )
        for ((event, value) in failures) {
            val localHost = FakeHost()
            val local = LinuxHdrSessionCoordinator(localHost, FakeEnvironment(), { _, b -> b() }, { it() }, { _, _ -> })
            local.onPlayerEvent(PQ_SOURCE)
            localHost.hdrEvent(event, value)
            localHost.hdrEvent(event, value)
            assertEquals(listOf(20L), localHost.disposedDetached, event)
            assertEquals(false, localHost.paused[10L], event)
            localHost.hdrEvent("linuxHdrTarget/pq/bt.2020")
            assertTrue(localHost.promoted.isEmpty(), event)
        }
    }

    @Test
    fun createFailureAndTimeoutResumeTheEmbeddedPlayer() {
        host.createFailure = IllegalStateException("The loaded libmpv has no Wayland Vulkan context.")
        coordinator.onPlayerEvent(PQ_SOURCE)
        assertEquals(false, host.paused[10L])
        assertTrue(host.disposedDetached.isEmpty())

        val timeoutHost = FakeHost()
        val timeoutSchedule = mutableListOf<() -> Unit>()
        val local = LinuxHdrSessionCoordinator(timeoutHost, FakeEnvironment(), { _, b -> b() }, { it() }, { _, b -> timeoutSchedule += b })
        local.onPlayerEvent(PQ_SOURCE)
        timeoutHost.hdrEvent("fileLoaded")
        timeoutSchedule.single()()
        assertEquals(listOf(20L), timeoutHost.disposedDetached)
        assertEquals(false, timeoutHost.paused[10L])
    }

    @Test
    fun reattachOrDisposalDuringStartAbandonsTheUnpublishedSession() {
        coordinator.onPlayerEvent(PQ_SOURCE)
        coordinator.onAttach()
        assertEquals(listOf(20L), host.disposedDetached)
        host.hdrEvent("fileLoaded")
        host.hdrEvent("linuxHdrVoConfigured", 1.0)
        host.hdrEvent("linuxHdrTarget/pq/bt.2020")
        assertTrue(host.promoted.isEmpty())

        val localHost = FakeHost()
        val local = LinuxHdrSessionCoordinator(localHost, FakeEnvironment(), { _, b -> b() }, { it() }, { _, _ -> })
        local.onPlayerEvent(PQ_SOURCE)
        local.dispose()
        assertEquals(listOf(20L), localHost.disposedDetached)
        local.onPlayerEvent(PQ_SOURCE)
        assertEquals(1, localHost.requests.size)
    }

    @Test
    fun promotionRefusedByAChangedPlayerDisposesTheSession() {
        host.promoteAllowed = false
        startToConfirmation()
        assertEquals(listOf(20L), host.disposedDetached)
        assertNull(coordinator.activeHdrHandle)
    }

    @Test
    fun activeSessionForwardsPlayerEventsAndKeysExactlyOnce() {
        startToConfirmation()
        host.forwarded.clear()
        host.hdrEvent("playbackRestart")
        host.hdrEvent("linuxHdrKey", 32.0)
        host.hdrEvent("linuxHdrVoFailed")
        assertEquals(listOf("playbackRestart" to 0.0), host.forwarded)
        assertEquals(listOf(32), host.keys)
    }

    @Test
    fun lostOutputOrSdrTargetReturnsToTheEmbeddedPlayerAtTheSamePosition() {
        startToConfirmation()
        host.points[20L] = LinuxHdrSessionCoordinator.PlaybackPoint(77_000L, paused = false)
        host.hdrEvent("linuxHdrVoConfigured", 0.0)
        assertEquals(listOf(77_000L to true), host.resumed)
        assertEquals(listOf<Long?>(20L, null), host.routes)
        assertNull(coordinator.activeHdrHandle)
        host.hdrEvent("linuxHdrSessionEnded")
        assertEquals(1, host.resumed.size, "late events from the retired session are ignored")

        val localHost = FakeHost()
        val local = LinuxHdrSessionCoordinator(localHost, FakeEnvironment(), { _, b -> b() }, { it() }, { _, _ -> })
        local.onPlayerEvent(PQ_SOURCE)
        localHost.hdrEvent("fileLoaded")
        localHost.hdrEvent("linuxHdrVoConfigured", 1.0)
        localHost.hdrEvent("linuxHdrTarget/pq/bt.2020")
        localHost.points[20L] = LinuxHdrSessionCoordinator.PlaybackPoint(90_000L, paused = true)
        localHost.hdrEvent("linuxHdrTarget/srgb/bt.709")
        assertEquals(listOf(90_000L to false), localHost.resumed)
    }

    @Test
    fun deadSessionFallsBackToTheHandoffPosition() {
        startToConfirmation()
        host.points.remove(20L)
        host.hdrEvent("linuxHdrSessionEnded")
        assertEquals(listOf(42_000L to true), host.resumed)
    }

    @Test
    fun userLeaveAndTonemapModeReturnToTheEmbeddedPlayer() {
        startToConfirmation()
        host.points[20L] = LinuxHdrSessionCoordinator.PlaybackPoint(50_000L, paused = false)
        coordinator.onHdrModeChanged(DesktopHdrMode.AlwaysPassthrough)
        assertEquals(listOf(desktopHdrTargetMpvProperties(DesktopHdrMode.AlwaysPassthrough)), host.targetOptions)
        assertTrue(coordinator.showMessage("HDR Mode: Always Passthrough"))
        coordinator.onHdrModeChanged(DesktopHdrMode.AlwaysTonemap)
        assertEquals(listOf(50_000L to true), host.resumed)
        assertFalse(coordinator.showMessage("ignored"))

        val localHost = FakeHost()
        val local = LinuxHdrSessionCoordinator(localHost, FakeEnvironment(), { _, b -> b() }, { it() }, { _, _ -> })
        local.onPlayerEvent(PQ_SOURCE)
        localHost.hdrEvent("fileLoaded")
        localHost.hdrEvent("linuxHdrVoConfigured", 1.0)
        localHost.hdrEvent("linuxHdrTarget/pq/bt.2020")
        local.leave("user")
        local.leave("user")
        assertEquals(1, localHost.resumed.size)
    }

    @Test
    fun escapeDuringStartGoesBackFromTheEmbeddedPlayer() {
        coordinator.onPlayerEvent(PQ_SOURCE)
        host.hdrEvent("linuxHdrKey", java.awt.event.KeyEvent.VK_ESCAPE.toDouble())
        assertEquals(listOf(20L), host.disposedDetached)
        assertEquals(listOf("back" to 0.0), host.forwarded)
        assertTrue(host.keys.isEmpty())
    }

    @Test
    fun disposingAnActiveSessionClearsTheGamepadRouteWithoutResuming() {
        startToConfirmation()
        coordinator.dispose()
        assertEquals(listOf<Long?>(20L, null), host.routes)
        assertTrue(host.resumed.isEmpty())
        assertTrue(host.disposedDetached.isEmpty(), "the published handle is disposed by the controller")
    }
}
