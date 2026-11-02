package com.nuvio.app.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.nuvio.app.core.build.NuvioLinkRegistration
import com.nuvio.app.core.ui.NuvioToastController
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.linux_appimage_links_description
import nuvio.composeapp.generated.resources.linux_appimage_links_failed
import nuvio.composeapp.generated.resources.linux_appimage_links_section
import nuvio.composeapp.generated.resources.linux_appimage_links_title
import org.jetbrains.compose.resources.stringResource

/** Renders nothing except where [NuvioLinkRegistration.offered]: the Linux AppImage. */
@Composable
internal fun NuvioLinkRegistrationSection(isTablet: Boolean) {
    if (!NuvioLinkRegistration.offered) return
    var registered by remember { mutableStateOf(NuvioLinkRegistration.isRegistered()) }
    val failed = stringResource(Res.string.linux_appimage_links_failed, "%s")
    SettingsSection(title = stringResource(Res.string.linux_appimage_links_section), isTablet = isTablet) {
        SettingsGroup(isTablet = isTablet) {
            SettingsSwitchRow(
                title = stringResource(Res.string.linux_appimage_links_title),
                description = stringResource(Res.string.linux_appimage_links_description),
                checked = registered,
                isTablet = isTablet,
                onCheckedChange = { value ->
                    val error = NuvioLinkRegistration.setRegistered(value)
                    if (error != null) NuvioToastController.show(failed.replace("%s", error), durationMillis = 7000L)
                    registered = NuvioLinkRegistration.isRegistered()
                },
            )
        }
    }
}
