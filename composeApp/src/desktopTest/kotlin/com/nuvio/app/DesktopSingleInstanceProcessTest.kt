package com.nuvio.app

import com.nuvio.app.core.deeplink.AppDeepLink
import com.nuvio.app.core.deeplink.AppDeepLinkRepository
import com.nuvio.app.testing.*
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class DesktopSingleInstanceProcessTest {
    @Test fun secondJvmSignalsPrimaryExactlyOnceAndCleanExitReleasesLockAndListener() = withHeadlessFixture { root ->
        Child(root, "primary:a").use { primary ->
            val port = primary.primaryPort()
            secondary(root, "a", true)
            assertEquals("COUNT:1", primary.command("count"))
            primary.exit()
            assertFalse(Files.exists(root.resolve("a/instance.port")))
            assertListenerClosed(port)
            Child(root, "primary:a").use { next ->
                next.primaryPort()
                assertEquals("COUNT:0", next.command("count"))
                next.exit()
            }
        }
    }

    @Test fun differentDataDirectoriesHaveIndependentOwners() = withHeadlessFixture { root ->
        Child(root, "primary:a").use { a ->
            val portA = a.primaryPort()
            Child(root, "primary:b").use { b ->
                assertNotEquals(portA, b.primaryPort())
                secondary(root, "a", true)
                assertEquals("COUNT:1", a.command("count"))
                assertEquals("COUNT:0", b.command("count"))
                secondary(root, "b", true)
                assertEquals("COUNT:1", a.command("count"))
                assertEquals("COUNT:1", b.command("count"))
                b.exit()
            }
            a.exit()
        }
    }

    @Test fun secondJvmHandsOffDecodedLinkBeforeActivatingAndLeavesItPending() = withHeadlessFixture { root ->
        Child(root, "primary:a").use { primary ->
            primary.primaryPort()
            val url = "nuvio://meta?type=series&id=tt0944947%3A1%3A2%2Fcaf%C3%A9"
            secondary(root, "a", true, url)
            val expected = "META:series:tt0944947:1:2/café"
            assertEquals("LINK:$expected", primary.command("link"))
            assertEquals("ACTIVATED-LINK:$expected", primary.command("activated-link"))
            assertEquals("COUNT:1", primary.command("count"))
            // A plain second launch must activate without erasing the queued link.
            secondary(root, "a", true)
            assertEquals("COUNT:2", primary.command("count"))
            assertEquals("LINK:$expected", primary.command("link"))
            primary.exit()
        }
    }

    // Linux packages register x-scheme-handler/nuvio with `Exec=nuvio-desktop %u`, so clicking a
    // Watch Party invite while Nuvio is open starts a second copy with the link as its argument.
    @Test fun watchPartyInviteFromASecondLaunchJoinsInTheRunningCopy() = withHeadlessFixture { root ->
        Child(root, "primary:a").use { primary ->
            primary.primaryPort()
            secondary(root, "a", true, "nuvio://party/abc234")
            assertEquals("LINK:PARTY:ABC234", primary.command("link"))
            assertEquals("ACTIVATED-LINK:PARTY:ABC234", primary.command("activated-link"))
            assertEquals("COUNT:1", primary.command("count"))
            primary.exit()
        }
    }

    @Test fun linkHandoffStripsRawLineBreaksInsteadOfTruncatingTheUrl() = withHeadlessFixture { root ->
        Child(root, "primary:a").use { primary ->
            primary.primaryPort()
            secondary(root, "a", true, "nuvio://meta?type=series&id=tt094\r\n4947")
            assertEquals("LINK:META:series:tt0944947", primary.command("link"))
            assertEquals("ACTIVATED-LINK:META:series:tt0944947", primary.command("activated-link"))
            assertEquals("COUNT:1", primary.command("count"))
            primary.exit()
        }
    }

    @Test fun invalidLinkCannotReplacePendingNavigationAndLaterLinksStillWork() = withHeadlessFixture { root ->
        Child(root, "primary:a").use { primary ->
            primary.primaryPort()
            secondary(root, "a", true, "nuvio://downloads")
            assertEquals("LINK:DOWNLOADS", primary.command("link"))
            for (invalid in listOf("https://example.invalid/meta?type=series&id=other", "nuvio://meta?type=series")) {
                secondary(root, "a", true, invalid)
                assertEquals("LINK:DOWNLOADS", primary.command("link"))
            }
            secondary(root, "a", true, "nuvio://meta?type=movie&id=tt123")
            assertEquals("LINK:META:movie:tt123", primary.command("link"))
            assertEquals("COUNT:4", primary.command("count"))
            primary.exit()
        }
    }

    @Test fun malformedSignalDoesNotActivateOrPoisonListener() = withHeadlessFixture { root ->
        Child(root, "primary:a").use { primary ->
            val port = primary.primaryPort()
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000)
                socket.soTimeout = 3_000
                socket.getOutputStream().write("not-activate\n".toByteArray())
                socket.getOutputStream().flush()
                assertEquals("ok", socket.getInputStream().bufferedReader().readLine())
            }
            assertEquals("COUNT:0", primary.command("count"))
            secondary(root, "a", true)
            assertEquals("COUNT:1", primary.command("count"))
            primary.exit()
        }
    }

    @Test fun failedSecondarySignalDoesNotTakeOwnershipOrPoisonFutureSignals() = withHeadlessFixture { root ->
        Child(root, "primary:a").use { primary ->
            val port = primary.primaryPort()
            Files.writeString(root.resolve("a/instance.port"), "invalid-port\n")
            secondary(root, "a", false)
            assertEquals("COUNT:0", primary.command("count"))
            Files.writeString(root.resolve("a/instance.port"), "$port\n")
            secondary(root, "a", true)
            assertEquals("COUNT:1", primary.command("count"))
            primary.exit()
        }
    }

    @Test fun abruptOwnerExitReleasesOsLockDespiteStalePortFile() = withHeadlessFixture { root ->
        Child(root, "primary:a").use { primary ->
            val port = primary.primaryPort()
            primary.kill()
            assertListenerClosed(port)
            assertTrue(Files.exists(root.resolve("a/instance.port")))
            Child(root, "primary:a").use { next ->
                next.primaryPort()
                secondary(root, "a", true)
                assertEquals("COUNT:1", next.command("count"))
                next.exit()
            }
        }
    }

    private fun secondary(root: Path, dir: String, signalled: Boolean, launchUrl: String? = null) = Child(root, "secondary:$dir", launchUrl).use {
        assertEquals("SECONDARY:$signalled", it.next())
        it.awaitExit()
    }
    private fun assertListenerClosed(port: Int) {
        assertFailsWith<java.io.IOException> {
            Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 1_000) }
        }
    }

    private class Child(root: Path, scenario: String, launchUrl: String? = null) : Closeable {
        private val process = headlessProbeBuilder(root, DesktopSingleInstanceProcessProbe::class.java, scenario)
            .apply { if (launchUrl != null) command().add(launchUrl) }
            .redirectErrorStream(true).start()
        private val lines = LinkedBlockingQueue<String>()
        private val output = StringBuffer()
        private val reader = Thread {
            process.inputStream.bufferedReader().useLines { stream -> stream.forEach {
                output.append(it).append('\n')
                if (it.startsWith("fixture:")) lines.put(it.removePrefix("fixture:"))
            } }
        }.apply { isDaemon = true; start() }
        fun next(): String = assertNotNull(lines.poll(12, TimeUnit.SECONDS), "Child did not respond:\n$output")
        fun primaryPort(): Int = next().let {
            assertTrue(it.startsWith("PRIMARY:"), it)
            it.substringAfter(':').toInt()
        }
        fun command(command: String): String {
            process.outputStream.write("$command\n".toByteArray())
            process.outputStream.flush()
            return next()
        }
        fun exit() {
            assertEquals("EXIT", command("exit"))
            awaitExit()
        }
        fun awaitExit() {
            assertTrue(process.waitFor(12, TimeUnit.SECONDS), "Child did not exit:\n$output")
            assertEquals(0, process.exitValue(), output.toString())
        }
        fun kill() {
            process.destroyForcibly()
            assertTrue(process.waitFor(5, TimeUnit.SECONDS))
        }
        override fun close() {
            stopHeadlessProbe(process)
            reader.join(5_000)
            assertFalse(reader.isAlive, "Child output reader did not stop")
        }
    }
}

