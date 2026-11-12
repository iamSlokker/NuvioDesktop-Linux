package com.nuvio.app.features.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerDiscoveryFailure
import com.nuvio.app.core.ui.NuvioAlertDialog
import com.nuvio.app.core.ui.NuvioModalDialog
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.input.mouseKeyboardState
import com.nuvio.app.features.input.setMouseKeyboard
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.focusable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.interaction.MutableInteractionSource
import com.nuvio.app.core.ui.selectWithKeyboardOrPointer

/*
 * Upstream presents this flow as a modal bottom sheet plus two BasicAlertDialogs. A bottom sheet is
 * the wrong idiom on desktop and does not match the rest of this fork's popups, so the same flow is
 * rebuilt on NuvioModalDialog / NuvioAlertDialog. States, copy and validation are unchanged - only
 * the surfaces differ.
 */

@Composable
internal fun ServerConnectionMenu(
    activeServer: ServerConfiguration,
    onUseOfficial: () -> Unit,
    onConnectCustom: () -> Unit,
    modifier: Modifier = Modifier,
    onMenuFocused: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    // Escape, the pad's Back button and Start all raise this menu (see AuthMenuRequest): it holds
    // the on-screen keyboard toggle, which a couch user needs before they can sign in.
    LaunchedEffect(Unit) {
        AuthMenuRequest.open.collect { expanded = true }
    }
    // Kept in step with the popup so the page's Escape handler knows whether to open or close it.
    // Written during composition, not from an effect: the page's preview handler can run before a
    // LaunchedEffect would have published the new value, and a stale `false` there re-opens the menu
    // on the very Escape meant to dismiss it.
    AuthMenuRequest.isOpen = expanded
    // The menu is a popup window. Closing it can leave the page with no Compose focus owner, so
    // ask the sign-in page to put the keys back on the control that had them before the menu.
    var wasExpanded by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (wasExpanded && !expanded) AuthFocusRestore.request()
        wasExpanded = expanded
    }
    DisposableEffect(Unit) {
        onDispose { AuthMenuRequest.isOpen = false }
    }
    // The same setting Settings > Keyboard shortcuts exposes: raise the app's own keyboard whenever
    // a text field is focused. On a couch PC this is the only way to type a password without a
    // physical keyboard, and the sign-in page is where that need first appears.
    val onScreenKeyboard by mouseKeyboardState().collectAsState()
    val menuInteraction = remember { MutableInteractionSource() }
    Column(modifier = modifier) {
        IconButton(
            onClick = { expanded = true },
            interactionSource = menuInteraction,
            // In the arrow-key focus order, so the D-pad and the arrows can reach it like any other
            // control. Escape and Start also raise the menu (see AuthMenuRequest), but a focus stop
            // is what makes it discoverable: without one the icon is invisible to the walk and a
            // user has no way to know it is there.
            //
            // `selectWithKeyboardOrPointer` rather than a bare `focusable()`: that adds a focus
            // target *beside* the button's own clickable, so the icon could be focused but Select
            // did nothing. This wires focus and activation to the same node.
            modifier = Modifier
                .testTag(AuthServerMenuButtonTag)
                .onFocusChanged { if (it.isFocused) onMenuFocused() }
                .selectWithKeyboardOrPointer(
                    enabled = true,
                    interactionSource = menuInteraction,
                    onClick = { expanded = true },
                ),
        ) {
            Icon(
                imageVector = Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.server_menu_content_description),
                tint = MaterialTheme.nuvio.colors.textPrimary,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = MaterialTheme.nuvio.colors.surfacePopover,
            shape = MaterialTheme.nuvio.shapes.compactCard,
        ) {
            com.nuvio.app.core.ui.KeyRouteLayer("server menu") // the focus router leaves an open menu alone
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.server_menu_official)) },
                enabled = activeServer.isCustom,
                onClick = {
                    expanded = false
                    onUseOfficial()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (activeServer.isCustom) {
                                Res.string.server_menu_change_custom
                            } else {
                                Res.string.server_menu_custom
                            },
                        ),
                    )
                },
                onClick = {
                    expanded = false
                    onConnectCustom()
                },
            )
            HorizontalDivider(color = MaterialTheme.nuvio.colors.borderSubtle)
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.settings_shortcuts_on_screen_keyboard)) },
                trailingIcon = {
                    if (onScreenKeyboard) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = null,
                            tint = MaterialTheme.nuvio.colors.accent,
                        )
                    }
                },
                modifier = Modifier.testTag(AuthOnScreenKeyboardMenuItemTag),
                onClick = { setMouseKeyboard(!onScreenKeyboard) },
            )
        }
    }
}

