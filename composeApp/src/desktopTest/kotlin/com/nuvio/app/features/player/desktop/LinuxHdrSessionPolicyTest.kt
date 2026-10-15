package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopHdrMode
import com.nuvio.app.features.player.desktopHdrTargetMpvProperties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LinuxHdrSessionPolicyTest {
    private val eligibleInputs = LinuxHdrSessionInputs(
        experimentalEnabled = true,
        waylandDisplay = "wayland-0",
        hdrMode = DesktopHdrMode.Auto,
        sourceTransfer = "pq",
        libmpvWaylandSupported = true,
        probeHelperAvailable = true,
    )

    private val hdrDescription = LinuxHdrImageDescription(
        ready = true, transfer = "st2084_pq", primaries = "bt2020",
        minNits = 0.005, maxNits = 1000.0, referenceNits = 203.0,
    )
    private val hdrDevice = LinuxHdrVulkanDevice(
        name = "Example GPU", hdrMetadata = true, hdr10 = true, hdr10TenBit = true, waylandPresent = true,
    )
    private val eligibleCapabilities = LinuxHdrCapabilities(
        schema = 1,
        probeComplete = true,
        colorManagerVersion = 1,
        pqAdvertised = true,
        bt2020Advertised = true,
        preferred = hdrDescription,
        outputs = listOf(hdrDescription),
        devices = listOf(hdrDevice),
    )

    private fun sdr(inputs: LinuxHdrSessionInputs = eligibleInputs, capabilities: LinuxHdrCapabilities? = eligibleCapabilities) =
        assertIs<LinuxHdrSessionDecision.Sdr>(LinuxHdrSessionPolicy.decide(inputs, capabilities))

    @Test
    fun allPrerequisitesSelectTheHdrSessionWithTheOnlyEligibleDevice() {
        assertEquals(LinuxHdrSessionDecision.ProbeRequired, LinuxHdrSessionPolicy.preProbe(eligibleInputs))
        val decision = assertIs<LinuxHdrSessionDecision.Hdr>(LinuxHdrSessionPolicy.decide(eligibleInputs, eligibleCapabilities))
        assertEquals("Example GPU", decision.vulkanDevice)
        assertIs<LinuxHdrSessionDecision.Hdr>(LinuxHdrSessionPolicy.decide(eligibleInputs.copy(sourceTransfer = "hlg"), eligibleCapabilities))
        assertIs<LinuxHdrSessionDecision.Hdr>(
            LinuxHdrSessionPolicy.decide(eligibleInputs.copy(hdrMode = DesktopHdrMode.AlwaysPassthrough), eligibleCapabilities),
        )
    }

    @Test
    fun experimentalSettingIsRequiredAndDefaultsOff() {
        sdr(eligibleInputs.copy(experimentalEnabled = false))
    }

    @Test
    fun missingWaylandDisplayFailsClosedBeforeAnyProbe() {
        assertIs<LinuxHdrSessionDecision.Sdr>(LinuxHdrSessionPolicy.preProbe(eligibleInputs.copy(waylandDisplay = null)))
        assertIs<LinuxHdrSessionDecision.Sdr>(LinuxHdrSessionPolicy.preProbe(eligibleInputs.copy(waylandDisplay = " ")))
    }

    @Test
    fun alwaysTonemapKeepsTheEmbeddedPlayer() {
        sdr(eligibleInputs.copy(hdrMode = DesktopHdrMode.AlwaysTonemap))
    }

    @Test
    fun onlyPqOrHlgSourcesQualify() {
        for (transfer in listOf(null, "", "bt.1886", "srgb", "gamma2.2", "linear", "auto", "bt.2020")) {
            sdr(eligibleInputs.copy(sourceTransfer = transfer))
        }
    }

    @Test
    fun libmpvWithoutWaylandOrUnknownSupportFailsClosed() {
        sdr(eligibleInputs.copy(libmpvWaylandSupported = false))
        sdr(eligibleInputs.copy(libmpvWaylandSupported = null))
    }

    @Test
    fun missingOrFailedProbeFailsClosed() {
        sdr(eligibleInputs.copy(probeHelperAvailable = false))
        sdr(capabilities = null)
        sdr(capabilities = eligibleCapabilities.copy(probeComplete = false))
    }

    @Test
    fun compositorMustAdvertisePqAndBt2020() {
        sdr(capabilities = eligibleCapabilities.copy(colorManagerVersion = 0))
        sdr(capabilities = eligibleCapabilities.copy(pqAdvertised = false))
        sdr(capabilities = eligibleCapabilities.copy(bt2020Advertised = false))
    }

    @Test
    fun preferredSurfaceTargetMustBePqBt2020WithHeadroom() {
        // The prototype compared raw wire numbers and mistook sRGB (9) for PQ (11);
        // names from the generated protocol constants avoid that class of error.
        sdr(capabilities = eligibleCapabilities.copy(preferred = hdrDescription.copy(transfer = "srgb")))
        sdr(capabilities = eligibleCapabilities.copy(preferred = hdrDescription.copy(transfer = "other:9")))
        sdr(capabilities = eligibleCapabilities.copy(preferred = hdrDescription.copy(primaries = "srgb")))
        sdr(capabilities = eligibleCapabilities.copy(preferred = hdrDescription.copy(ready = false)))
        sdr(capabilities = eligibleCapabilities.copy(preferred = hdrDescription.copy(failed = true)))
        sdr(capabilities = eligibleCapabilities.copy(preferred = hdrDescription.copy(maxNits = 203.0)))
        sdr(capabilities = eligibleCapabilities.copy(preferred = hdrDescription.copy(referenceNits = -1.0)))
        sdr(capabilities = eligibleCapabilities.copy(preferred = hdrDescription.copy(maxNits = Double.NaN)))
    }

    @Test
    fun anSdrPreferenceIsNotOverriddenByAnotherHdrOutput() {
        val sdrDescription = LinuxHdrImageDescription(ready = true, transfer = "srgb", primaries = "srgb", maxNits = 80.0, referenceNits = 80.0)
        sdr(capabilities = eligibleCapabilities.copy(preferred = sdrDescription, outputs = listOf(sdrDescription, hdrDescription)))
        sdr(capabilities = eligibleCapabilities.copy(outputs = emptyList()))
        sdr(capabilities = eligibleCapabilities.copy(outputs = listOf(sdrDescription)))
    }

    @Test
    fun vulkanDeviceMustBeUnambiguousAndFullyCapable() {
        sdr(capabilities = eligibleCapabilities.copy(devices = emptyList()))
        sdr(capabilities = eligibleCapabilities.copy(devices = listOf(hdrDevice.copy(hdr10TenBit = false))))
        sdr(capabilities = eligibleCapabilities.copy(devices = listOf(hdrDevice.copy(hdrMetadata = false))))
        sdr(capabilities = eligibleCapabilities.copy(devices = listOf(hdrDevice.copy(waylandPresent = false))))
        sdr(capabilities = eligibleCapabilities.copy(devices = listOf(hdrDevice.copy(hdr10 = false))))
        sdr(capabilities = eligibleCapabilities.copy(devices = listOf(hdrDevice, hdrDevice.copy(name = "Other GPU"))))
        sdr(capabilities = eligibleCapabilities.copy(devices = listOf(hdrDevice.copy(name = ""))))
        sdr(capabilities = eligibleCapabilities.copy(devices = listOf(hdrDevice, hdrDevice.copy(hdr10 = false))))
        // One capable and one incapable device with distinct names is unambiguous.
        val chosen = assertIs<LinuxHdrSessionDecision.Hdr>(LinuxHdrSessionPolicy.decide(eligibleInputs,
            eligibleCapabilities.copy(devices = listOf(hdrDevice.copy(name = "iGPU", hdr10 = false), hdrDevice))))
        assertEquals("Example GPU", chosen.vulkanDevice)
    }

    @Test
    fun probeJsonParsesTheHelperSchemaAndRejectsAnythingElse() {
        val json = """{"schema":1,"probe_complete":true,"reason":"ok","color_manager_version":1,
            "pq_advertised":true,"bt2020_advertised":true,
            "preferred":{"ready":true,"failed":false,"transfer":"st2084_pq","primaries":"bt2020",
              "min_nits":0.005,"max_nits":1000,"reference_nits":203,"target_max_nits":1000},
            "outputs":[{"ready":true,"failed":false,"transfer":"st2084_pq","primaries":"bt2020",
              "min_nits":0.005,"max_nits":1000,"reference_nits":203,"target_max_nits":1000}],
            "devices":[{"name":"Example GPU","hdr_metadata":true,"hdr10":true,"hdr10_10bit":true,"wayland_present":true}],
            "future_field":42}"""
        val parsed = assertNotNull(LinuxHdrCapabilities.parse(json))
        assertIs<LinuxHdrSessionDecision.Hdr>(LinuxHdrSessionPolicy.decide(eligibleInputs, parsed))
        assertNull(LinuxHdrCapabilities.parse(""))
        assertNull(LinuxHdrCapabilities.parse("not json"))
        assertNull(LinuxHdrCapabilities.parse(json.replace("\"schema\":1", "\"schema\":2")))
        assertNull(LinuxHdrCapabilities.parse("{\"probe_complete\":true}"))
        // The real headless helper output (WAYLAND_DISPLAY unset) is parseable and SDR.
        val headless = assertNotNull(LinuxHdrCapabilities.parse("""{"schema":1,"probe_complete":false,
            "reason":"WAYLAND_DISPLAY is unset; no display connection attempted","color_manager_version":0,
            "pq_advertised":false,"bt2020_advertised":false,"preferred":{"ready":false,"failed":false,
            "transfer":"","primaries":"","min_nits":-1,"max_nits":-1,"reference_nits":-1,"target_max_nits":-1},
            "outputs":[],"devices":[]}"""))
        sdr(capabilities = headless)
    }

    @Test
    fun sourceTransferComesFromTheExistingShaderMediaEvent() {
        assertEquals("pq", linuxShaderMediaTransfer("linuxShaderMedia/pq/bt.2020/bt.2020-ncl"))
        assertEquals("hlg", linuxShaderMediaTransfer("linuxShaderMedia/hlg/bt.2020/bt.2020-ncl"))
        assertNull(linuxShaderMediaTransfer("linuxShaderMedia///"))
        assertNull(linuxShaderMediaTransfer("videoParams"))
        assertTrue(isLinuxHdrSourceTransfer("pq"))
        assertTrue(isLinuxHdrSourceTransfer("hlg"))
    }

    @Test
    fun negotiatedTargetMustBeHdrBeforePromotion() {
        assertEquals(true, linuxHdrTargetVerdict("linuxHdrTarget/pq/bt.2020"))
        assertEquals(true, linuxHdrTargetVerdict("linuxHdrTarget/hlg/bt.2020"))
        assertNull(linuxHdrTargetVerdict("linuxHdrTarget//"))
        assertNull(linuxHdrTargetVerdict("linuxHdrTarget/auto/auto"))
        assertEquals(false, linuxHdrTargetVerdict("linuxHdrTarget/srgb/bt.709"))
        assertEquals(false, linuxHdrTargetVerdict("linuxHdrTarget/pq/bt.709"))
        assertEquals(false, linuxHdrTargetVerdict("linuxHdrTarget/bt.1886/bt.2020"))
        assertEquals(false, linuxHdrTargetVerdict("linuxHdrTarget/pq/bt.2020/extra"))
    }

    @Test
    fun hdrSessionReusesTheSharedDesktopHdrModeMapping() {
        // Values are the unchanged Windows/macOS applyDesktopVideoProfile contract.
        assertEquals(
            listOf("target-colorspace-hint" to "auto", "target-colorspace-hint-mode" to "target",
                "target-prim" to "auto", "target-trc" to "auto", "target-peak" to "auto", "target-contrast" to "auto"),
            desktopHdrTargetMpvProperties(DesktopHdrMode.Auto),
        )
        assertEquals(
            listOf("target-colorspace-hint" to "yes", "target-colorspace-hint-mode" to "target",
                "target-prim" to "bt.709", "target-trc" to "bt.1886", "target-peak" to "203", "target-contrast" to "1000"),
            desktopHdrTargetMpvProperties(DesktopHdrMode.AlwaysTonemap),
        )
        assertEquals(
            listOf("target-colorspace-hint" to "yes", "target-colorspace-hint-mode" to "source",
                "target-prim" to "auto", "target-trc" to "auto", "target-peak" to "auto", "target-contrast" to "auto"),
            desktopHdrTargetMpvProperties(DesktopHdrMode.AlwaysPassthrough),
        )
    }
}