internal object DesktopSingleInstanceProcessProbe {
    @JvmStatic fun main(args: Array<String>) {
        val (role, dir) = args[1].split(':')
        val data = Path.of(args[0]).resolve(dir)
        val count = AtomicInteger()
        val activatedLink = AtomicReference<AppDeepLink?>()
        DesktopSingleInstance.onActivate = {
            activatedLink.set(AppDeepLinkRepository.pendingDeepLink.value)
            count.incrementAndGet()
        }
        when (val outcome = DesktopSingleInstance.claim(data, args.getOrNull(2))) {
            DesktopSingleInstance.Outcome.Primary -> {
                check(role == "primary") { "Secondary incorrectly acquired ownership" }
                println("fixture:PRIMARY:${Files.readString(data.resolve("instance.port")).trim()}")
                val input = System.`in`.bufferedReader()
                while (true) when (input.readLine()) {
                    "count" -> println("fixture:COUNT:${count.get()}")
                    "link" -> println("fixture:LINK:${describe(AppDeepLinkRepository.pendingDeepLink.value)}")
                    "activated-link" -> println("fixture:ACTIVATED-LINK:${describe(activatedLink.get())}")
                    "exit", null -> { println("fixture:EXIT"); return }
                    else -> error("Unexpected test command")
                }
            }
            is DesktopSingleInstance.Outcome.Secondary -> {
                check(role == "secondary") { "Expected an independent primary owner" }
                println("fixture:SECONDARY:${outcome.signalled}")
            }
        }
    }

    private fun describe(link: AppDeepLink?): String = when (link) {
        is AppDeepLink.Meta -> "META:${link.type}:${link.id}"
        AppDeepLink.Downloads -> "DOWNLOADS"
        is AppDeepLink.Party -> "PARTY:${link.code}"
        null -> "NONE"
    }
}