/** Test handle for the three-dot menu's on-screen keyboard toggle. */
internal const val AuthOnScreenKeyboardMenuItemTag = "auth-osk-toggle"
internal const val AuthServerMenuButtonTag = "auth-server-menu"

/**
 * Asks the sign-in page's three-dot menu to open, and reports whether it is open.
 *
 * The menu holds the on-screen keyboard toggle, which is the only way to type a password on a
 * couch PC — and it is the one control a pad user needs before they can sign in at all. Escape, the
 * pad's Back button and Start all raise it through here, so the menu is reachable without a mouse
 * and without a working focus ring on the icon itself.
 *
 * [isOpen] exists so the page can tell "Escape should open the menu" from "Escape should close it".
 * Without it the page's preview handler re-opened the menu on every Escape, including the one meant
 * to dismiss it: the popup then never closed, kept focus, and the page's arrows stayed dead for
 * good — the reported "navigation works until I press Escape".
 */
internal object AuthMenuRequest {
    private val _open = kotlinx.coroutines.flow.MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val open: kotlinx.coroutines.flow.SharedFlow<Unit> = _open

    /** True while the menu is showing; the menu keeps this in step with its own expanded state. */
    @Volatile
    var isOpen: Boolean = false

    fun request() {
        _open.tryEmit(Unit)
    }
}

@Composable
internal fun ServerConnectionDialog(
    state: ServerConnectionUiState,
    onDiscover: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    var url by rememberSaveable { mutableStateOf("") }
    val error = serverDiscoveryError(state)

    NuvioModalDialog(
        onDismissRequest = { if (!state.isDiscovering) onDismiss() },
        title = stringResource(Res.string.server_connect_title),
        subtitle = stringResource(Res.string.server_connect_subtitle),
        maxWidth = 560.dp,
        actions = {
            TextButton(onClick = onDismiss, enabled = !state.isDiscovering) {
                Text(stringResource(Res.string.action_cancel))
            }
            TextButton(
                onClick = { onDiscover(url) },
                enabled = url.isNotBlank() && !state.isDiscovering,
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.accent),
            ) {
                if (state.isDiscovering) {
                    PendingLabel(
                        label = stringResource(Res.string.server_connect_checking),
                        color = tokens.colors.accent,
                    )
                } else {
                    Text(stringResource(Res.string.server_connect_action))
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        ) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isDiscovering,
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.server_connect_url_placeholder)) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { if (url.isNotBlank()) onDiscover(url) },
                ),
                shape = tokens.shapes.button,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = tokens.colors.borderFocus,
                    unfocusedBorderColor = tokens.colors.borderDefault,
                    focusedTextColor = tokens.colors.textPrimary,
                    unfocusedTextColor = tokens.colors.textPrimary,
                    focusedLabelColor = tokens.colors.textSecondary,
                    unfocusedLabelColor = tokens.colors.textMuted,
                    cursorColor = tokens.colors.accent,
                ),
            )
            if (error != null) {
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
        }
    }
}

@Composable
internal fun ServerTrustDialog(
    server: ServerConfiguration,
    isSwitching: Boolean,
    switchFailure: ServerSwitchFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioModalDialog(
        onDismissRequest = { if (!isSwitching) onDismiss() },
        title = stringResource(Res.string.server_review_title),
        subtitle = stringResource(Res.string.server_review_description),
        maxWidth = 560.dp,
        actions = {
            TextButton(onClick = onDismiss, enabled = !isSwitching) {
                Text(stringResource(Res.string.action_cancel))
            }
            TextButton(
                onClick = onConfirm,
                enabled = !isSwitching,
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.accent),
            ) {
                if (isSwitching) {
                    PendingLabel(
                        label = stringResource(Res.string.server_switching),
                        color = tokens.colors.accent,
                    )
                } else {
                    Text(stringResource(Res.string.server_review_trust))
                }
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = NuvioTokens.Space.s8),
            verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10),
        ) {
            ServerDetail(
                label = stringResource(Res.string.server_review_verified_label),
                value = server.backendUrl,
            )
            ServerDetail(
                label = stringResource(Res.string.server_review_key_label),
                value = stringResource(Res.string.server_review_key_discovered),
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = tokens.colors.warning.copy(alpha = NuvioTokens.Opacity.selected),
                        shape = RoundedCornerShape(NuvioTokens.Radius.lg),
                    )
                    .padding(NuvioTokens.Space.s14),
                verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
            ) {
                Text(
                    text = stringResource(Res.string.server_warning_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.colors.warning,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when {
                        !server.isSecure && server.isPublicHost -> {
                            stringResource(Res.string.server_warning_public_http)
                        }
                        !server.isSecure -> stringResource(Res.string.server_warning_http)
                        server.isPublicHost -> stringResource(Res.string.server_warning_public)
                        else -> stringResource(Res.string.server_warning_private)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                Text(
                    text = stringResource(Res.string.server_warning_credentials),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.textSecondary,
                )
            }
            if (switchFailure != null) {
                Text(
                    text = serverSwitchError(switchFailure),
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.colors.danger,
                )
            }
        }
    }
}

