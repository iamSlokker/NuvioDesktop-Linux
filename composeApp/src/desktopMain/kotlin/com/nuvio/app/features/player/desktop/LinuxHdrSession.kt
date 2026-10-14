package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.DesktopHdrMode
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.player.desktopHdrTargetMpvProperties
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

private val hdrLog = Logger.withTag("LinuxHdrSession")

/** JNI surface of the experimental HDR session (player_bridge.cpp, Linux only). */
internal object LinuxHdrSessionNative {
    init {
        ensureNativePlayerBridgeLoaded()
    }

    /** Headless: creates an uninitialised mpv handle only. */
    external fun libmpvSupportsWayland(): Boolean

    /**
     * Creates a paused native-Wayland session in the shared player handle table, so all
     * per-handle [NativePlayerBridge] methods (including dispose) apply to it.
     */
    external fun create(
        sourceUrl: String,
        sourceAudioUrl: String?,
        headerLines: Array<String>,
        initialPositionMs: Long,
        extraMpvOptions: Array<String>,
        keyBindings: Array<String>,
        vulkanDevice: String,
        oscScriptPath: String,
        appId: String,
        eventSink: NativePlayerEventSink,
    ): Long

    external fun scriptMessage(handle: Long, arguments: Array<String>)
    external fun showText(handle: Long, text: String, durationMs: Int)
    external fun isFocused(handle: Long): Boolean
    external fun transferPlaybackState(fromHandle: Long, toHandle: Long)
}

/** Machine-local, Linux-only switch for the experimental session. Default off. */
internal object LinuxHdrSessionSettings {
    private const val KEY = "linux_experimental_wayland_hdr"
    private val store by lazy { DesktopStorage.store("nuvio_linux_player") }
    private val state = MutableStateFlow(false)
    @Volatile
    private var loaded = false

    val enabledState: StateFlow<Boolean>
        get() {
            ensureLoaded()
            return state
        }

    fun isEnabled(): Boolean {
        ensureLoaded()
        return state.value
    }

    fun setEnabled(enabled: Boolean) {
        ensureLoaded()
        state.value = enabled
        runCatching { store.putBoolean(KEY, enabled) }
    }

    private fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            state.value = runCatching { store.getBoolean(KEY) }.getOrNull() ?: false
            loaded = true
        }
    }
}

/** Runs the separate `nuvio-hdr-probe` helper under a hard timeout. */
internal object LinuxHdrCapabilityProbe {
    const val HELPER_NAME = "nuvio-hdr-probe"
    private const val TIMEOUT_MS = 8_000L
    private const val MAX_OUTPUT_BYTES = 64 * 1024

    fun helper(): File? = NativePlayerBridge.runtimeDllDir()
        ?.resolve(HELPER_NAME)
        ?.takeIf { it.isFile && it.canExecute() }

    fun run(): LinuxHdrCapabilities? {
        val executable = helper() ?: return null
        val process = runCatching {
            ProcessBuilder(executable.absolutePath, "--probe-json")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrElse { error ->
            hdrLog.w(error) { "HDR capability probe could not start" }
            return null
        }
        var output = ByteArray(0)
        val reader = Thread({
            runCatching {
                process.inputStream.use { stream -> output = stream.readNBytes(MAX_OUTPUT_BYTES) }
            }
        }, "Nuvio-HDR-Probe-Output").apply { isDaemon = true; start() }
        // Vulkan driver queries cannot be cancelled in-process; the helper can be killed.
        if (!process.waitFor(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            hdrLog.w { "HDR capability probe timed out" }
            return null
        }
        reader.join(1_000L)
        if (process.exitValue() != 0) return null
        val capabilities = LinuxHdrCapabilities.parse(output.toString(Charsets.UTF_8))
        hdrLog.i {
            "HDR capability probe: complete=${capabilities?.probeComplete} reason=${capabilities?.reason}" +
                " preferred=${capabilities?.preferred?.transfer}/${capabilities?.preferred?.primaries}" +
                " devices=${capabilities?.devices?.map { "${it.name}:hdr10=${it.hdr10TenBit}" }}"
        }
        return capabilities
    }
}

/** Exports the bundled display-only OSC script into the user's private data directory. */
internal object LinuxHdrOsc {
    private const val RESOURCE = "/player-scripts/linux-hdr-osc.lua"

    val path: String by lazy { export() ?: "" }

    private fun export(): String? = runCatching {
        val bytes = LinuxHdrOsc::class.java.getResourceAsStream(RESOURCE)?.use { it.readBytes() }
            ?: return null
        // Never a shared temporary directory: mpv executes this file.
        val directory = DesktopStorage.rootDir.resolve("runtime").resolve("linux-hdr")
        Files.createDirectories(directory)
        val target = directory.resolve("linux-hdr-osc.lua")
        if (!Files.isRegularFile(target) || !Files.readAllBytes(target).contentEquals(bytes)) {
            val staging = Files.createTempFile(directory, "linux-hdr-osc", ".part")
            try {
                Files.write(staging, bytes)
                Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(staging)
            }
        }
        target.toAbsolutePath().toString()
    }.onFailure { error -> hdrLog.w(error) { "HDR OSC script unavailable" } }.getOrNull()
}

/** Production environment for [LinuxHdrSessionCoordinator]. */
internal object LinuxHdrSessionEnvironment : LinuxHdrSessionCoordinator.Environment {
    private val waylandSupport: Boolean? by lazy {
        runCatching { LinuxHdrSessionNative.libmpvSupportsWayland() }.getOrNull()
    }

    override fun experimentalEnabled(): Boolean = LinuxHdrSessionSettings.isEnabled()
    override fun waylandDisplay(): String? = System.getenv("WAYLAND_DISPLAY")
    override fun hdrMode(): DesktopHdrMode = PlayerSettingsRepository.uiState.value.desktopHdrMode
    override fun libmpvWaylandSupported(): Boolean? = waylandSupport
    override fun probeHelperAvailable(): Boolean = LinuxHdrCapabilityProbe.helper() != null
    override fun probe(): LinuxHdrCapabilities? = LinuxHdrCapabilityProbe.run()
    override fun targetOptions(mode: DesktopHdrMode): List<Pair<String, String>> = desktopHdrTargetMpvProperties(mode)
    override fun log(message: String) = hdrLog.i { message }
}

internal const val LINUX_HDR_APP_ID = "com.nuvio.media.desktop"
