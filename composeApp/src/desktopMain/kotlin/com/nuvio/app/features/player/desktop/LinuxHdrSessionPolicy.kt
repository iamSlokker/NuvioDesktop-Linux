package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.DesktopHdrMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * Selection policy for the experimental Linux native-Wayland HDR session.
 *
 * Fail-closed by construction: every input that is missing, unknown or ambiguous keeps
 * the accepted embedded X11 player (SDR output path). Pure and display-free, so the
 * whole decision table is unit-tested without a compositor, GPU or libmpv.
 */

/** mpv `video-params/gamma` names of an HDR transfer. BT.2020 primaries alone are not HDR. */
private val linuxHdrSourceTransfers = setOf("pq", "hlg", "st2084", "arib-std-b67")

internal fun isLinuxHdrSourceTransfer(gamma: String?): Boolean = gamma in linuxHdrSourceTransfers

/** The transfer field of a bridge `linuxShaderMedia/<gamma>/<primaries>/<matrix>` event. */
internal fun linuxShaderMediaTransfer(event: String): String? {
    if (!event.startsWith(LinuxShaderMedia.EVENT_PREFIX)) return null
    return event.removePrefix(LinuxShaderMedia.EVENT_PREFIX).split('/').firstOrNull()?.takeIf(String::isNotBlank)
}

/**
 * Whether a negotiated mpv target (`video-target-params`) is an HDR surface: null while
 * mpv has not rendered to a target yet, false for every SDR or unrecognised target.
 */
internal fun linuxHdrTargetVerdict(event: String): Boolean? {
    if (!event.startsWith(LINUX_HDR_TARGET_EVENT_PREFIX)) return false
    val fields = event.removePrefix(LINUX_HDR_TARGET_EVENT_PREFIX).split('/')
    val gamma = fields.getOrNull(0).orEmpty()
    val primaries = fields.getOrNull(1).orEmpty()
    if (fields.size != 2) return false
    if (gamma.isBlank() || gamma == "auto") return null
    return gamma in setOf("pq", "hlg") && primaries == "bt.2020"
}

internal const val LINUX_HDR_TARGET_EVENT_PREFIX = "linuxHdrTarget/"

internal data class LinuxHdrSessionInputs(
    /** The Linux-only "Experimental HDR (Wayland)" setting; default off. */
    val experimentalEnabled: Boolean,
    val waylandDisplay: String?,
    val hdrMode: DesktopHdrMode,
    /** mpv transfer of the decoded source, from the embedded player's video-params. */
    val sourceTransfer: String?,
    /** Whether the loaded libmpv has the Wayland Vulkan context; null when it cannot be read. */
    val libmpvWaylandSupported: Boolean?,
    val probeHelperAvailable: Boolean,
)

internal sealed interface LinuxHdrSessionDecision {
    data class Sdr(val reason: String) : LinuxHdrSessionDecision
    data object ProbeRequired : LinuxHdrSessionDecision
    data class Hdr(val vulkanDevice: String, val reason: String) : LinuxHdrSessionDecision
}

@Serializable
internal data class LinuxHdrImageDescription(
    val ready: Boolean = false,
    val failed: Boolean = false,
    val transfer: String = "",
    val primaries: String = "",
    @SerialName("min_nits") val minNits: Double = -1.0,
    @SerialName("max_nits") val maxNits: Double = -1.0,
    @SerialName("reference_nits") val referenceNits: Double = -1.0,
    @SerialName("target_max_nits") val targetMaxNits: Double = -1.0,
)

@Serializable
internal data class LinuxHdrVulkanDevice(
    val name: String = "",
    @SerialName("hdr_metadata") val hdrMetadata: Boolean = false,
    val hdr10: Boolean = false,
    @SerialName("hdr10_10bit") val hdr10TenBit: Boolean = false,
    @SerialName("wayland_present") val waylandPresent: Boolean = false,
)

