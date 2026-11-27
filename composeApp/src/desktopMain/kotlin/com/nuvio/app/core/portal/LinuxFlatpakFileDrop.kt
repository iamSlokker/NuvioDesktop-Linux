package com.nuvio.app.core.portal

import co.touchlab.kermit.Logger
import com.nuvio.app.core.i18n.localizedString
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.LocalFileDrop
import com.nuvio.app.features.player.VIDEO_EXTENSIONS
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DropTargetDragEvent
import java.io.File
import java.io.InputStream
import nuvio.composeapp.generated.resources.*

/*
 * A file dragged in from the host's file manager arrives in the sandbox as a path that does not exist
 * here. File managers also offer the same drag through the FileTransfer portal: a key in the data
 * types below, which the portal redeems for /run/user/<uid>/doc paths this app can open. AWT maps an
 * unknown X11 data type to a DataFlavor of the same MIME type, read as a stream.
 */
internal object LinuxFlatpakFileDrop {
    private val log = Logger.withTag("FlatpakFileDrop")
    private val transferMimeTypes = listOf("application/vnd.portal.filetransfer", "application/vnd.portal.files")

    /** Hover check beside the plain file list: some sources offer only the portal's type. */
    fun canAccept(event: DropTargetDragEvent): Boolean = canAccept(event.currentDataFlavors)

    fun canAccept(flavors: Array<DataFlavor>): Boolean =
        LinuxFileChooserPortal.applies && portalFlavor(flavors) != null

    /**
     * Handles a drop in the Flatpak. Files the sandbox can already see go straight through, and
     * anything else is redeemed through the portal off the UI thread, or explained if it cannot be.
     * Returns whether the drop was taken.
     */
    fun handle(transferable: Transferable): Boolean =
        handle(transferable, { playableDropPaths(it) }) { it.forEach(LocalFileDrop::emit) }

    /**
     * [handle] for any drop target: [keep] picks the paths the target takes from those dropped, and
     * [deliver] gets them, on the drop's thread when the sandbox can already see them and on the
     * portal's worker thread when they had to be redeemed.
     */
    fun handle(transferable: Transferable, keep: (List<String>) -> List<String>, deliver: (List<String>) -> Unit): Boolean {
        val visible = runCatching {
            if (transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @Suppress("UNCHECKED_CAST")
                keep((transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>).map(File::getPath))
            } else {
                emptyList()
            }
        }.getOrDefault(emptyList())
        if (visible.isNotEmpty()) {
            deliver(visible)
            return true
        }
        // The key has to be read while the drop is in progress; redeeming it does not.
        val key = runCatching {
            portalFlavor(transferable.transferDataFlavors)?.let { flavor ->
                (transferable.getTransferData(flavor) as? InputStream)?.use { transferKey(it.readNBytes(MaxKeyBytes)) }
            }
        }.onFailure { log.w(it) { "Could not read the drop's transfer key" } }.getOrNull()
        if (key.isNullOrEmpty()) {
            tellNotShared()
            return false
        }
        Thread({ redeem(key, keep, deliver) }, "nuvio-file-drop-portal").apply { isDaemon = true }.start()
        return true
    }

    private fun redeem(key: String, keep: (List<String>) -> List<String>, deliver: (List<String>) -> Unit) {
        val paths = runCatching {
            NativePlayerBridge.ensureNativeLibraryLoaded()
            transferredFiles(LinuxFileChooserPortalNative.retrieveFiles(key, RetrieveTimeoutMs))
        }.onFailure { log.w(it) { "File transfer portal failed" } }.getOrNull()
        val kept = paths?.let(keep).orEmpty()
        if (kept.isEmpty()) tellNotShared() else deliver(kept)
    }

    private fun tellNotShared() {
        NuvioToastController.show(localizedString(Res.string.linux_portal_drop_not_shared), durationMillis = 7000L)
    }

    private fun portalFlavor(flavors: Array<DataFlavor>): DataFlavor? = flavors.firstOrNull { flavor ->
        InputStream::class.java.isAssignableFrom(flavor.representationClass) &&
            transferMimeTypes.any(flavor::isMimeTypeEqual)
    }
}

/** The drag source writes the key as text, GTK's with a trailing NUL. */
internal fun transferKey(bytes: ByteArray): String = bytes.toString(Charsets.UTF_8).trim { it == '\u0000' || it.isWhitespace() }

/** `[1 or 0 for success, message, path...]` to the transferred paths, or null when it failed. */
internal fun transferredFiles(raw: Array<String>): List<String>? =
    if (raw.getOrNull(0) == "1") raw.drop(2) else null

internal fun playableDropPaths(paths: List<String>, isFile: (String) -> Boolean = { File(it).isFile }): List<String> =
    paths.filter { path -> isFile(path) && File(path).extension.lowercase() in VIDEO_EXTENSIONS }

private const val MaxKeyBytes = 4096
private const val RetrieveTimeoutMs = 15_000
