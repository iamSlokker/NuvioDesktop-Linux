package com.nuvio.app.features.auth

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.core.qr.QrCodeMatrix
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioSweepHighlight
import kotlinx.coroutines.delay
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_auth_qr_email_tab
import nuvio.composeapp.generated.resources.compose_auth_qr_expired
import nuvio.composeapp.generated.resources.compose_auth_qr_expires_in
import nuvio.composeapp.generated.resources.compose_auth_qr_manual_hint
import nuvio.composeapp.generated.resources.compose_auth_qr_preparing
import nuvio.composeapp.generated.resources.compose_auth_qr_qr_tab
import nuvio.composeapp.generated.resources.compose_auth_qr_refresh
import nuvio.composeapp.generated.resources.compose_auth_qr_scan_hint
import nuvio.composeapp.generated.resources.compose_auth_qr_signed_in
import nuvio.composeapp.generated.resources.compose_auth_qr_signing_in
import nuvio.composeapp.generated.resources.compose_auth_qr_waiting
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.border
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import com.nuvio.app.core.ui.KeyboardInputMode
import com.nuvio.app.core.ui.selectWithKeyboardOrPointer

/** How the sign-in card is filled in. */
enum class AuthMethod { Email, QrCode }

/** Test handle for the pairing card's refresh action. */
internal const val QrRefreshTag = "qr-refresh"

/** Test handle for the pairing card as a whole. */
internal const val QrPanelTag = "qr-panel"

/** Test handle for the "Expires in m:ss" line. */
internal const val QrCountdownTag = "qr-countdown"

private val QrPanelSize: Dp = 148.dp
private val QrCornerRadius: Dp = 14.dp

/**
 * The right-hand half of the sign-in card: a pairing code, the code in text for anyone who would
 * rather type it, and whatever the pairing is currently doing.
 *
 * The symbol itself is drawn from a module grid (see [QrCodeMatrix]) rather than an image, so it
 * stays crisp at any scale and needs no bitmap, file or platform graphics API.
 */
