package com.nuvio.app.core.build

import com.nuvio.app.features.cast.DlnaDiscovery
import com.nuvio.app.features.iptv.XmltvGuide
import com.nuvio.app.features.plugins.pluginDigestHex
import com.nuvio.app.features.plugins.pluginHmacHex
import java.net.StandardProtocolFamily
import java.net.URI
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.security.KeyStore
import java.security.SecureRandom
import java.text.Normalizer
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import org.xml.sax.SAXException

/**
 * JDK paths a native image can lose while a JVM keeps them: a resource it did not embed, a provider
 * or a class it did not register. A signed-out launch with no audio device reaches none of them.
 * No network, display or device is needed, and a hung path fails instead of holding the self-test.
 */
internal object LinuxRuntimeProbes {

    fun run(probe: (String, () -> String) -> Unit) {
        mapOf<String, () -> String>(
            "linux tls" to ::tls,
            "linux unix socket" to ::unixSocket,
            "linux xml" to ::xml,
            "linux normalizer" to ::normalizer,
            "linux jca" to ::jca,
            "linux audio output" to ::audioOutput,
            "linux library serializers" to ::librarySerializers,
        ).forEach { (name, check) -> probe(name) { bounded(check) } }
    }

    private fun bounded(check: () -> String): String {
        var outcome: Result<String>? = null
        val worker = Thread { outcome = runCatching(check) }.apply { isDaemon = true; start() }
        worker.join(BoundMillis)
        return checkNotNull(outcome) { "still running after ${BoundMillis / 1000}s" }.getOrThrow()
    }

    /**
     * A ClientHello from the default context OkHttp and java.net.http start from, without sending
     * it, and the certificate authorities baked into the image at build time. okhttp3's platform
     * lookup is by name.
     */
    private fun tls(): String {
        val engine = SSLContext.getDefault().createSSLEngine("selftest.invalid", 443).apply { useClientMode = true }
        engine.beginHandshake()
        val hello = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(ByteBuffer.allocate(0), hello)
        check(hello.position() > 0) { "no ClientHello" }
        val authorities = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().sumOf { it.acceptedIssuers.size }
        check(authorities > 0) { "no trusted certificate authorities" }
        checkNotNull(okhttp3.OkHttpClient().sslSocketFactory) { "OkHttp has no TLS" }
        return "hello=${hello.position()} bytes authorities=$authorities"
    }

    /** Discord's IPC and the external player's mpv socket, in-process and on a short private path. */
    private fun unixSocket(): String {
        val directory = Files.createTempDirectory("nuvio-")
        val path = directory.resolve("s")
        try {
            ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
                server.bind(UnixDomainSocketAddress.of(path))
                SocketChannel.open(StandardProtocolFamily.UNIX).use { client ->
                    check(client.connect(UnixDomainSocketAddress.of(path))) { "connect did not complete" }
                    server.accept().use { accepted ->
                        client.write(ByteBuffer.wrap(byteArrayOf(42)))
                        val received = ByteBuffer.allocate(1)
                        check(accepted.read(received) == 1 && received.get(0) == 42.toByte()) { "no byte came back" }
                    }
                }
            }
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
        return "round trip"
    }

    /** DLNA's device description parser, with the error path that needs the JDK's message bundle. */
    private fun xml(): String {
        val location = URI("http://127.0.0.1/description.xml")
        val renderer = checkNotNull(DlnaDiscovery.parseDescription("uuid:selftest", location, DlnaDescription)) { "no renderer parsed" }
        check(renderer.device.name == "Self test") { "parsed ${renderer.device.name}" }
        val malformed = runCatching { DlnaDiscovery.parseDescription("uuid:selftest", location, "<root><device>") }
        check(malformed.exceptionOrNull() is SAXException) { "malformed XML gave $malformed" }
        return "parsed, malformed rejected"
    }

