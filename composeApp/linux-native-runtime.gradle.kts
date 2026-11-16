// ---------------------------------------------------------------------------------------------
// The Linux native player runtime inside a local jpackage app image.
//
// A packaged Linux layout is bin/nuvio, lib/app, lib/runtime and lib/native, and
// NativePlayerBridge finds the player in lib/native because java.home is lib/runtime
// (findPackagedNativeRuntime). tools/linux/packaging/stage.py fills that folder for the released
// DEB, Flatpak and AppImage, but the Gradle app-image tasks filled nothing, so an image built here
// -- the development AppImage from appimage-linux.gradle.kts, or packageReleaseDeb -- reported
// "Linux native player bridge is unavailable." while `gradlew run` played fine: a source run loads
// build/native/linux/libplayer_bridge.so through findLocalBuildLibrary(), which a packaged app is
// deliberately not allowed to do (DesktopDevRun).
//
// So the local bridge build is staged into the image: the bridge, the libmpv the linker gave it
// (read back from CMake, so the image carries the same shared object the bridge records as NEEDED)
// and the patched libquickjs.so the packages ship beside them. Everything they depend on stays the
// host's -- WebKitGTK, GTK, X11 and libmpv's own closure -- which is the trade the development
// AppImage already makes (tools/linux/packaging/dev_appimage.py). It never touches the release
// path, which builds lib/native from the pinned media runtime in stage.py.
//
// Kept in this file so upstream merges meet only the one-line apply() in build.gradle.kts, as with
// native-image-linux.gradle.kts and appimage-linux.gradle.kts.
// ---------------------------------------------------------------------------------------------
import java.nio.file.Files
import java.nio.file.StandardCopyOption

val linuxNativeRuntimeHost = System.getProperty("os.name").contains("linux", ignoreCase = true)
val linuxPlayerBridgeDir = layout.buildDirectory.dir("native/linux")
val linuxPlayerBridgeLibrary = linuxPlayerBridgeDir.map { it.file("libplayer_bridge.so") }
val linuxQuickJsLibrary = linuxPlayerBridgeDir.map { it.file("libquickjs.so") }

/** Written by src/desktopMain/native/linux/CMakeLists.txt: the libmpv the bridge links against. */
val linuxMpvLibraryIndex = linuxPlayerBridgeDir.map { it.file("mpv-library.txt") }

/** The libmpv the linker recorded as NEEDED, as resolved by CMake's MPV_ROOT / system search. */
fun linuxLinkedMpvLibrary(): File {
    val index = linuxMpvLibraryIndex.get().asFile
    val path = index.takeIf(File::isFile)?.readText()?.trim().orEmpty()
    check(path.isNotEmpty()) {
        "${index.name} is missing from ${index.parentFile}: run :composeApp:buildLinuxPlayerBridge " +
            "so the app image can carry the libmpv the bridge links against."
    }
    return File(path).also { library ->
        check(library.isFile) { "The libmpv the bridge was linked against is gone: $library" }
    }
}

/** Copies a shared object into the image, keeping its mode and timestamps. */
fun stageLinuxNativeLibrary(source: File, target: File) {
    Files.copy(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES,
    )
}

if (linuxNativeRuntimeHost) {
    mapOf(
        "createDistributable" to "main",
        "createReleaseDistributable" to "main-release",
    ).forEach { (taskName, imageFlavor) ->
        tasks.matching { it.name == taskName }.configureEach {
            // An image without the bridge starts up and plays nothing, so build it rather than
            // leaving a runtime hole for the launcher to discover.
            dependsOn("buildLinuxPlayerBridge")
            // Staged here and nowhere else: a rebuilt bridge, or a libmpv that moved, has to
            // invalidate an otherwise up-to-date app image.
            inputs.files(linuxPlayerBridgeLibrary, linuxQuickJsLibrary, linuxMpvLibraryIndex)
            doLast {
                val imageDir = layout.buildDirectory.dir("compose/binaries/$imageFlavor/app/nuvio").get().asFile
                check(imageDir.resolve("bin/nuvio").isFile) { "Linux app image was not created at $imageDir" }
                val native = imageDir.resolve("lib/native").apply { mkdirs() }
                val bridge = linuxPlayerBridgeLibrary.get().asFile
                check(bridge.isFile) { "The Linux player bridge was not built at $bridge" }
                stageLinuxNativeLibrary(bridge, native.resolve(bridge.name))
                val quickJs = linuxQuickJsLibrary.get().asFile
                if (quickJs.isFile) {
                    stageLinuxNativeLibrary(quickJs, native.resolve(quickJs.name))
                } else {
                    logger.warn("libquickjs.so was not built; plugins fall back to quickjs-kt's bundled copy.")
                }
                // Under the soname the bridge's NEEDED entry asks for, so the image's own copy is
                // the one that loads (the same name the packages use for it).
                stageLinuxNativeLibrary(linuxLinkedMpvLibrary(), native.resolve("libmpv.so.2"))
                val missing = listOf("libplayer_bridge.so", "libmpv.so.2").filterNot { native.resolve(it).isFile }
                check(missing.isEmpty()) { "Linux app image is missing native runtime files: ${missing.joinToString()}" }
                logger.lifecycle("Linux native player runtime staged in $native")
            }
        }
    }
}
