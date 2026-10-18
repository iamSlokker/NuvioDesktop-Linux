// ---------------------------------------------------------------------------------------------
// GraalVM native image, Linux x86_64. The Linux counterpart of the Windows tasks in
// build.gradle.kts (nativeCompile, createNativeDistributable, nativeSmoke, nativeImageTrace),
// kept in this file so upstream merges only meet the one-line apply() there. Inputs and rules:
// native-image-linux/README.md. None of these runs as part of createDistributable or a build:
//
//   linuxNativeImageTrace           JVM app on GraalVM's java with the tracing agent, in a container
//                                   with a private Xvfb + D-Bus (merges into native-image-linux/agent/)
//   linuxNativeCompile              native-image over createDistributable's jars -> build/native-image-linux/work
//   createLinuxNativeDistributable  the package layout -> build/native-image-linux/nuvio
//   linuxNativeSmoke                launches that layout with its self-test in a container (gate)
//
// The generated metadata (serializers, bridge JNI) comes from upstream's generateNativeMetadata,
// which build.gradle.kts points at the Linux jpackage image and C++ sources. This is a separate
// script, so it cannot call build.gradle.kts's helpers; the few it needs are restated here.
//
// Where native-image runs: on the machine running Gradle, or with
// -Pnuvio.linux.nativeImageContainer=<image> inside that container (project and GraalVM mounted
// at their own paths, no network). Release packages are built in the Ubuntu 22.04 package builder
// so the binary's glibc floor is that image's, never the developer's host; local builds should
// use the same image (the host also needs gcc and a static zlib, which immutable hosts lack).
//
// Properties: -Pnuvio.graalvm.home (or GRAALVM_HOME / local.properties): GraalVM for JDK 25.
// -Pnuvio.native.qa=true: QA build (reports missing registrations, never package it).
// -Pnuvio.native.march (default compatibility). -Pnuvio.native.builderHeap (default 10g).
// -Pnuvio.linux.nativeRuntimeDir=<dir>: copied to lib/native (libplayer_bridge.so, libmpv, ...).
// -Pnuvio.linux.smokeImage=<image>: container for linuxNativeSmoke / linuxNativeImageTrace.
// -Pnuvio.native.smokeRuns (default 3).
// ---------------------------------------------------------------------------------------------
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.util.Properties

val linuxNativeHost = System.getProperty("os.name").contains("linux", ignoreCase = true)
val linuxNativeInputsDir = layout.projectDirectory.dir("native-image-linux")
val linuxNativeUpstreamInputsDir = layout.projectDirectory.dir("native-image")
val linuxNativeGeneratedDir = layout.buildDirectory.dir("native-image/generated")
val linuxJvmImageDir = layout.buildDirectory.dir("compose/binaries/main/app/nuvio")
val linuxSerializationMetadataDir = layout.buildDirectory.dir("native-image-linux/generated-serialization")
val linuxNativeWorkDir = layout.buildDirectory.dir("native-image-linux/work")
val linuxNativeDistDir = layout.buildDirectory.dir("native-image-linux/nuvio")
val linuxNativeSmokeScript = layout.projectDirectory.file("scripts/linux-native-smoke.py")

fun linuxNativeProperty(name: String): String? {
    val local = Properties().apply {
        rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
    }
    return (providers.gradleProperty(name).orNull ?: System.getenv(name) ?: local.getProperty(name))
        ?.trim()?.takeIf { it.isNotEmpty() }
}

val linuxNativeQa = providers.gradleProperty("nuvio.native.qa").orNull.toBoolean()
val linuxNativeMarch = linuxNativeProperty("nuvio.native.march") ?: "compatibility"
val linuxNativeBuilderHeap = linuxNativeProperty("nuvio.native.builderHeap") ?: "10g"
val linuxNativeContainer = linuxNativeProperty("nuvio.linux.nativeImageContainer")
val linuxNativeRuntimeDir = linuxNativeProperty("nuvio.linux.nativeRuntimeDir")
val linuxSmokeImage = linuxNativeProperty("nuvio.linux.smokeImage")

