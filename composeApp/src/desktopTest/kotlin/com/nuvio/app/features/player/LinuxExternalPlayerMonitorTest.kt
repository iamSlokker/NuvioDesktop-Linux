package com.nuvio.app.features.player

import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

class LinuxExternalPlayerMonitorTest {
    private lateinit var directory: File
    private val host = LinuxExternalPlayerTarget.Executable("/usr/bin/mpv")
    private val flatpak = LinuxExternalPlayerTarget.Flatpak("/usr/bin/flatpak", "io.mpv.Mpv")

    @BeforeTest
    fun setup() {
        assumeTrue(System.getProperty("os.name").contains("linux", ignoreCase = true))
        directory = Files.createTempDirectory("nuvio-mpv-ipc-test-").toFile()
    }

    @AfterTest
    fun cleanup() {
        if (::directory.isInitialized) directory.deleteRecursively()
    }

    private val noConfig get() = File(directory, "absent/mpv.conf")

    private fun mpvConfig(line: String): File =
        File(directory, "config/mpv/mpv.conf").apply {
            parentFile.mkdirs()
            writeText("# comment\nprofile=gpu-hq\n$line\n")
        }

    /** A minimal mpv: answers get_property requests, with an unrequested event line before each reply. */
    private fun fakeMpv(socket: Path, properties: Map<String, String>, replies: Int = Int.MAX_VALUE): Thread {
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        server.bind(UnixDomainSocketAddress.of(socket))
        return thread(isDaemon = true) {
            server.use {
                val client = server.accept()
                client.use {
                    val reader = Channels.newInputStream(client).bufferedReader()
                    val writer = Channels.newOutputStream(client).bufferedWriter()
                    var answered = 0
                    while (answered < replies) {
                        val line = reader.readLine() ?: break
                        val name = Regex(""""get_property","([\w-]+)"""").find(line)!!.groupValues[1]
                        val id = Regex(""""request_id":(\d+)""").find(line)!!.groupValues[1]
                        writer.write("{\"event\":\"playback-restart\"}\n")
                        val data = properties[name]
                        writer.write(
                            if (data != null) "{\"data\":$data,\"request_id\":$id,\"error\":\"success\"}\n"
                            else "{\"request_id\":$id,\"error\":\"property unavailable\"}\n",
                        )
                        writer.flush()
                        answered++
                    }
                }
            }
        }
    }

    @Test
    fun readsPositionDurationAndPauseOverTheUnixSocket() {
        val socket = File(directory, "mpv.sock").toPath()
        fakeMpv(socket, mapOf("time-pos" to "754.25", "duration" to "1800.5", "pause" to "true"))
        LinuxMpvSocketReader(socket, ownsSocket = true).use { reader ->
            assertEquals(ExternalPlaybackSample(positionMs = 754_250, durationMs = 1_800_500, isPaused = true), reader.read())
            // The same connection keeps serving later polls.
            assertEquals(754_250, reader.read()?.positionMs)
        }
        assertFalse(Files.exists(socket), "Nuvio's own socket is removed on close")
    }

    @Test
    fun nothingLoadedYetGivesNoSample() {
        val socket = File(directory, "mpv.sock").toPath()
        fakeMpv(socket, mapOf("pause" to "false"))
        LinuxMpvSocketReader(socket, ownsSocket = true).use { reader -> assertNull(reader.read()) }
    }

    @Test
    fun noSocketYetIsNotAnError() {
        LinuxMpvSocketReader(File(directory, "not-started.sock").toPath(), ownsSocket = true).use { reader ->
            assertNull(reader.read())
        }
    }

    @Test
    fun mpvGoingAwayThrowsSoTheMonitorRetriesOrStops() {
        val socket = File(directory, "mpv.sock").toPath()
        fakeMpv(socket, mapOf("time-pos" to "1", "duration" to "2", "pause" to "false"), replies = 1)
        LinuxMpvSocketReader(socket, ownsSocket = true).use { reader ->
            assertFailsWith<IOException> { reader.read() }
        }
    }

    @Test
    fun aConfiguredSocketIsReusedAndLeftInPlace() {
        val socket = File(directory, "user.sock").toPath()
        fakeMpv(socket, mapOf("time-pos" to "10", "duration" to "20", "pause" to "false"))
        val channel = prepareLinuxExternalPlayerChannel(
            ExternalPlayerControl.Mpv,
            host,
            runtimeDirectory = directory.path,
            mpvConfig = mpvConfig("input-ipc-server=$socket"),
        )
        assertTrue(channel.channel.launchArgs.isEmpty(), "mpv.conf's socket must not be overridden")
        val reader = assertNotNull(channel.channel.reader)
        assertEquals(10_000, reader.read()?.positionMs)
        reader.close()
        assertTrue(Files.exists(socket), "a user's socket is not deleted")
    }

    @Test
    fun hostMpvGetsAPrivateSocketInTheRuntimeFolder() {
        val channel = prepareLinuxExternalPlayerChannel(ExternalPlayerControl.Mpv, host, directory.path, noConfig)
        val argument = channel.channel.launchArgs.single()
        val socket = File(argument.removePrefix("--input-ipc-server="))
        assertEquals(File(directory, "nuvio-mpv"), socket.parentFile)
        assertTrue(socket.name.matches(Regex("""mpv-[0-9a-f]{16}\.sock""")), socket.name)
        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(socket.parentFile.toPath())))
        assertTrue(channel.sandboxFilesystems.isEmpty())
        assertNotNull(channel.channel.reader).close()
    }

    @Test
    fun hostMpvWithoutARuntimeFolderUsesATempFolderItCleansUp() {
        val channel = prepareLinuxExternalPlayerChannel(ExternalPlayerControl.Mpv, host, runtimeDirectory = null, mpvConfig = noConfig)
        val folder = File(channel.channel.launchArgs.single().removePrefix("--input-ipc-server=")).parentFile
        assertTrue(folder.isDirectory)
        assertNotNull(channel.channel.reader).close()
        assertFalse(folder.exists())
    }

    @Test
    fun flatpakMpvIsGrantedTheSocketFolderAndIgnoresHostConfig() {
        val channel = prepareLinuxExternalPlayerChannel(
            ExternalPlayerControl.Mpv,
            flatpak,
            runtimeDirectory = directory.path,
            mpvConfig = mpvConfig("input-ipc-server=/tmp/host-only.sock"),
        )
        assertTrue(channel.channel.launchArgs.single().startsWith("--input-ipc-server=${File(directory, "nuvio-mpv")}/"))
        assertEquals(listOf("xdg-run/nuvio-mpv"), channel.sandboxFilesystems)
        assertNotNull(channel.channel.reader).close()
    }

    @Test
    fun flatpakMpvWithoutARuntimeFolderFallsBackToProcessLifetime() {
        val channel = prepareLinuxExternalPlayerChannel(ExternalPlayerControl.Mpv, flatpak, runtimeDirectory = null, mpvConfig = noConfig)
        assertTrue(channel.channel.launchArgs.isEmpty())
        assertNull(channel.channel.reader)
    }

    @Test
    fun vlcUsesTheSameLoopbackHttpInterfaceAsWindows() {
        val channel = prepareLinuxExternalPlayerChannel(
            ExternalPlayerControl.Vlc,
            LinuxExternalPlayerTarget.Executable("/usr/bin/vlc"),
        )
        val args = channel.channel.launchArgs
        assertTrue("--extraintf=http" in args && "--http-host=127.0.0.1" in args, args.toString())
        assertTrue(args.any { it.startsWith("--http-password=") })
        assertNotNull(channel.channel.reader)
    }

    @Test
    fun configuredSocketPathsExpandLikeMpv() {
        val home = File(directory, "home").path
        assertEquals(File(home, "mpv.sock").toPath(), configuredLinuxMpvIpcSocket(mpvConfig("input-ipc-server=~/mpv.sock"), home))
        val config = mpvConfig("--input-ipc-server = \"~~/socket\"  # for SVP")
        assertEquals(File(config.parentFile, "socket").toPath(), configuredLinuxMpvIpcSocket(config, home))
        assertNull(configuredLinuxMpvIpcSocket(mpvConfig("input-ipc-server=relative.sock"), home))
        assertNull(configuredLinuxMpvIpcSocket(mpvConfig("vo=gpu-next"), home))
        assertNull(configuredLinuxMpvIpcSocket(noConfig, home))
    }

    @Test
    fun mpvConfigFollowsXdgConfigHome() {
        assertEquals(File("/cfg/mpv/mpv.conf"), linuxMpvConfigFile(configHome = "/cfg", home = "/home/u"))
        assertEquals(File("/home/u/.config/mpv/mpv.conf"), linuxMpvConfigFile(configHome = "relative", home = "/home/u"))
    }

    @Test
    fun launchCommandCarriesTheChannelBeforeTheSource() {
        val request = ExternalPlayerPlaybackRequest(sourceUrl = "https://example.test/video.mkv", title = "Test")
        val channel = prepareLinuxExternalPlayerChannel(ExternalPlayerControl.Mpv, flatpak, directory.path, noConfig)
        val command = assertNotNull(ExternalPlayerPlatform.linuxLaunchCommand("mpv", flatpak, request, channel))
        val grant = command.indexOf("--filesystem=xdg-run/nuvio-mpv")
        assertTrue(grant in 0 until command.indexOf("io.mpv.Mpv"), command.toString())
        val ipc = command.indexOfFirst { it.startsWith("--input-ipc-server=") }
        assertTrue(ipc in (command.indexOf("io.mpv.Mpv") + 1) until command.indexOf("--"), command.toString())
        assertEquals("https://example.test/video.mkv", command.last())
        channel.channel.reader?.close()

        // Without tracking the command is exactly what it was before.
        val untracked = assertNotNull(ExternalPlayerPlatform.linuxLaunchCommand("mpv", flatpak, request))
        assertTrue(untracked.none { it.startsWith("--input-ipc-server=") || it.startsWith("--filesystem=xdg-run/") })
    }
}
