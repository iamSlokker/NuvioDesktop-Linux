package com.nuvio.app.core.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The Linux native-image inputs in `composeApp/native-image-linux/` (see its README), held to
 * [NativeImageMetadataTest]'s rules, plus the rule that keeps the Linux bridge's JNI metadata
 * generatable: `generateNativeMetadata` reads `FindClass("literal")` lookups and fails a native
 * build on anything else; this catches it in an ordinary test run.
 */
class LinuxNativeImageMetadataTest {
    private val projectDir: File = listOf(File("."), File("composeApp"))
        .firstOrNull { File(it, "native-image-linux").isDirectory }
        ?: fail("composeApp/native-image-linux not found from ${File(".").absolutePath}")
    private val root = File(projectDir, "native-image-linux")
    private val json = Json { ignoreUnknownKeys = true }

    private fun topics(dir: File) = dir.listFiles { file -> file.isDirectory && file.name != "agent" }.orEmpty().toList()

    @Test
    fun `every Linux metadata file is BOM-free JSON`() {
        val files = root.walkTopDown().filter { it.isFile && it.extension == "json" }.toList()
        assertTrue(files.isNotEmpty(), "no metadata files under $root")
        files.forEach { file ->
            val bytes = file.readBytes()
            assertTrue(
                !(bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()),
                "${file.relativeTo(root)} starts with a BOM; native-image will refuse it",
            )
            runCatching { json.parseToJsonElement(bytes.decodeToString()) }
                .onFailure { fail("${file.relativeTo(root)} is not valid JSON: ${it.message}") }
        }
    }

    @Test
    fun `every Linux topic has its metadata and a README saying why`() {
        assertTrue(File(root, "README.md").isFile, "native-image-linux/README.md is missing")
        val topics = topics(root)
        assertTrue(topics.isNotEmpty(), "no topic directories under $root")
        topics.forEach { topic ->
            assertTrue(File(topic, "reachability-metadata.json").isFile, "${topic.name}/ has no reachability-metadata.json")
            assertTrue(File(topic, "README.md").isFile, "${topic.name}/ has no README.md explaining its entries")
        }
    }

    /** The Linux build passes upstream's topics too, so a type in both would carry two reasons. */
    @Test
    fun `no type is registered by two hand-written topics, Linux or upstream`() {
        val owners = mutableMapOf<String, String>()
        val clashes = mutableListOf<String>()
        (topics(root).map { "native-image-linux/${it.name}" to it } +
            topics(File(projectDir, "native-image")).map { "native-image/${it.name}" to it }).forEach { (name, topic) ->
            val doc = json.parseToJsonElement(File(topic, "reachability-metadata.json").readText()) as JsonObject
            (doc["reflection"] as? JsonArray).orEmpty().forEach { entry ->
                val type = (entry as JsonObject)["type"]?.jsonPrimitive?.content ?: return@forEach
                owners.put(type, name)?.let { previous -> clashes += "$type in $previous/ and $name/" }
            }
        }
        assertTrue(clashes.isEmpty(), "registered twice:\n" + clashes.joinToString("\n"))
    }

    @Test
    fun `every Linux bridge JNI lookup names its class with a FindClass literal`() {
        val sources = File(projectDir, "src/desktopMain/native/linux").listFiles { file ->
            file.isFile && (file.extension == "cpp" || file.extension == "h")
        }.orEmpty()
        assertTrue(sources.isNotEmpty(), "no Linux native sources found")
        val call = """(?:env->|\(\*env\)->)"""
        val assigned = Regex("""(\w+)\s*=\s*${call}FindClass\(\s*"[^"]+"\s*\)""")
        val member = Regex("""${call}(?:Get(?:Static)?(?:Method|Field)ID|ThrowNew)\(\s*(\w+)""")
        val problems = mutableListOf<String>()
        sources.sortedBy { it.name }.forEach { source ->
            val text = source.readText()
            fun line(at: Int) = "${source.name}:${text.take(at).count { it == '\n' } + 1}"
            Regex("""${call}GetObjectClass\(""").findAll(text).forEach {
                problems += "${line(it.range.first)}: GetObjectClass; look the interface up with FindClass(\"literal\")"
            }
            val classes = assigned.findAll(text).map { it.groupValues[1] }.toSet()
            member.findAll(text).forEach { match ->
                if (match.groupValues[1] !in classes) {
                    problems += "${line(match.range.first)}: lookup on '${match.groupValues[1]}', not assigned from FindClass(\"literal\")"
                }
            }
        }
        assertTrue(problems.isEmpty(), "native-image metadata for these lookups cannot be generated:\n" + problems.joinToString("\n"))
    }
}
