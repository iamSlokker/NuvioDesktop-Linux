package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import com.nuvio.app.features.locallibrary.SandboxPathPicker
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** The system chooser beside a typed folder path; renders nothing outside a sandbox. */
@Composable
internal fun SandboxFolderRow(isTablet: Boolean, onChosen: (String) -> Unit) {
    if (!SandboxPathPicker.required) return
    val scope = rememberCoroutineScope()
    SettingsGroupDivider(isTablet)
    SettingsNavigationRow(
        title = stringResource(Res.string.linux_portal_choose_folder),
        description = stringResource(Res.string.linux_portal_choose_folder_description),
        isTablet = isTablet,
        onClick = { scope.launch { SandboxPathPicker.pickFolder()?.let(onChosen) } },
    )
}

/** Adds chosen shader paths to the custom shader editor; renders nothing outside a sandbox. */
@Composable
internal fun SandboxShaderPathButtons(onChosen: (String) -> Unit) {
    if (!SandboxPathPicker.required) return
    val scope = rememberCoroutineScope()
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { scope.launch { SandboxPathPicker.pickFolder()?.let(onChosen) } }) {
            Text(stringResource(Res.string.linux_portal_add_shader_folder))
        }
        TextButton(onClick = { scope.launch { SandboxPathPicker.pickShaderFile()?.let(onChosen) } }) {
            Text(stringResource(Res.string.linux_portal_add_shader_file))
        }
    }
}

/** One path per line, as the shader editor expects. */
internal fun appendPathLine(text: String, path: String): String =
    if (text.isBlank()) path else "${text.trimEnd()}\n$path"
