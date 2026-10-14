package com.nuvio.app.features.metadata

import com.nuvio.app.testing.LocalHttpFixture
import com.nuvio.app.testing.respond
import java.io.File
import java.net.http.HttpClient
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Loopback-only checks of the Linux runtime download; no network, display or player. */
class AnimeListDownloadTest {
    private val cacheDir: File = Files.createTempDirectory("nuvio-anime-download").toFile()
    private val client = HttpClient.newHttpClient()
    private val payload = """[{"mal_id":1,"kitsu_id":1}]""".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }

    @AfterTest
    fun cleanup() {
        cacheDir.deleteRecursively()
    }

    private fun serve(body: ByteArray, status: Int = 200, block: (String, LocalHttpFixture) -> Unit) {
        LocalHttpFixture().use { server ->
            server.route("/anime.json") { exchange, _ -> exchange.respond(status, body) }
            server.start()
            block(server.url + "/anime.json", server)
        }
    }

    @Test
    fun verifiedDownloadIsCachedAndReusedWithoutNetwork() {
        serve(payload) { url, server ->
            assertEquals(String(payload), AnimeListDownload.loadOrFetch(cacheDir, url, sha, payload.size.toLong(), client))
            server.nextRequest()
            assertEquals(String(payload), AnimeListDownload.loadOrFetch(cacheDir, url, sha, payload.size.toLong(), client))
            assertTrue(server.drainRequests().isEmpty())
        }
        assertEquals(listOf("anime-list-mini-$sha.json"), cacheDir.list()!!.toList())
    }

    @Test
    fun tamperedOversizedOrFailedDownloadsAreRejectedAndNotCached() {
        val tampered = payload.copyOf().also { it[2] = 'x'.code.toByte() }
        serve(tampered) { url, _ ->
            assertFailsWith<IllegalStateException> {
                AnimeListDownload.loadOrFetch(cacheDir, url, sha, payload.size.toLong(), client)
            }
        }
        serve(payload + payload) { url, _ ->
            assertFailsWith<IllegalStateException> {
                AnimeListDownload.loadOrFetch(cacheDir, url, sha, payload.size.toLong(), client)
            }
        }
        serve(payload, status = 404) { url, _ ->
            assertFailsWith<IllegalStateException> {
                AnimeListDownload.loadOrFetch(cacheDir, url, sha, payload.size.toLong(), client)
            }
        }
        assertFalse(cacheDir.listFiles()!!.any { it.isFile })
    }

    @Test
    fun corruptedCacheIsReplacedByAVerifiedDownload() {
        cacheDir.mkdirs()
        File(cacheDir, "anime-list-mini-$sha.json").writeBytes(ByteArray(payload.size))
        serve(payload) { url, server ->
            assertEquals(String(payload), AnimeListDownload.loadOrFetch(cacheDir, url, sha, payload.size.toLong(), client))
            server.nextRequest()
        }
    }

    @Test
    fun pinnedUpstreamIdentityMatchesBundledProvenance() {
        val provenance = File("src/desktopMain/resources/anime-mapping/PROVENANCE.json").readText()
        assertTrue(AnimeListDownload.COMMIT in provenance)
        assertTrue(AnimeListDownload.SHA256 in provenance)
        assertTrue(AnimeListDownload.URL.startsWith("https://raw.githubusercontent.com/Fribb/anime-lists/"))
    }
}
