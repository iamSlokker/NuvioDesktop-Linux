package com.nuvio.app.features.backup

import kotlinx.coroutines.flow.StateFlow

/**
 * What Settings shows for the Google Drive backup section. [message] is a short, already-localized
 * sentence; [lastBackupMs] is when this PC last uploaded; [remoteOlderThanLocal] is true when the
 * local settings changed after the last upload.
 */
data class GoogleDriveBackupState(
    val configured: Boolean = false,
    val connected: Boolean = false,
    val email: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val lastBackupMs: Long = 0L,
    val remoteSavedAtMs: Long = 0L,
    val remoteDevice: String? = null,
    val remoteExists: Boolean = false,
    val remoteOlderThanLocal: Boolean = false,
    val needsReauth: Boolean = false,
    /** Set while a browser sign-in is pending, so the user can reopen the page. */
    val signInUrl: String? = null,
) {
    val statusLabel: String
        get() = when {
            !configured -> "Not configured"
            connected -> email?.let { "Connected as $it" } ?: "Connected"
            needsReauth -> "Sign-in expired — connect again"
            else -> "Not connected"
        }
}

/**
 * Google Drive backup for the two settings domains. Desktop actual lives in
 * `features/backup/GoogleDriveBackup.desktop.kt`; it is behind an expect so the Settings UI stays
 * platform-neutral and the Android SDK is never needed.
 */
internal expect object GoogleDriveBackup {
    val state: StateFlow<GoogleDriveBackupState>

    /** Opens the system browser for OAuth (PKCE, loopback redirect). */
    fun connect()
    fun cancelSignIn()
    fun disconnect()

    /** Uploads both domains (Live TV legacy file + ordinary settings companion). */
    fun backupNow()

    /** Restores the selected domains after a confirmation step in the UI. */
    fun restoreNow(liveTv: Boolean, appSettings: Boolean)

    /** Re-reads the account and the remote backup's timestamp. */
    fun refreshStatus()
}
