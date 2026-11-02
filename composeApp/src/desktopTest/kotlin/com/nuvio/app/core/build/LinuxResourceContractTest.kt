package com.nuvio.app.core.build

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A native image contains only the resources it is told about, and a package only the files
 * stage.py installs. On a JVM everything is on the classpath, so nothing else notices a miss until
 * a user does. Every file must have a way into the packages, and every path the code opens must
 * have one too; run by native-build.sh, so a miss stops the build before the 4 minute image.
 */
class LinuxResourceContractTest {
    private val projectDir: File = listOf(File("."), File("composeApp"))
        .firstOrNull { File(it, "native-image-linux").isDirectory }
        ?: fail("composeApp/native-image-linux not found from ${File(".").absolutePath}")
    private val resources = File(projectDir, "src/desktopMain/resources")
    private val packaging = File(projectDir, "../tools/linux/packaging")
    private val json = Json { ignoreUnknownKeys = true }

    /** Resources a packaging script copies somewhere else, and the text that shows it still does. */
    private val copiedByPackaging = mapOf(
        "anime-mapping/**" to ("native_evidence.py" to "desktopMain/resources/anime-mapping"),
    )

    /** Left out of the Linux packages on purpose. Say why when adding one. */
    private val excluded = mapOf(
        // Windows' TorrServer.exe and its note. Linux builds its own from source (torrserver/build.py).
        "torrserver/windows-amd64/**" to "Windows binary",
    )

    private val globs: List<String> by lazy {
        listOf("native-image-linux", "native-image").flatMap { root ->
            File(projectDir, root).walkTopDown().filter { it.name == "reachability-metadata.json" }.flatMap { file ->
                val doc = json.parseToJsonElement(file.readText()) as JsonObject
                (doc["resources"] as? JsonArray).orEmpty().mapNotNull { (it as JsonObject)["glob"]?.jsonPrimitive?.content }
            }.toList()
        }
    }

    /** What stage.py installs under lib/resources/, read from its own constant. */
    private val installed: List<String> by lazy {
        Regex("""'lib/resources/([^']+)'""").findAll(File(packaging, "stage.py").readText()).map { it.groupValues[1] }.toList()
    }

    private fun bundled(): List<String> = resources.walkTopDown().filter { it.isFile }
        .map { it.relativeTo(resources).invariantSeparatorsPath }.toList()

