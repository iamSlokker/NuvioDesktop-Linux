package com.nuvio.app.core.build

import com.nuvio.app.features.player.desktop.LinuxAwtViewResolver
import com.nuvio.app.features.player.desktop.LinuxInhibitionNative
import com.nuvio.app.features.player.desktop.LinuxMprisSession
import com.nuvio.app.features.player.desktop.LinuxMprisState
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import com.nuvio.app.features.player.desktop.NativePlayerEventSink
import com.nuvio.app.features.player.desktop.NativePlayerHost
import com.nuvio.app.features.player.desktop.nativeMprisBackend
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

/**
 * The Linux half of [NativeImageSupport.startSelfTestIfRequested] (`NUVIO_NATIVE_SELFTEST`, run by
 * `linuxNativeSmoke` inside a throwaway container with a private Xvfb and D-Bus). Each probe drives
 * real Linux JNI through the image: the calls Java makes into `libplayer_bridge.so`, and the
 * callbacks its mpv / GLib threads make back into Java after `AttachCurrentThreadAsDaemon`, which
 * fail natively when their metadata is missing (`composeApp/native-image-linux/README.md`).
 * Everything stays in the process, temporary files and a private D-Bus daemon.
 */
internal object LinuxNativeSelfTest {

    fun run(probe: (String, () -> String) -> Unit) {
        // native-image emits libawt & co. beside the exe and the layout adds libjawt.so under
        // runtime/lib; a second copy of either mapped from another path would split AWT's state.
        probe("linux jdk libraries") {
            val maps = File("/proc/self/maps").readLines().mapNotNull { line ->
                line.substringAfter('/', "").takeIf(String::isNotEmpty)?.let { "/$it" }
            }.toSet()
            val libraries = listOf("libawt.so", "libawt_xawt.so", "libjawt.so", "libfontmanager.so")
            val copies = libraries.associateWith { name -> maps.filter { it.endsWith("/$name") } }
            copies.forEach { (name, paths) -> check(paths.size <= 1) { "$name mapped from $paths" } }
            check(copies.getValue("libawt_xawt.so").size == 1) { "XToolkit library is not loaded" }
            copies.entries.joinToString(" ") { (name, paths) -> "$name=${paths.size}" }
        }
        probe("linux player bridge") {
            NativePlayerBridge.ensureNativeLibraryLoaded()
            "loaded from ${NativePlayerBridge.runtimeDllDir()}"
        }
        probe("linux x11 view") {
            withVideoFrame { _, host -> onEdt { "drawable=0x" + LinuxAwtViewResolver.resolveNativeViewPointer(host).toString(16) } }
        }
        probe("linux playback") { playback() }
        probe("linux mpris") { mpris() }
        // No inhibition backend is expected on the smoke's private bus (acquire returns 0); a
        // desktop session takes and releases a real lease. Either way the JNI round trip runs.
        probe("linux inhibitor") {
            val handle = LinuxInhibitionNative.acquire(Runnable { })
            LinuxInhibitionNative.release(handle)
            if (handle == 0L) "no backend (expected on a private bus)" else "acquired and released"
        }
        // kotlinx.serialization reaches an object's, a sealed base's or an enum's serializer by
        // reflection. Navigation builds every route's serializer right after sign-in, a screen the
        // signed-out smoke never shows; a native build once crashed there on TabsRoute (2026-10-18).
        probe("linux app serializers") {
            listOf(
                com.nuvio.app.TabsRoute::class.java,
                com.nuvio.app.DetailRoute::class.java,
                com.nuvio.app.features.catalog.CatalogTargetKind::class.java,
            ).joinToString(" ") { type -> kotlinx.serialization.serializer(type).descriptor.serialName.substringAfterLast('.') }
        }
    }

    /**
     * A few seconds of a generated local video through the bridge: JAWT surface, libmpv, the
     * WebKitGTK HUD with its exported page, and mpv-thread events into [NativePlayerEventSink].
     * `NUVIO_NATIVE_SELFTEST_MPV_OPTIONS` (`|`-separated) adds options for the environment, such as
     * naming the smoke container's software Vulkan device, which libplacebo never picks by itself.
     */
    private fun playback(): String {
        val source = Files.createTempFile("nuvio-selftest-", ".y4m")
        try {
            writeVideo(source)
            return withVideoFrame { _, host ->
                val events = ConcurrentHashMap.newKeySet<String>()
                val extra = System.getenv("NUVIO_NATIVE_SELFTEST_MPV_OPTIONS").orEmpty()
                    .split('|').map(String::trim).filter(String::isNotEmpty)
                val drawable = onEdt { LinuxAwtViewResolver.resolveNativeViewPointer(host) }
                val handle = NativePlayerBridge.create(
                    hostViewPtr = drawable, sourceUrl = source.toString(), sourceAudioUrl = null,
                    headerLines = emptyArray(), playWhenReady = true, initialPositionMs = 0L,
                    initialProgressFraction = 0.0, controlsPageUrl = NativePlayerBridge.controlsPageUrl,
                    nvidiaRtxSuperResolutionEnabled = false, nvidiaRtxHdrEnabled = false,
                    isAnimeContent = false, animeSvpFilter = null,
                    extraMpvOptions = (listOf("ao=null") + extra).toTypedArray(),
                    eventSink = NativePlayerEventSink { type, _ -> events += type.take(40) },
                )
                check(handle != 0L) { "create returned no player" }
                try {
                    var position = 0L
                    awaitTrue(20, { "position=$position events=${events.sorted()}" }) {
                        position = NativePlayerBridge.positionMs(handle)
                        position >= 1_000L && events.isNotEmpty()
                    }
                    NativePlayerBridge.setPaused(handle, true)
                    "position=${position}ms events=${events.size} (${events.sorted().take(6).joinToString()})"
                } finally {
                    NativePlayerBridge.dispose(handle)
                }
            }
        } finally {
            Files.deleteIfExists(source)
        }
    }