@Composable
fun QrLoginPanel(
    state: QrLoginUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    /** Lets the page place the refresh button in its own focus order (it follows the form). */
    refreshFocusRequester: FocusRequester? = null,
    /**
     * False when the page draws the refresh action itself, in a row shared with Continue Without
     * Account. The panel then ends at the countdown, which is the layout the sign-in page uses.
     */
    showRefreshButton: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth().testTag(QrPanelTag),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // The instruction above the symbol, as on the phone app: the heading that used to sit here
        // is dropped because the page above already says what this card is for.
        Text(
            text = stringResource(Res.string.compose_auth_qr_scan_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(12.dp))

        val matrix = state.matrix
        if (matrix == null) {
            Box(
                modifier = Modifier
                    .size(QrPanelSize)
                    .clip(RoundedCornerShape(QrCornerRadius))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (state.phase == QrLoginPhase.Failed) {
                    Text(
                        text = "—",
                        style = MaterialTheme.typography.headlineLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        strokeWidth = 2.5.dp,
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .size(QrPanelSize)
                    .clip(RoundedCornerShape(QrCornerRadius))
                    .alpha(if (state.phase == QrLoginPhase.Expired) 0.35f else 1f),
            ) {
                QrCodeCanvas(matrix = matrix, modifier = Modifier.size(QrPanelSize))
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        state.userCode?.let { code ->
            Text(
                text = stringResource(Res.string.compose_auth_qr_manual_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = code,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(6.dp))
        }

        QrLoginStatus(state = state)

        if (showRefreshButton) {
            Spacer(modifier = Modifier.height(14.dp))
            // Always offered, not only after a code dies: someone with the wrong phone to hand, or
            // a code that will not scan, needs a new one without waiting for the countdown.
            QrRefreshButton(enabled = !state.isBusy, onClick = onRefresh, requester = refreshFocusRequester)
        }
    }
}

@Composable
private fun QrLoginStatus(state: QrLoginUiState) {
    val expiresIn by produceState(
        initialValue = state.expiresAtMillis?.let { remainingLabel(it) },
        state.expiresAtMillis,
        state.phase,
    ) {
        if (state.phase != QrLoginPhase.Waiting) {
            value = state.expiresAtMillis?.let { remainingLabel(it) }
            return@produceState
        }
        while (true) {
            value = state.expiresAtMillis?.let { remainingLabel(it) }
            delay(1_000)
        }
    }

    val statusText = when (state.phase) {
        QrLoginPhase.Starting -> stringResource(Res.string.compose_auth_qr_preparing)
        QrLoginPhase.Waiting -> stringResource(Res.string.compose_auth_qr_scan_hint)
        QrLoginPhase.SigningIn -> stringResource(Res.string.compose_auth_qr_signing_in)
        QrLoginPhase.SignedIn -> stringResource(Res.string.compose_auth_qr_signed_in)
        QrLoginPhase.Expired -> stringResource(Res.string.compose_auth_qr_expired)
        QrLoginPhase.Failed, QrLoginPhase.Idle -> state.error.orEmpty()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 22.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
        Text(
            text = statusText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.phase == QrLoginPhase.Failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            textAlign = TextAlign.Center,
        )
    }

    if (state.isWaiting && expiresIn != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.compose_auth_qr_expires_in, expiresIn.orEmpty()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(QrCountdownTag),
        )
    }

    Spacer(modifier = Modifier.height(14.dp))

    // Always offered, not only after a code dies: someone with the wrong phone to hand, or a code
    // that will not scan, needs a new one without waiting for the countdown.
}

/**
 * Replaces the code on screen. A plain focusable surface rather than Material's Button: this screen
 * is driven by arrows and a pad, and this draws its own focus light to match the rest of Nuvio.
 */
@Composable
internal fun QrRefreshButton(
    enabled: Boolean,
    onClick: () -> Unit,
    requester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onKeyNavigation: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.button)
    val interactionSource = remember { MutableInteractionSource() }
    // Keyboard focus only: a hover is not "about to press this", and the ring is the app's signal
    // for the control the keyboard is on.
    var hasFocus by remember { mutableStateOf(false) }
    val ringVisible = hasFocus && KeyboardInputMode.active
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .then(if (requester != null) Modifier.focusRequester(requester) else Modifier)
            .onFocusChanged { hasFocus = it.isFocused }
            .nuvioSweepHighlight(highlighted = enabled && ringVisible, cornerRadius = NuvioTokens.Radius.button)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            // The same 2dp accent ring the rest of the app draws on a focused control. It is drawn
            // after the fill so the stroke is not covered by it, and it shows on a disabled button
            // too: a control the keyboard is on has to look like it, even when it cannot be pressed.
            .border(
                width = if (ringVisible) tokens.borders.medium else 0.dp,
                color = if (ringVisible) tokens.colors.borderFocus else Color.Transparent,
                shape = shape,
            )
            .testTag(QrRefreshTag)
            // Applied FIRST, i.e. outermost in the chain. A key's preview phase runs from the
            // outermost node inward, so this sees the arrow before `selectWithKeyboardOrPointer`
            // installs its focusable node. With the order reversed the navigation handler was
            // never reached and the walk died on the control the page starts on.
            .then(onKeyNavigation)
            .selectWithKeyboardOrPointer(
                enabled = enabled,
                interactionSource = interactionSource,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(Res.string.compose_auth_qr_refresh),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(
                alpha = if (enabled) 1f else tokens.opacity.disabled,
            ),
        )
    }
}

/**
 * Two-way switch between the email form and the pairing code. Same shape and height as the fields
 * below it, so switching does not move the card's contents around.
 */
@Composable
fun AuthMethodToggle(
    selected: AuthMethod,
    onSelect: (AuthMethod) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        AuthMethodTab(
            text = stringResource(Res.string.compose_auth_qr_email_tab),
            selected = selected == AuthMethod.Email,
            onClick = { onSelect(AuthMethod.Email) },
            modifier = Modifier.weight(1f),
        )
        AuthMethodTab(
            text = stringResource(Res.string.compose_auth_qr_qr_tab),
            selected = selected == AuthMethod.QrCode,
            onClick = { onSelect(AuthMethod.QrCode) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun AuthMethodTab(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(11.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
            )
            .clickable(onClick = onClick)
            .height(40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * Draws the symbol white-on-black in a quiet zone of four modules (the spec minimum for camera
 * readability), extending each module slightly so neighbouring cells never leave a seam.
 */
@Composable
private fun QrCodeCanvas(matrix: QrCodeMatrix, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        drawRect(color = Color.White)
        val modules = matrix.size + QrQuietZone * 2
        val step = size.minDimension / modules
        val origin = QrQuietZone * step
        for (row in 0 until matrix.size) {
            for (column in 0 until matrix.size) {
                if (!matrix.isDark(column, row)) continue
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(origin + column * step, origin + row * step),
                    size = Size(step + QrSeamOverlap, step + QrSeamOverlap),
                )
            }
        }
    }
}

private const val QrQuietZone = 4

/** One physical-pixel-ish overlap; enough to hide antialiasing seams, invisible at any size. */
private const val QrSeamOverlap = 0.5f

/** `m:ss`, or null once the deadline has passed (the controller flips to Expired). */
private fun remainingLabel(expiresAtMillis: Long, nowMillis: Long = System.currentTimeMillis()): String? {
    val remaining = expiresAtMillis - nowMillis
    if (remaining <= 0) return null
    val seconds = remaining / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}
