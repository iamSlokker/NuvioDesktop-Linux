package com.nuvio.app.features.metadata

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * Linux release packages do not redistribute anime-list-mini.json (its rights chain is not
 * established; see docs/compliance/artwork-data-closure.md). When the packaged resource is
 * absent, fetch the exact reviewed upstream revision once, verify size and SHA-256, and cache it
 * in the app data directory. Builds that still bundle the file never reach this path.
 */
internal object AnimeListDownload {
    const val COMMIT = "4ddbccd752fb4fe39da71f9729b33e4fd7257555"
    const val SHA256 = "cfcdee0da5c0c9f2cf91948390d40f5a49b0a59469b8f4030599a947afb2deba"
    const val BYTES = 5_862_205L
    const val URL = "https://raw.githubusercontent.com/Fribb/anime-lists/$COMMIT/anime-list-mini.json"

    private val log = Logger.withTag("AnimeIdMapping")

    private val client: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    fun loadOrFetch(): String? =
        runCatching {
            loadOrFetch(DesktopStorage.rootDir.resolve("anime-mapping").toFile(), URL, SHA256, BYTES, client)
        }.onFailure { error ->
            log.w(error) { "Could not obtain the anime ID mapping; anime ID conversion will be limited." }
        }.getOrNull()

    internal fun loadOrFetch(
        cacheDir: File,
        url: String,
        sha256: String,
        bytes: Long,
        client: HttpClient,
    ): String {
        val cached = File(cacheDir, "anime-list-mini-$sha256.json")
        if (cached.isFile && cached.length() == bytes && sha256Of(cached) == sha256) {
            return cached.readText()
        }
        cacheDir.mkdirs()
        val partial = File(cacheDir, cached.name + ".part")
        partial.delete()
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            error("Anime ID mapping download failed with HTTP ${response.statusCode()}.")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var received = 0L
        try {
            response.body().use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        received += read
                        check(received <= bytes) { "Anime ID mapping download is larger than expected." }
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                    }
                }
            }
            check(received == bytes) { "Anime ID mapping download has an unexpected size." }
            check(digest.digest().toHex() == sha256) { "Anime ID mapping download failed SHA-256 verification." }
            Files.move(partial.toPath(), cached.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            partial.delete()
        }
        return cached.readText()
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
