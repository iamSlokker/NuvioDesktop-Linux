package com.nuvio.app.features.p2p

import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The "linux p2p engine" self-test's path through a packaged layout, in a fresh JVM. A small HTTP
 * server stands in for TorrServer, so the extraction and the /echo and /shutdown calls are real
 * and no Go binary is needed.
 */
class LinuxTorrServerSelfTestTest {
    @Test
    fun extractsStartsAndStopsTheBundledEngine() = withHeadlessFixture { root ->
        runHeadlessProbe(root, LinuxTorrServerSelfTestProbe::class.java, "bundled")
    }

    @Test
    fun aPackageWithoutTheEngineFails() = withHeadlessFixture { root ->
        runHeadlessProbe(root, LinuxTorrServerSelfTestProbe::class.java, "missing")
    }
}

object LinuxTorrServerSelfTestProbe {
    private const val FakeEngine = """#!/usr/bin/python3
import http.server, sys, threading
port = int(sys.argv[sys.argv.index('--port') + 1])
class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        self.send_response(200); self.send_header('Content-Length', '0'); self.end_headers()
        if self.path == '/shutdown':
            threading.Thread(target=server.shutdown).start()
    def log_message(self, *args): pass
server = http.server.ThreadingHTTPServer(('127.0.0.1', port), Handler)
server.serve_forever()
"""

    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        System.clearProperty("nuvio.dev.localNatives")
        System.setProperty("jpackage.app-path", root.resolve("image/lib/nuvio").toString())
        System.setProperty("nuvio.desktop.dataDir", root.resolve("data").toString())
        when (args[1]) {
            "bundled" -> {
                val bundled = root.resolve("image/lib/resources/torrserver/linux-amd64/TorrServer")
                Files.createDirectories(bundled.parent)
                Files.writeString(bundled, FakeEngine)
                Files.setPosixFilePermissions(bundled, PosixFilePermissions.fromString("r-xr-xr-x"))
                assertEquals("answered /echo, stopped", TorrServerP2pBackend.selfTestEngine())
                val extracted = Files.list(root.resolve("data/torrserver/bin/linux-amd64")).use { it.toList() }.single()
                assertTrue(extracted.fileName.toString().startsWith("TorrServer-"), extracted.toString())
                assertEquals(FakeEngine, Files.readString(extracted))
            }
            "missing" -> assertFailsWith<P2pStreamingException> { TorrServerP2pBackend.selfTestEngine() }
            else -> error(args[1])
        }
        // The backend's shutdown hook and coroutine threads have nothing left to wait for.
        Runtime.getRuntime().halt(0)
    }
}
