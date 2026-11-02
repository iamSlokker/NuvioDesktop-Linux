package com.nuvio.app.features.locallibrary

import com.nuvio.app.core.i18n.localizedString
import com.nuvio.app.core.portal.LinuxFileChooserPortal
import com.nuvio.app.core.portal.PortalFileFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nuvio.composeapp.generated.resources.*

internal actual object SandboxPathPicker {
    actual val required: Boolean get() = LinuxFileChooserPortal.applies

    actual suspend fun pickFolder(): String? = withContext(Dispatchers.IO) {
        LinuxFileChooserPortal.chooseFolder(localizedString(Res.string.linux_portal_choose_folder))
    }

    actual suspend fun pickShaderFile(): String? = withContext(Dispatchers.IO) {
        val filterName = localizedString(Res.string.linux_portal_shader_filter)
        LinuxFileChooserPortal.openFile(
            title = localizedString(Res.string.linux_portal_add_shader_file),
            filter = PortalFileFilter(filterName, listOf("*.glsl", "*.hook")),
        )
    }
}