    /** IPTV guide names and the search folding: NFD and NFC read one ICU file, NFKD and NFKC another. */
    private fun normalizer(): String {
        check(XmltvGuide.normalizeName("PT: RTP Açores HD") == "rtpacores") { XmltvGuide.normalizeName("PT: RTP Açores HD") }
        check(Normalizer.normalize("ç", Normalizer.Form.NFD) == "ç") { "NFD" }
        check(Normalizer.normalize("ç", Normalizer.Form.NFC) == "ç") { "NFC" }
        check(Normalizer.normalize("ﬁ", Normalizer.Form.NFKD) == "fi") { "NFKD" }
        check(Normalizer.normalize("ﬁ", Normalizer.Form.NFKC) == "fi") { "NFKC" }
        return "NFD NFC NFKD NFKC"
    }

    /** Known answers, because a provider can be present and wrong: every algorithm the app names. */
    private fun jca(): String {
        val digests = mapOf(
            "MD5" to "900150983cd24fb0", "SHA-1" to "a9993e364706816a",
            "SHA-256" to "ba7816bf8f01cfea", "SHA-512" to "ddaf35a193617aba",
        )
        digests.forEach { (name, start) -> check(pluginDigestHex(name, "abc").startsWith(start)) { name } }
        mapOf("MD5" to 32, "SHA1" to 40, "SHA256" to 64, "SHA512" to 128).forEach { (name, length) ->
            check(pluginHmacHex(name, "key", "abc").length == length) { "HMAC $name" }
        }
        val key = SecretKeySpec(ByteArray(16) { it.toByte() }, "AES")
        val iv = IvParameterSpec(ByteArray(16))
        listOf("AES/CBC/PKCS5Padding" to iv, "AES/ECB/PKCS5Padding" to null).forEach { (name, parameters) ->
            val plain = "selftest".toByteArray()
            val sealed = Cipher.getInstance(name).apply { init(Cipher.ENCRYPT_MODE, key, parameters) }.doFinal(plain)
            val opened = Cipher.getInstance(name).apply { init(Cipher.DECRYPT_MODE, key, parameters) }.doFinal(sealed)
            check(opened.contentEquals(plain)) { name }
        }
        SecureRandom().nextBytes(ByteArray(16))
        return "${digests.size} digests, 4 HMACs, 2 ciphers, SecureRandom"
    }

    /**
     * The line the preshow keep-alive opens, up to the open: libjsound and its providers must load.
     * The smoke has no sound card, and "no line matches" is what a machine without one gets.
     */
    private fun audioOutput(): String {
        val mixers = AudioSystem.getMixerInfo().size
        val line = try {
            AudioSystem.getSourceDataLine(AudioFormat(48_000f, 16, 2, true, false)).lineInfo.toString()
        } catch (_: IllegalArgumentException) {
            "no output device"
        }
        return "mixers=$mixers line=$line"
    }

    /**
     * Library types Ktor and Supabase look up by class at run time, each by name because some are
     * internal. The first group has no generated serializer, so native-image-linux/library-serializers
     * lists them. InnerTubeX is compiled in only with -Pnuvio.innertubex=true.
     */
    private fun librarySerializers(): String {
        val supabase = listOf(
            "io.github.jan.supabase.auth.GoTrueErrorResponse",
            "io.github.jan.supabase.auth.mfa.AuthenticatorAssuranceLevel",
            "io.github.jan.supabase.auth.jwt.AMRMethod",
            "io.github.jan.supabase.realtime.Presence",
            "io.github.jan.supabase.auth.user.UserInfo",
            "io.github.jan.supabase.auth.user.Identity",
            "io.github.jan.supabase.auth.mfa.MfaChallenge",
            "io.github.jan.supabase.postgrest.PostgrestErrorResponse",
            "io.github.jan.supabase.realtime.RealtimeMessage",
            "io.github.jan.supabase.realtime.RealtimeJoinPayload",
        )
        supabase.forEach { name -> kotlinx.serialization.serializer(Class.forName(name)).descriptor.serialName }
        val innerTube = runCatching { Class.forName("com.metrolist.innertubex.models.Context") }.getOrNull()
            ?.let { kotlinx.serialization.serializer(it).descriptor.serialName }
        return "${supabase.size} Supabase, InnerTube ${if (innerTube == null) "not in this build" else "ok"}"
    }

    private const val BoundMillis = 30_000L

    private val DlnaDescription = """
        <root><device>
          <friendlyName>Self test</friendlyName>
          <serviceList><service>
            <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
            <controlURL>/control</controlURL>
          </service></serviceList>
        </device></root>
    """.trimIndent()
}