    /** Production MPRIS service on a private bus; a D-Bus call comes back on a GLib thread. */
    private fun mpris(): String {
        val daemon = ProcessBuilder("dbus-daemon", "--session", "--nofork", "--print-address=1").start()
        try {
            val address = checkNotNull(daemon.inputStream.bufferedReader().readLine()) { "dbus-daemon gave no address" }
            val name = "org.mpris.MediaPlayer2.NuvioSelfTest"
            val commands = LinkedBlockingQueue<String>()
            val owner = LinuxMprisSession(nativeMprisBackend(busName = name, busAddress = address), dispatch = { it() })
            try {
                val client = owner.client { method, _ -> commands += method }
                val token = client.newSource()
                client.publish(LinuxMprisState(token = token, trackId = "/org/mpris/MediaPlayer2/track/t$token",
                    title = "Self test", status = "Playing", durationUs = 10_000_000, canSeek = true))
                var reply = ""
                awaitTrue(10, { "PlaybackStatus reply: $reply" }) {
                    reply = gdbus(address, name, "org.freedesktop.DBus.Properties.Get", "org.mpris.MediaPlayer2.Player", "PlaybackStatus")
                    "Playing" in reply
                }
                gdbus(address, name, "org.mpris.MediaPlayer2.Player.Pause")
                val received = commands.poll(10, TimeUnit.SECONDS)
                check(received == "Pause") { "sink received $received" }
                client.close()
            } finally {
                owner.close()
                check(owner.awaitClosed(5_000)) { "MPRIS owner did not close" }
            }
            return "published, Pause delivered from the bus thread"
        } finally {
            daemon.destroy()
            daemon.waitFor(5, TimeUnit.SECONDS)
        }
    }

    private fun gdbus(address: String, destination: String, method: String, vararg args: String): String {
        val process = ProcessBuilder(
            listOf("gdbus", "call", "--address", address, "--dest", destination,
                "--object-path", "/org/mpris/MediaPlayer2", "--method", method) + args,
        ).redirectErrorStream(true).start()
        check(process.waitFor(5, TimeUnit.SECONDS)) { "gdbus timed out" }
        return process.inputStream.bufferedReader().readText().also { output ->
            check(process.exitValue() == 0) { "gdbus $method failed: ${output.trim()}" }
        }
    }

    private fun <T> withVideoFrame(block: (Frame, NativePlayerHost) -> T): T {
        val host = NativePlayerHost()
        val frame = onEdt {
            Frame("Nuvio native self-test").apply {
                isAutoRequestFocus = false
                focusableWindowState = false
                add(host)
                setSize(320, 180)
                isVisible = true
            }
        }
        try {
            awaitTrue(5, { "host peer not displayable" }) { host.isDisplayable }
            return block(frame, host)
        } finally {
            onEdt { frame.dispose() }
        }
    }

    /** 30 frames of 64x64 4:2:0 at 10 fps (3 s); mpv reads y4m without any codec. */
    private fun writeVideo(target: Path) {
        Files.newOutputStream(target).use { output ->
            output.write("YUV4MPEG2 W64 H64 F10:1 Ip A1:1 C420jpeg\n".toByteArray())
            val pixels = ByteArray(64 * 64 * 3 / 2) { 128.toByte() }
            repeat(30) { frame ->
                pixels.fill((32 + frame * 6).toByte(), 0, 64 * 64)
                output.write("FRAME\n".toByteArray())
                output.write(pixels)
            }
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        if (SwingUtilities.isEventDispatchThread()) return block()
        // An Error thrown on the EDT does not reliably come back out of invokeAndWait.
        var outcome: Result<T> = Result.failure(IllegalStateException("did not run"))
        SwingUtilities.invokeAndWait { outcome = runCatching(block) }
        return outcome.getOrThrow()
    }

    private fun awaitTrue(seconds: Long, state: () -> String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out after ${seconds}s: ${state()}" }
            Thread.sleep(50)
        }
    }
}
