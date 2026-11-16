package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.backup.GoogleDriveBackup
import com.nuvio.app.features.backup.GoogleDriveBackupState
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_cancel
import nuvio.composeapp.generated.resources.settings_drive_backup_now
import nuvio.composeapp.generated.resources.settings_drive_connect
import nuvio.composeapp.generated.resources.settings_drive_disconnect
import nuvio.composeapp.generated.resources.settings_drive_last_backup
import nuvio.composeapp.generated.resources.settings_drive_never
import nuvio.composeapp.generated.resources.settings_drive_not_configured
import nuvio.composeapp.generated.resources.settings_drive_older_warning
import nuvio.composeapp.generated.resources.settings_drive_restore
import nuvio.composeapp.generated.resources.settings_drive_restore_app
import nuvio.composeapp.generated.resources.settings_drive_restore_confirm_body
import nuvio.composeapp.generated.resources.settings_drive_restore_confirm_title
import nuvio.composeapp.generated.resources.settings_drive_restore_live
import nuvio.composeapp.generated.resources.settings_drive_section
import nuvio.composeapp.generated.resources.settings_drive_status
import nuvio.composeapp.generated.resources.settings_drive_waiting_browser
import org.jetbrains.compose.resources.stringResource
import java.text.DateFormat
import java.util.Date

/**
 * Google Drive backup controls, shared by the Account page. One state machine drives connect,
 * disconnect, backup and restore; the restore flow asks what to restore and confirms before it
 * replaces anything.
 */
@Composable
internal fun GoogleDriveBackupSection(isTablet: Boolean) {
    val state by GoogleDriveBackup.state.collectAsState()
    LaunchedEffect(Unit) { GoogleDriveBackup.refreshStatus() }
    var showRestore by remember { mutableStateOf(false) }

    SettingsSection(
        title = stringResource(Res.string.settings_drive_section),
        isTablet = isTablet,
    ) {
        SettingsGroup(isTablet = isTablet) {
            SettingsInfoRow(
                title = stringResource(Res.string.settings_drive_status),
                detail = if (!state.configured) {
                    stringResource(Res.string.settings_drive_not_configured)
                } else {
                    state.statusLabel
                },
                isTablet = isTablet,
                emphasized = state.connected,
            )
            if (state.configured) {
                SettingsGroupDivider(isTablet = isTablet)
                SettingsInfoRow(
                    title = stringResource(Res.string.settings_drive_last_backup),
                    detail = if (state.lastBackupMs > 0L) {
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                            .format(Date(state.lastBackupMs))
                    } else {
                        stringResource(Res.string.settings_drive_never)
                    },
                    isTablet = isTablet,
                )
                if (state.remoteExists && state.remoteOlderThanLocal) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSectionNote(
                        text = stringResource(Res.string.settings_drive_older_warning),
                        isTablet = isTablet,
                    )
                }
                if (state.signInUrl != null) {
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsSectionNote(
                        text = stringResource(Res.string.settings_drive_waiting_browser),
                        isTablet = isTablet,
                    )
                }

                SettingsGroupDivider(isTablet = isTablet)
                if (!state.connected) {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_drive_connect),
                        description = "",
                        isTablet = isTablet,
                        onClick = { GoogleDriveBackup.connect() },
                    )
                } else {
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_drive_backup_now),
                        description = "",
                        isTablet = isTablet,
                        onClick = { GoogleDriveBackup.backupNow() },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_drive_restore),
                        description = "",
                        isTablet = isTablet,
                        onClick = { showRestore = true },
                    )
                    SettingsGroupDivider(isTablet = isTablet)
                    SettingsNavigationRow(
                        title = stringResource(Res.string.settings_drive_disconnect),
                        description = "",
                        isTablet = isTablet,
                        onClick = { GoogleDriveBackup.disconnect() },
                    )
                }
            }
        }

        state.message?.let { message ->
            SettingsSectionNote(text = message, isTablet = isTablet)
        }
    }

    if (showRestore) {
        RestoreDriveBackupDialog(
            onDismiss = { showRestore = false },
            onRestore = { live, app ->
                showRestore = false
                GoogleDriveBackup.restoreNow(liveTv = live, appSettings = app)
            },
        )
    }
}

/** A read-only two-line row for status text. */
@Composable
private fun SettingsInfoRow(
    title: String,
    detail: String,
    isTablet: Boolean,
    emphasized: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (isTablet) 20.dp else 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = tokens.colors.textPrimary,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = if (emphasized) tokens.colors.accent else tokens.colors.textMuted,
            )
        }
    }
}

@Composable
private fun RestoreDriveBackupDialog(
    onDismiss: () -> Unit,
    onRestore: (liveTv: Boolean, appSettings: Boolean) -> Unit,
) {
    var liveTv by remember { mutableStateOf(true) }
    var appSettings by remember { mutableStateOf(true) }
    NuvioModalDialog(
        onDismissRequest = onDismiss,
        title = stringResource(Res.string.settings_drive_restore_confirm_title),
        subtitle = stringResource(Res.string.settings_drive_restore_confirm_body),
        actions = {
            SettingsGlassButton(
                text = stringResource(Res.string.action_cancel),
                onClick = onDismiss,
            )
            SettingsGlassButton(
                text = stringResource(Res.string.settings_drive_restore),
                enabled = liveTv || appSettings,
                accent = true,
                onClick = { onRestore(liveTv, appSettings) },
            )
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_drive_restore_live),
                description = null,
                checked = liveTv,
                isTablet = true,
                onCheckedChange = { liveTv = it },
            )
            SettingsSwitchRow(
                title = stringResource(Res.string.settings_drive_restore_app),
                description = null,
                checked = appSettings,
                isTablet = true,
                onCheckedChange = { appSettings = it },
            )
        }
    }
}
