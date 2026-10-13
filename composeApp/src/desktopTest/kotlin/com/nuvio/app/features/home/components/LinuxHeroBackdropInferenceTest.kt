package com.nuvio.app.features.home.components

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtProvider
import coil3.asImage
import com.nuvio.app.testing.headlessProbeBuilder
import com.nuvio.app.testing.stopHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

/** Real CPU inference and failure paths, each in a fresh JVM with disposable storage and no display. */
class LinuxHeroBackdropInferenceTest {
    @Test
    fun bundledModelsRunOnCpuAndReuseMemoryAndDiskProfiles() = withHeadlessFixture { root ->
        probe(root, "inference")
        // A fresh process cannot infer with this empty native directory. A successful read
        // therefore proves that the production cache is used without loading either model.
        probe(root, "disk-cache", missingNatives = true)
    }

    @Test
    fun missingNativeLibrariesFallBackWithoutRetriedLoads() = withHeadlessFixture { root ->
        probe(root, "unavailable", missingNatives = true)
    }

    @Test
    fun invalidSaliencyModelFallsBackWithoutRetriedLoads() = withHeadlessFixture { root ->
        probe(root, "unavailable", invalidModel = "u2netp.onnx")
    }

    @Test
    fun invalidFaceModelAfterSaliencyLoadFallsBack() = withHeadlessFixture { root ->
        probe(root, "unavailable", invalidModel = "yunet.onnx")
    }

    @Test
    fun missingFaceResourceFallsBack() = withHeadlessFixture { root ->
        probe(root, "missing-model")
    }

    @Test
    fun cancelledQueuedAnalysisCannotPublishAndFreshRequestStillWorks() = withHeadlessFixture { root ->
        probe(root, "cancelled-queue")
    }

    @Test
    fun linuxJniModelsAndLicenseResourcesArePresent() = withHeadlessFixture {
        listOf("libonnxruntime.so", "libonnxruntime4j_jni.so").forEach { name ->
            val bytes = assertNotNull(javaClass.getResourceAsStream("/ai/onnxruntime/native/linux-x64/$name"))
                .use { it.readNBytes(4) }
            assertTrue(bytes.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())))
        }
        listOf("u2netp.onnx", "yunet.onnx", "NOTICE.txt", "LICENSE-U2NET.txt", "LICENSE-YUNET.txt", "LICENSE-ONNXRUNTIME.txt").forEach { name ->
            assertNotNull(javaClass.getResourceAsStream("/backdrop-framing/$name"), name).use {
                assertTrue(it.read() >= 0, "Empty resource $name")
            }
        }
    }

    private fun probe(root: Path, scenario: String, missingNatives: Boolean = false, invalidModel: String? = null) {
        val builder = headlessProbeBuilder(root, LinuxHeroBackdropInferenceProbe::class.java, scenario)
        val command = builder.command()
        val resources = root.resolve("override-resources")
        Files.createDirectories(resources.resolve("backdrop-framing"))
        if (invalidModel != null) Files.writeString(resources.resolve("backdrop-framing/$invalidModel"), "invalid ONNX fixture")
        val classpathIndex = command.indexOf("-cp") + 1
        command[classpathIndex] = resources.toString() + File.pathSeparator + command[classpathIndex]
        if (missingNatives) {
            val empty = Files.createDirectories(root.resolve("empty-natives"))
            command.add(1, "-Donnxruntime.native.path=$empty")
        }
        val output = root.resolve("$scenario.log")
        val process = builder.redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Inference probe timed out: $scenario")
            val log = Files.readString(output)
            assertEquals(0, process.exitValue(), log)
            if (scenario == "unavailable" || scenario == "missing-model") {
                assertEquals(1, Regex("Backdrop framing models unavailable").findAll(log).count(), log)
            }
        } finally {
            stopHeadlessProbe(process)
        }
    }
}