    private fun kotlinSources(): List<File> =
        listOf("src/desktopMain/kotlin", "src/desktopMain/innertubex/kotlin", "src/commonMain/kotlin").flatMap { root ->
            File(projectDir, root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

    @Test
    fun `the packaging's own contract can be read`() {
        assertTrue("composeResources/**" in globs && "jni/linux_x64/**" in globs, "resource globs not read: $globs")
        assertEquals(listOf("torrserver/linux-amd64/TorrServer"), installed)
        assertTrue(bundled().size > 30, "resources not found under $resources")
        assertTrue(kotlinSources().size > 1000, "sources not found under $projectDir")
    }

    @Test
    fun `every bundled resource is embedded, copied by packaging, or left out on purpose`() {
        copiedByPackaging.forEach { (glob, proof) ->
            assertTrue(proof.second in File(packaging, proof.first).readText(), "${proof.first} no longer copies $glob")
        }
        val orphans = bundled().filterNot { path ->
            matchesAny(path, globs) || matchesAny(path, installed) ||
                matchesAny(path, copiedByPackaging.keys) || matchesAny(path, excluded.keys)
        }
        assertEquals(
            emptyList(), orphans,
            "no package carries these: add a glob to native-image-linux/resources, install them in stage.py, or list them in `excluded`",
        )
        val unused = (copiedByPackaging.keys + excluded.keys).filter { glob -> bundled().none { globMatches(glob, it) } }
        assertEquals(emptyList(), unused, "these entries match nothing any more")
    }

    @Test
    fun `every resource path the code opens is in the image or installed`() {
        // PackagedResources itself passes on whatever its callers asked for.
        val sites = kotlinSources().filter { it.name != "PackagedResources.kt" }
            .flatMap { file -> resourceSites(file.readText()).map { file.name to it } }
        assertTrue(sites.size >= 20, "resource reads not found: $sites")
        val problems = sites.flatMap { (file, site) ->
            val paths = site.paths
            when {
                paths.isEmpty() -> listOf("$file: cannot tell what `${site.argument}` opens; use a literal or a constant")
                else -> paths.mapNotNull { path -> problem(path)?.let { "$file: `${site.argument}`: $path $it" } }
            }
        }
        assertEquals(emptyList(), problems, "no package path for these")
    }

    private fun problem(path: String): String? {
        val sample = path.removePrefix("/").replace('*', 'x')
        return when {
            matchesAny(sample, globs) || installed.any { globMatches(path.removePrefix("/"), it) } -> {
                val literal = '*' !in path && !path.removePrefix("/").startsWith("native/")
                if (literal && !File(resources, path.removePrefix("/")).isFile && path.removePrefix("/") !in installed) {
                    "matches a glob, but no such file exists"
                } else {
                    null
                }
            }
            else -> "matches no native-image glob and nothing in stage.py"
        }
    }

    @Test
    fun `the checker notices a path with no way into the packages`() {
        assertEquals("matches no native-image glob and nothing in stage.py", problem("/fonts/new.ttf"))
        assertEquals("matches a glob, but no such file exists", problem("/player-ui/missing.css"))
        assertEquals(null, problem("/player-ui/controls.css"))
        assertEquals(null, problem("/player-shaders/*.glsl"))
        assertEquals(null, problem("torrserver/linux-amd64/TorrServer"))
        assertEquals(null, problem("torrserver/*/*"))
    }

    @Test
    fun `resource reads are resolved through constants, templates and parameters`() {
        val sites = resourceSites(
            """
            private const val PREFIX = "/player-scripts/"
            private const val WEIGHTS = "/audiosync/a.bin"
            val a = X::class.java.getResourceAsStream("/player-ui/${'$'}name")
            val b = X::class.java.getResourceAsStream("${'$'}{PREFIX}svp.conf")
            val c = javaClass.getResourceAsStream(WEIGHTS)
            val d = PackagedResources.open("backdrop-framing/${'$'}name")
            fun load(resource: String) = X::class.java.getResourceAsStream(resource)
            val e = load("/one.css") + load("/two/${'$'}x.css")
            fun local() { val resource2 = "torrserver/${'$'}{p.dir}/${'$'}{p.name}"; PackagedResources.open(resource2) }
            val f = X::class.java.getResourceAsStream(unknown)
            // X::class.java.getResourceAsStream("/commented/out")
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                listOf("/player-ui/*"), listOf("/player-scripts/svp.conf"), listOf("/audiosync/a.bin"),
                listOf("backdrop-framing/*"), listOf("/one.css", "/two/*.css"), listOf("torrserver/*/*"), emptyList(),
            ),
            sites.map { it.paths },
        )
    }

    private class Site(val argument: String, val paths: List<String>)

    /** Reads of a resource in one Kotlin file, with what each can open: `*` for a run-time part. */
    private fun resourceSites(source: String): List<Site> {
        val code = source.lineSequence().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")
        return Regex("""(?:PackagedResources\.open|\.getResourceAsStream|\.getResource)\(([^()]*)\)""").findAll(code)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotEmpty() }
            .map { argument -> Site(argument, resolve(argument, code, 0)) }
            .toList()
    }

    private fun resolve(argument: String, code: String, depth: Int): List<String> {
        if (depth > 3) return emptyList()
        if (argument.startsWith("\"")) return listOf(template(argument.trim('"'), code))
        if (!argument.matches(Regex("""\w+"""))) return emptyList()
        val constants = Regex("""\bval\s+$argument\s*(?::\s*String\s*)?=\s*("[^"\n]*")""").findAll(code).map { it.groupValues[1] }.toList()
        if (constants.isNotEmpty()) return constants.map { template(it.trim('"'), code) }
        // A parameter: what the same file passes to that function.
        val function = Regex("""\bfun\s+(\w+)\([^)]*\b$argument:\s*String""").find(code)?.groupValues?.get(1) ?: return emptyList()
        return Regex("""\b$function\(\s*([^,()]+?)\s*[,)]""").findAll(code).map { it.groupValues[1] }
            .filterNot { it.startsWith("$argument:") }
            .flatMap { resolve(it, code, depth + 1) }.toList()
    }

    private fun template(text: String, code: String): String =
        Regex("""\$\{([^}]*)\}|\$(\w+)""").replace(text) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            Regex("""\bconst\s+val\s+$name\s*=\s*"([^"\n$]*)"""").find(code)?.groupValues?.get(1) ?: "*"
        }

    private fun matchesAny(path: String, patterns: Collection<String>) = patterns.any { globMatches(it, path) }

    /** native-image's glob: `**` crosses directories, `*` does not. */
    private fun globMatches(glob: String, path: String): Boolean {
        val pattern = glob.split("**").joinToString(".*") { part -> part.split("*").joinToString("[^/]*") { Regex.escape(it) } }
        return Regex(pattern).matches(path)
    }
}
