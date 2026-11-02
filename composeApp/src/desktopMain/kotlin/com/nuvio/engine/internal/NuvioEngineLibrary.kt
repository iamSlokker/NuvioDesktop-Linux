package com.nuvio.engine.internal

import java.io.File

/**
 * Locates and loads nuvio_engine.dll. Resolution mirrors player_bridge.dll's: the packaged app
 * image (the directory above `java.home`) first, then the Gradle build output for `gradlew run`.
 * An explicit `-Dnuvio.engine.library=<abs path>` / `NUVIO_ENGINE_LIBRARY` wins over both.
 *
 * [isAvailable] is the cheap pre-check the P2P engine uses to decide between this backend and
 * TorrServer without triggering a load; [load] is what [NativeBridge]'s initializer calls.
 */
internal object NuvioEngineLibrary {
    private val isLinux = System.getProperty("os.name").orEmpty().lowercase().contains("linux")

    val LIBRARY_FILE_NAME: String = if (isLinux) "libnuvio_engine.so" else "nuvio_engine.dll"

    @Volatile
    private var loadedFrom: File? = null

    /** The file that was, or would be, loaded; null when no candidate exists on disk. */
    fun resolve(): File? {
        loadedFrom?.let { return it }
        configuredPath()?.let { return it.takeIf(File::isFile) }
        return candidates().firstOrNull(File::isFile)
    }

    val isAvailable: Boolean
        get() = (isLinux || System.getProperty("os.name").orEmpty().lowercase().contains("win")) && resolve() != null

    @Synchronized
    fun load() {
        if (loadedFrom != null) return
        val library = resolve()
            ?: throw UnsatisfiedLinkError(
                "$LIBRARY_FILE_NAME was not found beside the app runtime or under build/native/windows; " +
                    "run :composeApp:buildWindowsNuvioEngine or pass -Dnuvio.engine.library=<path>",
            )
        System.load(library.absolutePath)
        loadedFrom = library
    }

    private fun configuredPath(): File? =
        (System.getProperty("nuvio.engine.library")?.takeIf { it.isNotBlank() }
            ?: System.getenv("NUVIO_ENGINE_LIBRARY")?.takeIf { it.isNotBlank() })
            ?.let(::File)

    private fun candidates(): List<File> {
        if (isLinux) return linuxCandidates()
        val packaged = System.getProperty("java.home")
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.parentFile
            ?.resolve(LIBRARY_FILE_NAME)
        if (!com.nuvio.app.core.build.DesktopDevRun.allowsLocalNativeBuilds) return listOfNotNull(packaged)
        return listOfNotNull(
            packaged,
            File("composeApp/build/native/windows/$LIBRARY_FILE_NAME"),
            File("build/native/windows/$LIBRARY_FILE_NAME"),
        )
    }

    /** The packages keep it in lib/native, beside lib/nuvio; a source run in the Gradle build output. */
    private fun linuxCandidates(): List<File> {
        val packaged = System.getProperty("jpackage.app-path")
            ?.takeIf { it.isNotBlank() }
            ?.let { File(it).parentFile?.resolve("native/$LIBRARY_FILE_NAME") }
        if (!com.nuvio.app.core.build.DesktopDevRun.allowsLocalNativeBuilds) return listOfNotNull(packaged)
        return listOfNotNull(
            packaged,
            File("composeApp/build/native/linux/$LIBRARY_FILE_NAME"),
            File("build/native/linux/$LIBRARY_FILE_NAME"),
        )
    }
}