/** Calls the same entry points as HomeHeroSection; no Compose window or network image is involved. */
object LinuxHeroBackdropInferenceProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Path.of(args[0])
        System.setProperty("nuvio.desktop.dataDir", root.resolve("nuvio-data").toString())
        val width = 96
        val height = 54
        val rgba = ByteArray(width * height * 4) { index ->
            when (index % 4) {
                3 -> 255.toByte()
                else -> ((index / 4 / width * 5 + index / 4 % width * 2) % 256).toByte()
            }
        }
        when (args[1]) {
            "inference" -> runBlocking {
                Bitmap().use { bitmap ->
                    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
                    check(bitmap.installPixels(info, rgba, width * 4))
                    bitmap.setImmutable()
                    val profile = assertNotNull(HeroBackdropFramer.profile(CacheKey, bitmap.asImage()))
                    verifyProfile(profile)
                    assertSame(profile, HeroBackdropFramer.peek(CacheKey))
                    assertSame(profile, HeroBackdropFramer.profile(CacheKey, null))
                    assertEquals(setOf(OrtProvider.CPU), OrtEnvironment.getAvailableProviders())
                    assertEquals("1.31.0", OrtEnvironment.getEnvironment().version)
                    // Linux uses ORT's platform loader, never the Windows persistent DLL path.
                    assertNull(System.getProperty("onnxruntime.native.path"))
                }
            }
            "missing-model" -> {
                // Mask the resource through the actual class loader used by FramingModels.
                // Merely placing an empty directory first on the classpath would still find
                // the valid resource later, so it would not test a missing model.
                val urls = System.getProperty("java.class.path").split(File.pathSeparator)
                    .map { File(it).toURI().toURL() }.toTypedArray()
                object : URLClassLoader(urls, ClassLoader.getPlatformClassLoader()) {
                    override fun getResource(name: String): URL? =
                        if (name == "backdrop-framing/yunet.onnx") null else super.getResource(name)
                }.use { loader ->
                    val thread = Thread.currentThread()
                    val previousLoader = thread.contextClassLoader
                    try {
                        // kotlin.test discovers its assertion provider with ServiceLoader.
                        // It must see the same isolated classes as the reflected probe.
                        thread.contextClassLoader = loader
                        loader.loadClass(LinuxHeroBackdropInferenceProbe::class.java.name)
                            .getMethod("main", Array<String>::class.java)
                            .invoke(null, arrayOf(args[0], "unavailable") as Any)
                    } finally {
                        thread.contextClassLoader = previousLoader
                    }
                }
            }
            "cancelled-queue" -> runBlocking {
                // Occupy the production single-analysis dispatcher so cancellation happens
                // deterministically before JNI starts, without sleeps or fake inference.
                val type = HeroBackdropFramer::class.java
                val dispatcher = type.getDeclaredField("analysisDispatcher").apply { isAccessible = true }
                    .get(null) as CoroutineDispatcher
                @Suppress("UNCHECKED_CAST")
                val inFlight = type.getDeclaredField("inFlight").apply { isAccessible = true }
                    .get(null) as MutableMap<String, *>
                val started = CountDownLatch(1)
                val release = CountDownLatch(1)
                val blocker = launch(dispatcher) {
                    started.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Analysis fixture was not released" }
                }
                check(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
                Bitmap().use { bitmap ->
                    val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL)
                    check(bitmap.installPixels(info, rgba, width * 4))
                    bitmap.setImmutable()
                    val request = async(Dispatchers.Default) { HeroBackdropFramer.profile(CacheKey, bitmap.asImage()) }
                    try {
                        withTimeout(10_000) {
                            while (!synchronized(inFlight) { inFlight.containsKey(CacheKey) }) yield()
                        }
                    } finally {
                        request.cancel()
                        release.countDown()
                        withContext(NonCancellable) {
                            request.join()
                            blocker.join()
                        }
                    }
                    assertNull(HeroBackdropFramer.peek(CacheKey))
                    assertTrue(synchronized(inFlight) { !inFlight.containsKey(CacheKey) })
                    verifyProfile(assertNotNull(HeroBackdropFramer.profile(CacheKey, bitmap.asImage())))
                }
            }
            "disk-cache" -> runBlocking {
                assertNull(HeroBackdropFramer.peek(CacheKey))
                verifyProfile(assertNotNull(HeroBackdropFramer.profile(CacheKey, null)))
            }
            "unavailable" -> {
                assertNull(analyzeHeroBackdropPixels(rgba, width, height))
                // Restore valid resources before the second call. A failed model load is
                // deliberately remembered instead of repeatedly consuming background CPU.
                root.resolve("override-resources/backdrop-framing").toFile().listFiles()?.forEach { it.delete() }
                assertNull(analyzeHeroBackdropPixels(rgba, width, height))
            }
            else -> error("Unknown scenario ${args[1]}")
        }
    }

    private fun verifyProfile(profile: HeroBackdropFramingProfile) {
        assertEquals(160, profile.subjectMass.size)
        assertEquals(160, profile.edgeCut.size)
        assertTrue(profile.subjectMass.all { it.isFinite() && it >= 0f })
        assertTrue(abs(profile.subjectMass.sum() - 1f) < 0.001f)
        assertTrue(profile.edgeCut.all { it.isFinite() && it in 0f..1f })
        assertTrue(profile.faces.all { face -> listOf(face.left, face.top, face.right, face.bottom).all(Float::isFinite) })
        assertTrue(heroBackdropFramingBias(profile, 3.55f, -0.58f) in -1f..1f)
    }

    private const val CacheKey = "fixture://generated-backdrop"
}