fun linuxGraalVmHome(): File {
    val home = listOfNotNull(linuxNativeProperty("nuvio.graalvm.home"), linuxNativeProperty("GRAALVM_HOME"))
        .map(::File).firstOrNull { it.resolve("bin/native-image").isFile }
        ?: throw GradleException("GraalVM for JDK 25 was not found. Pass -Pnuvio.graalvm.home=/path/to/graalvm or set GRAALVM_HOME.")
    val version = home.resolve("release").takeIf { it.isFile }?.readText()
        ?.let { Regex("JAVA_VERSION=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
    if (version == null || !version.startsWith("25")) {
        throw GradleException("native-image needs GraalVM for JDK 25; $home is ${version ?: "an unknown version"}.")
    }
    return home
}

/** jpackage's lib/app/nuvio.cfg: classpath, main class and JVM options of the JVM image. */
class LinuxJvmImageConfig(val classpath: List<String>, val mainClass: String, val javaOptions: List<String>)

fun readLinuxJvmImageConfig(appDir: File): LinuxJvmImageConfig {
    val lines = appDir.resolve("nuvio.cfg").readLines()
    fun values(key: String) = lines.filter { it.startsWith("$key=") }
        .map { it.substringAfter('=').replace("\$APPDIR", appDir.absolutePath) }
    return LinuxJvmImageConfig(values("app.classpath"), values("app.mainclass").single(), values("java-options"))
}

/** Java argfile line (native-image reads @files like the java launcher). */
fun linuxArgLine(arg: String): String =
    if (arg.none { it.isWhitespace() || it == '"' || it == '\'' || it == '#' || it == '\\' }) arg
    else "\"" + arg.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

/** Copies a tree keeping symlinks as symlinks (libmpv.so.2 -> libmpv.so.2.5.0). */
fun copyLinuxTree(from: File, to: File) {
    val source = from.toPath()
    Files.walk(source).use { paths ->
        paths.forEach { path ->
            val target = to.toPath().resolve(source.relativize(path).toString())
            when {
                Files.isSymbolicLink(path) -> Files.createSymbolicLink(target, Files.readSymbolicLink(path))
                Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) -> Files.createDirectories(target)
                else -> Files.copy(path, target, StandardCopyOption.COPY_ATTRIBUTES)
            }
        }
    }
}

/** The podman prefix every container run shares: rootless, no network, own uid, no relabel. */
fun linuxPodman(image: String, mounts: List<Pair<File, String>>, workDir: File, env: Map<String, String> = emptyMap()) =
    buildList {
        addAll(listOf("podman", "run", "--rm", "--network=none", "--userns=keep-id", "--security-opt", "label=disable"))
        mounts.forEach { (host, spec) -> add("--volume"); add("${host.absolutePath}:$spec") }
        env.forEach { (key, value) -> add("--env"); add("$key=$value") }
        add("--workdir"); add(workDir.absolutePath)
        add(image)
    }

// Every class the app's jars mark @Serializable, and its Companion, open to the reflection
// kotlinx.serialization uses to find a serializer: an object's INSTANCE and serializer(), or a
// Companion's serializer(). Upstream's generator covers only classes with an X$$serializer, so
// objects (navigation routes such as TabsRoute), sealed bases and enums relied on a trace reaching
// them; a native build crashed right after sign-in when none had (2026-10-18).
val generateLinuxSerializationMetadata = tasks.register("generateLinuxSerializationMetadata") {
    group = "native image"
    description = "Registers every @Serializable class of the app's jars for kotlinx.serialization's reflection."
    notCompatibleWithConfigurationCache("Reads the jpackage image's jars.")
    enabled = linuxNativeHost
    dependsOn("createDistributable")
    val jars = linuxJvmImageDir.map { image -> fileTree(image.dir("lib/app")) { include("*.jar") } }
    inputs.files(jars)
    outputs.dir(linuxSerializationMetadataDir)
    doLast {
        val marker = "Lkotlinx/serialization/Serializable;".toByteArray(Charsets.US_ASCII)
        fun ByteArray.contains(needle: ByteArray): Boolean =
            (0..size - needle.size).any { start -> needle.indices.all { this[start + it] == needle[it] } }
        val classes = sortedSetOf<String>()
        val names = HashSet<String>()
        jars.get().files.forEach { jar ->
            java.util.zip.ZipFile(jar).use { zip ->
                zip.entries().asSequence()
                    .filter { it.name.startsWith("com/nuvio/") && it.name.endsWith(".class") }
                    .forEach { entry ->
                        val name = entry.name.removeSuffix(".class").replace('/', '.')
                        names += name
                        if (!name.endsWith("\$\$serializer") && zip.getInputStream(entry).use { it.readBytes() }.contains(marker)) {
                            classes += name
                        }
                    }
            }
        }
        val types = classes.flatMap { listOf(it, "$it\$Companion") }.filter { it in names }.toSortedSet()
        val entries = types.joinToString(",\n") { type ->
            "    {\"type\": \"$type\", \"allDeclaredConstructors\": true, \"allDeclaredMethods\": true, " +
                "\"allDeclaredFields\": true, \"allPublicMethods\": true}"
        }
        val out = linuxSerializationMetadataDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        // UTF-8 without a BOM (native-image refuses one).
        out.resolve("reachability-metadata.json").writeText("{\n  \"reflection\": [\n$entries\n  ]\n}\n", Charsets.UTF_8)
        logger.lifecycle("Linux serialization metadata: ${classes.size} @Serializable classes, ${types.size} types -> $out")
    }
}

val linuxNativeCompile = tasks.register<Exec>("linuxNativeCompile") {
    group = "native image"
    description = "Compiles createDistributable's jars into the native Linux executable with GraalVM native-image."
    notCompatibleWithConfigurationCache("Runs host-local GraalVM native-image, optionally in a container.")
    enabled = linuxNativeHost
    dependsOn("createDistributable", "generateNativeMetadata", generateLinuxSerializationMetadata)
    inputs.dir(linuxNativeInputsDir)
    inputs.dir(linuxNativeUpstreamInputsDir)
    inputs.dir(linuxNativeGeneratedDir)
    inputs.dir(linuxSerializationMetadataDir)
    inputs.files(linuxJvmImageDir.map { image -> fileTree(image.dir("lib/app")) { include("*.jar", "nuvio.cfg") } })
    inputs.property("march", linuxNativeMarch)
    inputs.property("qa", linuxNativeQa)
    inputs.property("container", linuxNativeContainer ?: "")
    outputs.dir(linuxNativeWorkDir)
    isIgnoreExitValue = true
    doFirst {
        val graal = linuxGraalVmHome()
        val work = linuxNativeWorkDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val appDir = linuxJvmImageDir.get().asFile.resolve("lib/app")
        val cfg = readLinuxJvmImageConfig(appDir)
        val version = cfg.javaOptions.firstOrNull { it.startsWith("-Djpackage.app-version=") }
            ?.substringAfter('=') ?: throw GradleException("nuvio.cfg has no -Djpackage.app-version")
        // The Linux trace and topics first, then upstream's trace and topics. Upstream's agent/ was
        // recorded on Windows, but signed in: it holds the app's own reflection (navigation route
        // serializers, ...) that a signed-out Linux trace never reaches. Its Windows-only JDK
        // classes are absent from the Linux image and native-image skips them with a warning.
        val linuxRoot = linuxNativeInputsDir.asFile
        val upstreamRoot = linuxNativeUpstreamInputsDir.asFile
        fun topics(root: File) = root.listFiles { file -> file.isDirectory && file.name != "agent" }.orEmpty().sortedBy { it.name }
        val metadataDirs = listOf(linuxRoot.resolve("agent"), upstreamRoot.resolve("agent")).filter { it.isDirectory } +
            topics(linuxRoot) + topics(upstreamRoot) + linuxNativeGeneratedDir.get().asFile +
            linuxSerializationMetadataDir.get().asFile
        val args = buildList {
            add("-J-Xmx$linuxNativeBuilderHeap")
            add("-H:ConfigurationFileDirectories=" + metadataDirs.joinToString(",") { it.absolutePath })
            add("-H:+ReportExceptionStackTraces")
            add("-H:+UnlockExperimentalVMOptions")
            // build-artifacts.json and embedded-resources.json beside the executable: the Linux
            // packages keep them as evidence of what the image contains (tools/linux/packaging).
            add("-H:+GenerateBuildArtifactsFile")
            add("-H:+GenerateEmbeddedResourcesFile")
            // Build-time -D values; NativeImageSupport re-applies nuvio.cfg's at run time.
            add("-Djava.awt.headless=false")
            add("-Dskiko.renderApi=OPENGL")
            add("-Dcompose.application.configure.swing.globals=true")
            add("-Djpackage.app-version=$version")
            add("-march=$linuxNativeMarch")
            // As on Windows: java.net.URL http(s) is off by default in an image.
            add("--enable-url-protocols=http,https")
            if (linuxNativeQa) {
                add("--exact-reachability-metadata")
                // A run-time option in GraalVM CE 25.0.2 ("Could not find option" with upstream's
                // -H: spelling); -R: makes Warn the image's default.
                add("-R:MissingRegistrationReportingMode=Warn")
            }
            // The JVM build's -Xmx1g and upstream's measured young-generation size.
            add("-R:MaxHeapSize=1g")
            add("-R:MaxNewSize=192m")
            add("-o")
            add(work.resolve("nuvio").absolutePath)
            add("-cp")
            add(cfg.classpath.joinToString(":"))
            // --add-opens only take effect at build time; macOS and Windows packages do not exist here.
            addAll(
                cfg.javaOptions.filter { it.startsWith("--add-opens=") || it.startsWith("--add-exports=") }
                    .filterNot { "lwawt" in it || "sun.awt.windows" in it },
            )
            add(cfg.mainClass)
        }
        val argFile = work.resolve("ni-args.txt").apply { writeText(args.joinToString("\n") { linuxArgLine(it) } + "\n") }
        val log = work.resolve("build.log")
        standardOutput = log.outputStream()
        errorOutput = standardOutput
        environment.keys.removeAll(listOf("DISPLAY", "WAYLAND_DISPLAY", "NUVIO_RUN_LIVE_DISPLAY_TESTS"))
        val nativeImage = graal.resolve("bin/native-image").absolutePath
        if (linuxNativeContainer == null) {
            commandLine(nativeImage, "@${argFile.absolutePath}")
        } else {
            val home = work.resolve("home").apply { mkdirs() }
            val root = rootProject.projectDir
            commandLine(
                linuxPodman(
                    linuxNativeContainer,
                    listOf(root to root.absolutePath, graal to "${graal.absolutePath}:ro"),
                    work,
                    mapOf("HOME" to home.absolutePath, "LC_ALL" to "C.UTF-8"),
                ) + listOf(nativeImage, "@${argFile.absolutePath}"),
            )
        }
        logger.lifecycle(
            "native-image: GraalVM ${graal.name}, -march=$linuxNativeMarch${if (linuxNativeQa) ", QA (reports missing registrations)" else ""}, " +
                "${metadataDirs.size} metadata dirs, ${linuxNativeContainer?.let { "in $it" } ?: "on this host"}; log in $log",
        )
    }
    doLast {
        val work = linuxNativeWorkDir.get().asFile
        val log = work.resolve("build.log")
        if (executionResult.get().exitValue != 0 || !work.resolve("nuvio").isFile) {
            log.takeIf { it.isFile }?.readLines()?.takeLast(40)?.forEach { logger.error(it) }
            throw GradleException("native-image failed; full log: $log")
        }
        log.readLines().filter { "registered for" in it || "Finished generating" in it }.forEach { logger.lifecycle(it.trim()) }
    }
}

val createLinuxNativeDistributable = tasks.register("createLinuxNativeDistributable") {
    group = "native image"
    description = "Lays out the native Linux executable like the packages: bin/nuvio, lib/nuvio, lib/app, lib/runtime, lib/native."
    notCompatibleWithConfigurationCache("Copies host-local GraalVM and media runtime files.")
    enabled = linuxNativeHost
    dependsOn(linuxNativeCompile)
    inputs.dir(linuxNativeWorkDir)
    inputs.files(linuxJvmImageDir.map { image -> fileTree(image.dir("lib")) { exclude("app/*.jar", "runtime/**") } })
    inputs.property("qa", linuxNativeQa)
    inputs.property("nativeRuntimeDir", linuxNativeRuntimeDir ?: "")
    linuxNativeRuntimeDir?.let { inputs.dir(it) }
    outputs.dir(linuxNativeDistDir)
    doLast {
        val jvmLib = linuxJvmImageDir.get().asFile.resolve("lib")
        val work = linuxNativeWorkDir.get().asFile
        val dist = linuxNativeDistDir.get().asFile
        val exe = dist.resolve("lib/nuvio")
        val running = ProcessHandle.allProcesses().anyMatch { process ->
            process.info().command().map { File(it).absoluteFile.normalize() == exe.absoluteFile.normalize() }.orElse(false)
        }
        if (running) throw GradleException("Nuvio is running from $dist; quit it first (this task replaces that folder).")
        dist.deleteRecursively()
        listOf("bin", "lib/app", "lib/runtime/lib").forEach { dist.resolve(it).mkdirs() }
        // lib/ beside the exe, as in the JVM image, minus the JVM launcher's library.
        jvmLib.listFiles().orEmpty().filter { it.isFile && it.name != "libapplauncher.so" }
            .forEach { it.copyTo(dist.resolve("lib/${it.name}")) }
        // lib/app without the jars (compiled in), class archives or the JVM preload list.
        jvmLib.resolve("app").listFiles().orEmpty()
            .filterNot { it.extension == "jar" || it.extension == "jsa" || it.name == "startup-classes.lst" }
            .forEach { it.copyRecursively(dist.resolve("lib/app/${it.name}")) }
        work.resolve("nuvio").copyTo(exe).setExecutable(true, false)
        // The JDK libraries native-image emits load from the exe's directory.
        work.listFiles { file -> file.extension == "so" }.orEmpty().forEach { it.copyTo(dist.resolve("lib/${it.name}")) }
        // Not emitted: skiko and libplayer_bridge.so open libjawt.so from java.home (= lib/runtime),
        // and AWT's font manager reads its properties there. libjawt.so resolves libawt.so by
        // soname, i.e. the copy already loaded from lib/ (checked by the "linux jdk libraries" probe).
        linuxGraalVmHome().resolve("lib").listFiles { file ->
            file.isFile && (file.name == "libjawt.so" || file.name.startsWith("fontconfig") || file.name.startsWith("psfont"))
        }.orEmpty().forEach { it.copyTo(dist.resolve("lib/runtime/lib/${it.name}")) }
        linuxNativeRuntimeDir?.let { runtime ->
            val source = File(runtime)
            if (!source.resolve("libplayer_bridge.so").isFile) throw GradleException("$source has no libplayer_bridge.so")
            copyLinuxTree(source, dist.resolve("lib/native"))
        } ?: logger.warn("No -Pnuvio.linux.nativeRuntimeDir: lib/native is empty, so in-app playback is unavailable.")
        // The package entry point stays bin/nuvio; packages put their runtime wrapper beside it.
        dist.resolve("bin/nuvio").apply {
            writeText(
                "#!/bin/sh\n" +
                    "# Nuvio native image: the executable is lib/nuvio (NativeImageSupport expects lib/app and lib/runtime beside it).\n" +
                    "here=\$(dirname -- \"\$(readlink -f -- \"\$0\")\")\n" +
                    "exec \"\$here/../lib/nuvio\" \"\$@\"\n",
            )
            setExecutable(true, false)
        }
        if (linuxNativeQa) {
            dist.resolve("lib/native-qa").writeText("QA build: reports missing native-image registrations. Never ship.\n")
            logger.lifecycle("QA build: missing registrations are reported on the console (and in lib/native-qa-console.log).")
        }
        val totalMb = dist.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
        logger.lifecycle("Native image laid out in $dist ($totalMb MB, exe ${exe.length() / (1024 * 1024)} MB)")
    }
}

fun requireLinuxSmokeImage(): String = linuxSmokeImage ?: throw GradleException(
    "Pass -Pnuvio.linux.smokeImage=<image>: GUI runs happen only in a throwaway container with a private " +
        "Xvfb and D-Bus (see native-image-linux/README.md), never on the developer's desktop.",
)

val linuxNativeSmoke = tasks.register<Exec>("linuxNativeSmoke") {
    group = "native image"
    description = "Launches the native Linux layout with its self-test in a container and fails on any native failure."
    notCompatibleWithConfigurationCache("Launches the host-local native image in a container.")
    enabled = linuxNativeHost
    dependsOn(createLinuxNativeDistributable)
    doFirst {
        val dist = linuxNativeDistDir.get().asFile
        val out = layout.buildDirectory.dir("native-image-linux/smoke").get().asFile.apply { deleteRecursively(); mkdirs() }
        val script = linuxNativeSmokeScript.asFile
        val runs = providers.gradleProperty("nuvio.native.smokeRuns").orNull ?: "3"
        commandLine(
            linuxPodman(
                requireLinuxSmokeImage(),
                listOf(dist to dist.absolutePath, out to out.absolutePath, script.parentFile to "${script.parentFile.absolutePath}:ro"),
                out,
            ) + listOf("python3", "-I", script.absolutePath, "smoke", "--runs", runs, "--out", out.absolutePath,
                "--", dist.resolve("bin/nuvio").absolutePath),
        )
    }
}

tasks.register<Exec>("linuxNativeImageTrace") {
    group = "native image"
    description = "Traces the JVM app on GraalVM's java with the agent in a container (merges into native-image-linux/agent)."
    notCompatibleWithConfigurationCache("Launches the host-local app in a container for a tracing session.")
    enabled = linuxNativeHost
    dependsOn("createDistributable")
    doFirst {
        val graal = linuxGraalVmHome()
        val appDir = linuxJvmImageDir.get().asFile.resolve("lib/app")
        val runtime = linuxNativeRuntimeDir?.let(::File)
            ?: throw GradleException("Pass -Pnuvio.linux.nativeRuntimeDir=<dir with libplayer_bridge.so> so playback is traced too.")
        val agentDir = linuxNativeInputsDir.asFile.resolve("agent").apply { mkdirs() }
        val out = layout.buildDirectory.dir("native-image-linux/trace").get().asFile.apply { deleteRecursively(); mkdirs() }
        val script = linuxNativeSmokeScript.asFile
        // The packaged layout inside the container: java.home = lib/runtime (GraalVM), so the app
        // finds lib/native and lib/app exactly where the packages put them.
        val image = "/nuvio-trace"
        val cfg = readLinuxJvmImageConfig(appDir)
        val command = buildList {
            add("$image/lib/runtime/bin/java")
            add("-agentlib:native-image-agent=config-merge-dir=${agentDir.absolutePath},config-write-period-secs=30,config-write-initial-delay-secs=20")
            addAll(cfg.javaOptions.map { it.replace(appDir.absolutePath, "$image/lib/app") }.filterNot { "SharedArchive" in it })
            add("-Djava.library.path=$image/lib/app")
            add("-Djpackage.app-path=$image/lib/nuvio")
            add("-Dnuvio.nativeImageTracing=1")
            add("-cp")
            add(cfg.classpath.joinToString(":") { it.replace(appDir.absolutePath, "$image/lib/app") })
            add(cfg.mainClass)
        }
        val seconds = providers.gradleProperty("nuvio.trace.seconds").orNull ?: "90"
        commandLine(
            linuxPodman(
                requireLinuxSmokeImage(),
                listOf(
                    graal to "$image/lib/runtime:ro", appDir to "$image/lib/app:ro", runtime to "$image/lib/native:ro",
                    agentDir to agentDir.absolutePath, out to out.absolutePath,
                    script.parentFile to "${script.parentFile.absolutePath}:ro",
                ),
                out,
            ) + listOf("python3", "-I", script.absolutePath, "trace", "--linger", seconds, "--out", out.absolutePath, "--") + command,
        )
        logger.lifecycle("Tracing Nuvio on GraalVM (self-test + ${seconds}s idle); agent output merges into $agentDir.")
    }
}
