package com.nuvio.app.features.discover

import com.nuvio.app.core.portal.LinuxFileChooserPortal
import com.nuvio.app.core.portal.PortalFileFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.filechooser.FileNameExtensionFilter
import nuvio.composeapp.generated.resources.*
import com.nuvio.app.core.i18n.localizedString

private val PortalJsonFilter = PortalFileFilter("Discover catalog (*.json)", listOf("*.json"))

internal actual object DiscoverCatalogFilePicker {

    actual suspend fun openForImport(): String? = withContext(Dispatchers.IO) {
        if (LinuxFileChooserPortal.applies) {
            return@withContext LinuxFileChooserPortal
                .openFile(localizedString(Res.string.discover_export_import_discover_row), PortalJsonFilter)
                ?.let { File(it).readText() }
        }
        val chosen = chooseFile(save = false, suggestedName = null) ?: return@withContext null
        chosen.readText()
    }

    actual suspend fun saveExport(suggestedName: String, contents: String): String? =
        withContext(Dispatchers.IO) {
            if (LinuxFileChooserPortal.applies) {
                val path = LinuxFileChooserPortal.saveFile(
                    title = localizedString(Res.string.discover_export_export_discover_row),
                    suggestedName = if (File(suggestedName).extension.isNotBlank()) suggestedName else "$suggestedName.json",
                    filter = PortalJsonFilter,
                ) ?: return@withContext null
                // Exactly as returned; the document portal only keeps the name the user picked.
                return@withContext File(path).also { it.writeText(contents) }.name
            }
            val chosen = chooseFile(save = true, suggestedName = suggestedName)
                ?: return@withContext null
            // Appended rather than enforced in the dialog: a user who types their own extension
            // meant it, and Swing's chooser does not add one for them.
            val target = if (chosen.extension.isNotBlank()) chosen else File("${chosen.path}.json")
            target.writeText(contents)
            target.name
        }

    private fun chooseFile(save: Boolean, suggestedName: String?): File? {
        val holder = arrayOfNulls<File>(1)
        val run = Runnable {
            runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
            val chooser = JFileChooser().apply {
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                dialogTitle = if (save) localizedString(Res.string.discover_export_export_discover_row) else localizedString(Res.string.discover_export_import_discover_row)
                fileFilter = FileNameExtensionFilter("Discover catalog (*.json)", "json")
                suggestedName?.let { selectedFile = File(it) }
            }
            val result = if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)
            if (result == JFileChooser.APPROVE_OPTION) holder[0] = chooser.selectedFile
        }
        if (SwingUtilities.isEventDispatchThread()) run.run() else SwingUtilities.invokeAndWait(run)
        return holder[0]
    }
}
