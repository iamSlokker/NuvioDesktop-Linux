package com.nuvio.app.features.plugins

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.DesktopDevRun
import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.app.features.player.desktop.findPackagedNativeRuntime
import java.io.File

/**
 * Linux half of [QuickJsNativeLibrary]: points quickjs-kt at the patched `libquickjs.so` the Linux
 * packages ship in `lib/native` beside `libplayer_bridge.so`, built by the `quickjs` target of
 * src/desktopMain/native/linux/CMakeLists.txt from the same vendored sources as the Windows DLL.
 * The jar's own copy leaks each binding call's JNI local references until the job ends, and in
 * the native image a freed slot is not reused before then either. A property already set wins;
 * without a candidate the jar's copy loads as before.
 */
internal object LinuxQuickJsNativeLibrary {
    const val LIBRARY_FILE_NAME = "libquickjs.so"

    fun select() {
        if (DesktopHostOs.current != DesktopHostOs.LINUX) return
        val configured = System.getProperty(QuickJsNativeLibrary.LIBRARY_PATH_PROPERTY)?.takeIf { it.isNotBlank() }
        if (configured != null) {
            Logger.withTag("PluginRuntime").i { "QuickJS library from $configured (preset)" }
            return
        }
        val directory = libraryDirectory()
        if (directory == null) {
            Logger.withTag("PluginRuntime").w { "Patched $LIBRARY_FILE_NAME not found; using quickjs-kt's bundled copy" }
            return
        }
        System.setProperty(QuickJsNativeLibrary.LIBRARY_PATH_PROPERTY, directory.absolutePath)
        Logger.withTag("PluginRuntime").i { "QuickJS library: ${directory.resolve(LIBRARY_FILE_NAME).absolutePath}" }
    }

    internal fun libraryDirectory(
        javaHome: File? = System.getProperty("java.home")?.takeIf { it.isNotBlank() }?.let(::File),
        localBuilds: Boolean = DesktopDevRun.allowsLocalNativeBuilds,
    ): File? {
        findPackagedNativeRuntime(DesktopHostOs.LINUX, javaHome, listOf(LIBRARY_FILE_NAME))?.let { return it }
        if (!localBuilds) return null
        return listOf(File("composeApp/build/native/linux"), File("build/native/linux"))
            .firstOrNull { it.resolve(LIBRARY_FILE_NAME).isFile }
            ?.absoluteFile
    }
}