/** One `nuvio-hdr-probe --probe-json` snapshot. Colour values are wp_color_manager_v1 enum names. */
@Serializable
internal data class LinuxHdrCapabilities(
    val schema: Int = 0,
    @SerialName("probe_complete") val probeComplete: Boolean = false,
    val reason: String = "",
    @SerialName("color_manager_version") val colorManagerVersion: Int = 0,
    @SerialName("pq_advertised") val pqAdvertised: Boolean = false,
    @SerialName("bt2020_advertised") val bt2020Advertised: Boolean = false,
    val preferred: LinuxHdrImageDescription = LinuxHdrImageDescription(),
    val outputs: List<LinuxHdrImageDescription> = emptyList(),
    val devices: List<LinuxHdrVulkanDevice> = emptyList(),
) {
    companion object {
        const val SCHEMA = 1
        private val json = Json { ignoreUnknownKeys = true }

        /** Null for anything that is not one complete schema-1 object. */
        fun parse(text: String): LinuxHdrCapabilities? = runCatching {
            json.decodeFromString(serializer(), text.trim())
        }.getOrNull()?.takeIf { it.schema == SCHEMA }
    }
}

internal object LinuxHdrSessionPolicy {
    /** Cheap gates evaluated before any display connection; never returns [LinuxHdrSessionDecision.Hdr]. */
    fun preProbe(inputs: LinuxHdrSessionInputs): LinuxHdrSessionDecision = when {
        !inputs.experimentalEnabled -> LinuxHdrSessionDecision.Sdr("Experimental HDR (Wayland) is off")
        inputs.waylandDisplay.isNullOrBlank() -> LinuxHdrSessionDecision.Sdr("WAYLAND_DISPLAY is not set")
        inputs.hdrMode == DesktopHdrMode.AlwaysTonemap -> LinuxHdrSessionDecision.Sdr("HDR mode is Always Tonemap")
        inputs.sourceTransfer == null -> LinuxHdrSessionDecision.Sdr("Source transfer is unknown")
        !isLinuxHdrSourceTransfer(inputs.sourceTransfer) ->
            LinuxHdrSessionDecision.Sdr("Source transfer ${inputs.sourceTransfer} is not PQ/HLG")
        inputs.libmpvWaylandSupported != true ->
            LinuxHdrSessionDecision.Sdr("The loaded libmpv has no Wayland Vulkan context")
        !inputs.probeHelperAvailable -> LinuxHdrSessionDecision.Sdr("HDR capability probe is unavailable")
        else -> LinuxHdrSessionDecision.ProbeRequired
    }

    /** Final decision. [capabilities] null means the probe could not run, time out or parse. */
    fun decide(inputs: LinuxHdrSessionInputs, capabilities: LinuxHdrCapabilities?): LinuxHdrSessionDecision {
        val gate = preProbe(inputs)
        if (gate !is LinuxHdrSessionDecision.ProbeRequired) return gate
        if (capabilities == null) return LinuxHdrSessionDecision.Sdr("HDR capability probe did not complete")
        return evaluate(capabilities)
    }

    fun evaluate(capabilities: LinuxHdrCapabilities): LinuxHdrSessionDecision {
        fun sdr(reason: String) = LinuxHdrSessionDecision.Sdr(reason)
        if (!capabilities.probeComplete) return sdr("Capability probe incomplete: ${capabilities.reason}")
        if (capabilities.colorManagerVersion < 1 || !capabilities.pqAdvertised || !capabilities.bt2020Advertised)
            return sdr("Compositor does not advertise PQ/BT.2020")
        val target = capabilities.preferred
        if (!target.describesHdr()) return sdr("Preferred surface target is not PQ/BT.2020")
        if (!target.referenceNits.isFinite() || !target.maxNits.isFinite() ||
            target.referenceNits <= 0.0 || target.maxNits <= target.referenceNits
        ) return sdr("Preferred HDR luminance range is unknown or has no headroom")
        if (capabilities.outputs.none { it.describesHdr() }) return sdr("No output is described as PQ/BT.2020")
        val eligible = capabilities.devices.filter {
            it.hdr10 && it.hdr10TenBit && it.waylandPresent && it.hdrMetadata
        }
        if (eligible.isEmpty()) return sdr("No Vulkan device offers a presentable 10-bit HDR10 surface")
        // Enumeration order must not pick the renderer on multi-GPU systems.
        if (eligible.size > 1) return sdr("Multiple eligible Vulkan devices; selection would be ambiguous")
        val device = eligible.single()
        if (device.name.isBlank() || capabilities.devices.count { it.name == device.name } != 1)
            return sdr("Eligible Vulkan device has no unambiguous name")
        return LinuxHdrSessionDecision.Hdr(
            device.name,
            "Prerequisites observed; the mapped HDR surface must still confirm a PQ target",
        )
    }

    private fun LinuxHdrImageDescription.describesHdr(): Boolean =
        ready && !failed && transfer == "st2084_pq" && primaries == "bt2020"
}
