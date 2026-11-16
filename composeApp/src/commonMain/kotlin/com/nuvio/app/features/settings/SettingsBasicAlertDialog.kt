package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties
import com.nuvio.app.core.ui.DialogBackRegistration
import com.nuvio.app.core.ui.dismissOnBackKey

/**
 * [BasicAlertDialog] for the Settings feature, wired into the app's Back ladder. A desktop dialog
 * only dismisses on Escape; Backspace and a pad's B button arrive as ordinary Back events, which
 * [DialogBackRegistration] routes to this dialog before the page behind it can take them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsBasicAlertDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    DialogBackRegistration(onDismiss = onDismissRequest)
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties,
    ) {
        Box(Modifier.dismissOnBackKey(onDismissRequest)) {
            content()
        }
    }
}
