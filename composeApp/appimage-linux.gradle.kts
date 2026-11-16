// ---------------------------------------------------------------------------------------------
// Development AppImage, Linux x86_64. Packs the jpackage app image into one .AppImage file for
// local runs: no GraalVM native image, no private media runtime and no WebKit build, so seconds
// instead of the release pipeline's hours. tools/linux/packaging/dev_appimage.py states exactly
// what that costs, and the artifact is named -dev because of it. The release AppImage is a
// different, verified artifact: tools/linux/packaging/build.py --format appimage, in the pinned
// Ubuntu package builder. This file never touches it.
//
// Kept in this file so upstream merges only meet the one-line apply() in build.gradle.kts, as with
// native-image-linux.gradle.kts. The tasks are registered on every host and enabled on Linux only.
//
//   packageReleaseAppImage   the app image createReleaseDistributable builds, which packageReleaseDeb packs
//   packageAppImage          the debug/main app image createDistributable builds
//
// Each task packs into its own build/appimage-linux/<variant> directory and names its artifact for
// the image it wrapped, so the two never overwrite each other.
//
// Properties: -Pnuvio.appimage.python=<python3> (default python3).
// ---------------------------------------------------------------------------------------------
import java.util.Properties

val appImageLinuxHost = System.getProperty("os.name").contains("linux", ignoreCase = true)
val appImageScript = rootProject.layout.projectDirectory.file("tools/linux/packaging/dev_appimage.py")
val appImageVersionFile = layout.projectDirectory.file("Configuration/DesktopVersion.properties")
val appImageIconSource = layout.projectDirectory.file("src/desktopMain/resources/icons/nuvio-app-icon.png")
val appImageWorkDir = layout.buildDirectory.dir("appimage-linux")
val appImagePython = providers.gradleProperty("nuvio.appimage.python").getOrElse("python3")

/** The version the app image was stamped with: the same file the packages read. */
fun appImageVersion(): String =
    Properties().apply { appImageVersionFile.asFile.inputStream().use { load(it) } }
        .getProperty("VERSION_NAME").trim()

fun registerDevAppImage(
    taskName: String,
    distributable: String,
    imageDir: Provider<Directory>,
    variant: String,
    label: String,
) {
    val taskWorkDir = appImageWorkDir.map { it.dir(variant) }
    tasks.register<Exec>(taskName) {
        group = "distribution"
        description = "Packs $label into a single-file .AppImage for local runs (Linux only)."
        enabled = appImageLinuxHost
        dependsOn(distributable)
        notCompatibleWithConfigurationCache("Runs the host's python3 and fetches pinned tools.")
        inputs.dir(imageDir).withPropertyName("appImage")
        inputs.file(appImageScript).withPropertyName("packagingScript")
        inputs.file(appImageIconSource).withPropertyName("iconSource")
        outputs.dir(taskWorkDir).withPropertyName("appImageOutput")
        doFirst {
            val version = appImageVersion()
            val work = taskWorkDir.get().asFile.apply { mkdirs() }
            val output = work.resolve("Nuvio-Desktop-$version-dev-$variant-x86_64.AppImage")
            logger.lifecycle("Packing $label into ${output.name} (development AppImage, not a release artifact)")
            commandLine(
                appImagePython,
                appImageScript.asFile.absolutePath,
                "--app-image", imageDir.get().asFile.absolutePath,
                "--version", version,
                "--work-dir", work.absolutePath,
                "--output", output.absolutePath,
                "--java", File(System.getProperty("java.home"), "bin/java").absolutePath,
                "--icon-source", appImageIconSource.asFile.absolutePath,
            )
        }
        doLast {
            val artifacts = taskWorkDir.get().asFile.listFiles { file -> file.name.endsWith(".AppImage") }
            if (artifacts.isNullOrEmpty()) throw GradleException("No .AppImage was produced in $taskWorkDir")
            artifacts.forEach { logger.lifecycle("Development AppImage: ${it.absolutePath}") }
        }
    }
}

registerDevAppImage(
    taskName = "packageReleaseAppImage",
    distributable = "createReleaseDistributable",
    imageDir = layout.buildDirectory.dir("compose/binaries/main-release/app/nuvio"),
    variant = "release",
    label = "the release app image",
)

registerDevAppImage(
    taskName = "packageAppImage",
    distributable = "createDistributable",
    imageDir = layout.buildDirectory.dir("compose/binaries/main/app/nuvio"),
    variant = "debug",
    label = "the debug app image",
)
