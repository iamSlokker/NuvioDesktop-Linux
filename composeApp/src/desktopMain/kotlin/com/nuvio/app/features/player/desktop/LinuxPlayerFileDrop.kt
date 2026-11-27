package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.core.portal.LinuxFileChooserPortal
import com.nuvio.app.core.portal.LinuxFlatpakFileDrop
import java.awt.Canvas
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.swing.SwingUtilities

/*
 * fork:linux - files dropped on the Linux player. The HUD that takes a drop on Windows is, on Linux, an
 * override-redirect window that is unmapped whenever Nuvio's window is not the focused one, and a drag
 * from a file manager always starts with the file manager focused: the drop lands on the video's
 * Canvas, which had no drop target, and nothing happened. The Canvas now takes it: the paths come from
 * AWT's file list, or in the Flatpak through the FileTransfer portal as for the app's own drop target,
 * and go to the player's handler for the HUD's drop (subtitles load, anything else is explained).
 */
internal class LinuxPlayerFileDrop private constructor(
    private val host: Canvas,
    private val onPaths: (List<String>) -> Unit,
) {
    private val target = DropTarget(host, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun dragEnter(event: DropTargetDragEvent) = offer(event)
        override fun dragOver(event: DropTargetDragEvent) = offer(event)
        override fun drop(event: DropTargetDropEvent) {
            if (!takesFiles(event.currentDataFlavors)) {
                event.rejectDrop()
                return
            }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val taken = runCatching { take(event.transferable) }
                .onFailure { log.w(it) { "Could not read the files dropped on the player" } }
                .getOrDefault(false)
            event.dropComplete(taken)
        }
    }, true)

    private fun offer(event: DropTargetDragEvent) {
        if (takesFiles(event.currentDataFlavors)) event.acceptDrag(DnDConstants.ACTION_COPY) else event.rejectDrag()
    }

    private fun takesFiles(flavors: Array<DataFlavor>): Boolean =
        flavors.any { it == DataFlavor.javaFileListFlavor } || LinuxFlatpakFileDrop.canAccept(flavors)

    /** The drop's thread is the EDT; a portal redeem answers on its worker, so it is handed back. */
    private fun take(transferable: Transferable): Boolean {
        if (LinuxFileChooserPortal.applies) {
            return LinuxFlatpakFileDrop.handle(transferable, { existingDropPaths(it) }) { paths ->
                if (SwingUtilities.isEventDispatchThread()) onPaths(paths) else SwingUtilities.invokeLater { onPaths(paths) }
            }
        }
        if (!transferable.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) return false
        @Suppress("UNCHECKED_CAST")
        val paths = existingDropPaths((transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>).map(File::getPath))
        if (paths.isNotEmpty()) onPaths(paths)
        return paths.isNotEmpty()
    }

    fun close() {
        target.isActive = false
        if (host.dropTarget === target) host.dropTarget = null
    }

    companion object {
        private val log = Logger.withTag("PlayerDroppedFiles")

        /** Only the Linux main player; a hero trailer or the music surface takes no drop. */
        fun install(os: DesktopHostOs, mainPlayer: Boolean, host: Canvas, onPaths: (List<String>) -> Unit): LinuxPlayerFileDrop? =
            if (os == DesktopHostOs.LINUX && mainPlayer) LinuxPlayerFileDrop(host, onPaths) else null
    }
}

/** The dropped paths that are files here; which of them are subtitles is the player's call. */
internal fun existingDropPaths(paths: List<String>, isFile: (String) -> Boolean = { File(it).isFile }): List<String> =
    paths.filter(isFile)
