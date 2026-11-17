package com.nuvio.app.features.usenet

import java.io.File
import java.nio.file.Files
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private val isLinuxHost = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("linux")

/** The engine's name on Linux (tools/linux/packaging/usenet/build.py); null elsewhere. */
internal val linuxUsenetEngineName: String? = if (isLinuxHost) "nuvio-usenet" else null

/**
 * The Linux packages keep the engine in lib/native, beside lib/nuvio and the other native helpers
 * (as libnuvio_engine.so); [appDir] is lib/, the folder of the app executable. Null elsewhere.
 */
internal fun linuxUsenetEngineDir(appDir: File): File? = if (isLinuxHost) appDir.resolve("native") else null

/**
 * The packages' smoke probe ("linux usenet engine"): starts the engine as [UsenetEngine] does (a
 * bootstrap line on stdin), requires its protocol 1 ready record, then closes stdin and requires
 * it to stop. No provider or NZB is involved.
 */
internal fun linuxUsenetEngineSelfTest(): String {
    val executable = usenetEngineExecutable() ?: return "not in this layout"
    val cache = Files.createTempDirectory("nuvio-usenet-probe").toFile()
    val process = ProcessBuilder(executable.absolutePath)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    try {
        val bootstrap = buildJsonObject {
            put("token", UUID.randomUUID().toString().replace("-", ""))
            put("nzbCacheDir", cache.absolutePath)
            put("memoryMiB", 96)
            putJsonArray("certificates") {}
        }
        process.outputStream.write((bootstrap.toString() + "\n").toByteArray())
        process.outputStream.flush()
        val ready = CompletableFuture.supplyAsync { process.inputStream.bufferedReader().readLine() }
            .get(15, TimeUnit.SECONDS) ?: error("the engine exited on startup")
        val record = Json.parseToJsonElement(ready).jsonObject
        check(record["protocol"]?.jsonPrimitive?.int == 1) { "ready record $ready" }
        val port = record.getValue("port").jsonPrimitive.int
        process.outputStream.close()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "the engine kept running after its stdin closed" }
        check(process.exitValue() == 0) { "the engine exited ${process.exitValue()}" }
        return "protocol 1 on 127.0.0.1:$port, stopped with its stdin"
    } finally {
        if (process.isAlive) process.destroyForcibly()
        cache.deleteRecursively()
    }
}
