package com.nuvio.app.core.portal

import co.touchlab.kermit.Logger
import com.nuvio.app.core.build.LinuxFlatpakSandbox
import com.nuvio.app.core.i18n.localizedString
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.player.desktop.LinuxAwtViewResolver
import com.nuvio.app.features.player.desktop.NativePlayerBridge
import java.awt.EventQueue
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.Window
import java.io.File
import java.net.URI
import javax.swing.SwingUtilities
import nuvio.composeapp.generated.resources.*

/*
 * Swing's choosers run inside the sandbox, so in the Flatpak they only ever show the sandbox: no
 * ~/Videos, no /run/media. The FileChooser portal runs the desktop's own dialog outside it and grants
 * this app exactly what the user chose, with no filesystem permission.
 *
 * What comes back is usually a document portal path, /run/user/<uid>/doc/<id>/<name>. It is an ordinary
 * read/write FUSE mount: consumers keep using java.io.File and mpv on it unchanged. The portal registers
 * a pick as persistent, so a path stored in settings still works after a restart. Two limits to respect:
 * - A picked FILE's document holds that one name. A file created beside it under any other name is a
 *   throwaway (temp plus atomic rename onto the picked name is fine), so never add an extension to a
 *   path SaveFile returned; it would be silently lost.
 * - The path means nothing outside this sandbox, so it cannot be handed to a host program.
 */

internal data class PortalFileFilter(val name: String, val patterns: List<String>)

internal sealed interface PortalChoice {
    data class Picked(val path: String) : PortalChoice
    data object Cancelled : PortalChoice
    data class Failed(val message: String) : PortalChoice
}

internal data class PortalChooserRequest(
    val save: Boolean = false,
    val directory: Boolean = false,
    val title: String,
    val currentFolder: String? = null,
    val currentName: String? = null,
    val filter: PortalFileFilter? = null,
)

internal object LinuxFileChooserPortalNative {
    /** Returns `[status, uri, message]`; see file_chooser_portal.h. */
    external fun choose(
        save: Boolean,
        directory: Boolean,
        parentDrawable: Long,
        title: String,
        currentFolder: String,
        currentName: String,
        filterName: String,
        filterPatterns: Array<String>,
        timeoutMs: Int,
    ): Array<String>

    /** Returns `[1 or 0 for success, message, path...]`. */
    external fun retrieveFiles(key: String, timeoutMs: Int): Array<String>
}

/** Maps the native `[status, uri, message]` triple. Status follows the portal's Response codes. */
internal fun portalChoice(raw: Array<String>): PortalChoice {
    val message = raw.getOrNull(2).orEmpty()
    return when (raw.getOrNull(0)?.toIntOrNull()) {
        0 -> portalUriToPath(raw.getOrNull(1).orEmpty())?.let(PortalChoice::Picked)
            ?: PortalChoice.Failed("the portal returned an unusable location")
        1 -> PortalChoice.Cancelled
        else -> PortalChoice.Failed(message.ifBlank { "the file chooser portal reported an error" })
    }
}

/** The portal only hands out `file://` URIs, percent-encoded; anything else is not ours to open. */
internal fun portalUriToPath(uri: String): String? = runCatching {
    val parsed = URI(uri)
    if (!parsed.scheme.equals("file", ignoreCase = true)) return@runCatching null
    File(parsed).path.takeIf { it.isNotBlank() }
}.getOrNull()

/** Only hand the portal a starting folder that exists, or it may refuse the whole request. */
internal fun portalStartingFolder(initialPath: String?): String? {
    val file = initialPath?.takeIf { it.isNotBlank() }?.let(::File) ?: return null
    return when {
        file.isDirectory -> file.path
        file.parentFile?.isDirectory == true -> file.parent
        else -> null
    }
}

internal object LinuxFileChooserPortal {
    private val log = Logger.withTag("FileChooserPortal")

    /** True in the Flatpak: every picker must come through here instead of Swing. */
    val applies: Boolean get() = LinuxFlatpakSandbox.isActive

    fun chooseFolder(title: String, initialPath: String? = null): String? = resolved(
        PortalChooserRequest(directory = true, title = title, currentFolder = portalStartingFolder(initialPath)),
    )

    fun openFile(title: String, filter: PortalFileFilter? = null, initialPath: String? = null): String? = resolved(
        PortalChooserRequest(title = title, filter = filter, currentFolder = portalStartingFolder(initialPath)),
    )

    /** Write to exactly the returned path: see the note about picked files above. */
    fun saveFile(title: String, suggestedName: String, filter: PortalFileFilter? = null, initialPath: String? = null): String? = resolved(
        PortalChooserRequest(
            save = true,
            title = title,
            currentName = suggestedName,
            filter = filter,
            currentFolder = portalStartingFolder(initialPath),
        ),
    )

    /** Null when the user backed out, or when it failed, which the user is told about here. */
    private fun resolved(request: PortalChooserRequest): String? =
        when (val choice = offEventThread { run(request) }) {
            is PortalChoice.Picked -> choice.path
            PortalChoice.Cancelled -> null
            is PortalChoice.Failed -> {
                log.w { "File chooser failed: ${choice.message}" }
                NuvioToastController.show(localizedString(Res.string.linux_portal_chooser_failed), durationMillis = 7000L)
                null
            }
        }

    private fun run(request: PortalChooserRequest): PortalChoice = runCatching {
        NativePlayerBridge.ensureNativeLibraryLoaded()
        portalChoice(
            LinuxFileChooserPortalNative.choose(
                save = request.save,
                directory = request.directory,
                parentDrawable = appWindowDrawable(),
                title = request.title,
                currentFolder = request.currentFolder.orEmpty(),
                currentName = request.currentName.orEmpty(),
                filterName = request.filter?.name.orEmpty(),
                filterPatterns = request.filter?.patterns.orEmpty().toTypedArray(),
                timeoutMs = ChooserTimeoutMs,
            ),
        )
    }.getOrElse { error -> PortalChoice.Failed(error.message ?: error.toString()) }

    /**
     * Some callers (the settings backup) run on the UI thread, as Swing's modal dialogs allow. The
     * portal's dialog is another process, so waiting for it must not freeze the window: pump events
     * meanwhile, which is what a modal Swing dialog does too.
     */
    private fun <T> offEventThread(block: () -> T): T {
        if (!EventQueue.isDispatchThread()) return block()
        val loop = Toolkit.getDefaultToolkit().systemEventQueue.createSecondaryLoop()
        var outcome: Result<T>? = null
        val worker = Thread({
            outcome = runCatching(block)
            loop.exit()
        }, "nuvio-file-chooser-portal").apply { isDaemon = true }
        worker.start()
        loop.enter()
        worker.join()
        return outcome!!.getOrThrow()
    }

    /** The app window's X11 drawable for the dialog's parent; 0 opens it unparented, which still works. */
    private fun appWindowDrawable(): Long = runCatching {
        var drawable = 0L
        val resolve = Runnable {
            val window = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow?.takeIf { it.isShowing && it is Frame }
                ?: Window.getWindows().filter { it.isShowing && it is Frame }.maxByOrNull { it.width.toLong() * it.height }
            if (window != null) drawable = LinuxAwtViewResolver.resolveNativeViewPointer(window)
        }
        if (SwingUtilities.isEventDispatchThread()) resolve.run() else SwingUtilities.invokeAndWait(resolve)
        drawable
    }.getOrDefault(0L)
}

/** Long enough for anyone at the dialog; only a dialog nobody answers ever reaches it. */
private const val ChooserTimeoutMs = 60 * 60 * 1000