@Composable
internal fun OfficialServerDialog(
    isSwitching: Boolean,
    switchFailure: ServerSwitchFailure?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    NuvioAlertDialog(
        onDismissRequest = { if (!isSwitching) onDismiss() },
        title = { Text(stringResource(Res.string.server_official_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s10)) {
                Text(
                    text = stringResource(Res.string.server_official_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = tokens.colors.textSecondary,
                )
                if (switchFailure != null) {
                    Text(
                        text = serverSwitchError(switchFailure),
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.colors.danger,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isSwitching,
                colors = ButtonDefaults.textButtonColors(contentColor = tokens.colors.accent),
            ) {
                if (isSwitching) {
                    PendingLabel(
                        label = stringResource(Res.string.server_switching),
                        color = tokens.colors.accent,
                    )
                } else {
                    Text(stringResource(Res.string.server_official_action))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isSwitching) {
                Text(stringResource(Res.string.action_cancel))
            }
        },
    )
}

@Composable
private fun PendingLabel(label: String, color: Color) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(MaterialTheme.nuvio.icons.sm),
            color = color,
            strokeWidth = 2.dp,
        )
        Text(label)
    }
}

@Composable
private fun ServerDetail(
    label: String,
    value: String,
) {
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tokens.colors.surfaceCard, tokens.shapes.compactCard)
            .padding(NuvioTokens.Space.s14),
        verticalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s4),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = tokens.colors.textPrimary,
        )
    }
}

@Composable
private fun serverDiscoveryError(state: ServerConnectionUiState): String? {
    return when (state.failure) {
        ServerDiscoveryFailure.InvalidUrl -> stringResource(Res.string.server_error_invalid_url)
        ServerDiscoveryFailure.OfficialServer -> stringResource(
            if (state.activeServer.isCustom) {
                Res.string.server_error_official_available
            } else {
                Res.string.server_error_official_active
            },
        )
        ServerDiscoveryFailure.ConnectionFailed -> stringResource(Res.string.server_error_connection)
        ServerDiscoveryFailure.HttpError -> stringResource(
            Res.string.server_error_http,
            state.statusCode ?: 0,
        )
        ServerDiscoveryFailure.ResponseTooLarge -> stringResource(Res.string.server_error_response_too_large)
        ServerDiscoveryFailure.InvalidDocument -> stringResource(Res.string.server_error_invalid_document)
        ServerDiscoveryFailure.UnsupportedVersion -> stringResource(Res.string.server_error_version)
        ServerDiscoveryFailure.WrongService -> stringResource(Res.string.server_error_service)
        ServerDiscoveryFailure.NotSelfHosted -> stringResource(Res.string.server_error_not_self_hosted)
        ServerDiscoveryFailure.MissingConfiguration -> stringResource(Res.string.server_error_missing_configuration)
        ServerDiscoveryFailure.UnsupportedAuthentication -> stringResource(Res.string.server_error_auth)
        null -> null
    }
}

@Composable
private fun serverSwitchError(failure: ServerSwitchFailure): String =
    when (failure) {
        ServerSwitchFailure.SessionClear -> stringResource(Res.string.server_error_session_clear)
        ServerSwitchFailure.Save -> stringResource(Res.string.server_error_save)
        ServerSwitchFailure.Restart -> stringResource(Res.string.server_error_restart)
    }
