package com.nuvio.app.core.ui

import com.nuvio.app.features.settings.settingsNavItem

import com.nuvio.app.core.ui.smoothBorder
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.action_back
import nuvio.composeapp.generated.resources.action_ok
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.accentBrush

@Composable
fun NuvioScreen(
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = MaterialTheme.nuvio.spacing.screenHorizontal,
    topPadding: Dp? = null,
    backgroundColor: Color? = null,
    listState: LazyListState = rememberLazyListState(),
    showDesktopScrollbar: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    val resolvedBackgroundColor = backgroundColor ?: tokens.colors.background
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier = modifier.fillMaxSize().background(resolvedBackgroundColor)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalPadding,
                top = topPadding ?: tokens.spacing.screenTop + statusBarTop + nuvioPlatformExtraTopPadding,
                end = horizontalPadding,
                bottom = nuvioSafeBottomPadding(tokens.spacing.screenBottom),
            ),
            verticalArrangement = Arrangement.spacedBy(tokens.spacing.listGap),
            content = content,
        )
        if (showDesktopScrollbar) {
            NuvioDesktopVerticalScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 3.dp),
            )
        }
    }
}

internal fun Modifier.nuvioConsumePointerEvents(): Modifier =
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent(PointerEventPass.Final).changes.forEach { change ->
                    change.consume()
                }
            }
        }
    }

@Composable
fun NuvioSurfaceCard(
    modifier: Modifier = Modifier,
    tonalElevation: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tokens = MaterialTheme.nuvio
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = tokens.colors.surface,
        shape = tokens.shapes.card,
        tonalElevation = tonalElevation.dp,
        shadowElevation = tokens.elevation.flat,
    ) {
        Column(
            modifier = Modifier.padding(tokens.spacing.cardPadding),
            content = content,
        )
    }
}

@Composable
fun NuvioScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    includeStatusBarPadding: Boolean = true,
    topPadding: Dp? = null,
    onBack: (() -> Unit)? = null,
    titleTrailing: (@Composable () -> Unit)? = null,
    // Off for a screen drawn over a backdrop (the settings sky), where the header's own band of
    // page colour shows as a solid bar across the top.
    opaqueBackground: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val resolvedTopPadding = topPadding ?: if (includeStatusBarPadding) statusBarTop else NuvioTokens.Space.none
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .matchParentSize()
                .then(if (opaqueBackground) Modifier.background(tokens.colors.background) else Modifier)
                .nuvioConsumePointerEvents(),
        ) {}
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = resolvedTopPadding, bottom = NuvioTokens.Space.s4),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(tokens.spacing.controlGap),
            ) {
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = stringResource(Res.string.action_back),
                            tint = tokens.colors.textPrimary,
                        )
                    }
                }
                AnimatedContent(
                    targetState = title,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "screen_header_title",
                ) { currentTitle ->
                    Text(
                        text = currentTitle,
                        style = MaterialTheme.typography.displayLarge,
                        color = tokens.colors.textPrimary,
                    )
                }
                titleTrailing?.invoke()
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(NuvioTokens.Space.s2),
                verticalAlignment = Alignment.CenterVertically,
                content = actions,
            )
        }
    }
}

@Composable
fun NuvioSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.nuvio.colors.textMuted,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun NuvioActionLabel(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val ringShape = RoundedCornerShape(NuvioTokens.Radius.sm)
    Text(
        text = text,
        // Masked so a gradient accent sweeps the label the way it sweeps a filled accent surface;
        // a no-op on the built-in palettes, which paint accents flat. The focus ring is the one
        // settingsNavItem adds around the padded control; drawing another here around the bare
        // glyphs gave a focused action two rings.
        modifier = modifier
            .clip(ringShape)
            .then(
                if (onClick != null) {
                    // A settings section's heading action ("Reset", "Refresh") sits beside the
                    // centred title, where Compose's spatial focus never finds it: Settings walks its
                    // own item ladder, so an action outside it could only be clicked with a pointer.
                    // Registering it as an item puts it on that ladder, without making it the page's
                    // first stop (registerAsEntry = false).
                    Modifier
                        .settingsNavItem(onActivate = onClick, registerAsEntry = false)
                        .clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            // Padding sits inside the ring, so the ring clears the glyphs: drawn at the text's own
            // bounds it shaved the sides of 'Sign out' and 'Reset to Default'.
            .padding(horizontal = NuvioTokens.Space.s8, vertical = NuvioTokens.Space.s2)
            .accentGradientMask(),
        style = MaterialTheme.typography.titleMedium.accentBrush(),
        color = MaterialTheme.nuvio.colors.accent,
    )
}

@Composable
fun NuvioIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.nuvio.colors.textPrimary,
    onClick: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    IconButton(
        modifier = modifier
            .background(
                color = tokens.colors.background.copy(alpha = 0.001f),
                shape = tokens.shapes.avatar,
            ),
        onClick = onClick,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
        )
    }
}

@Composable
fun NuvioBackButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.nuvio.shapes.avatar,
    /** Unspecified (the default) draws the app's glass ([NuvioGlass]) with its light edge; a colour fills it plainly. */
    containerColor: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.nuvio.colors.textPrimary,
    buttonSize: Dp = NuvioTokens.Space.s40,
    iconSize: Dp = NuvioTokens.Icon.md,
    contentDescription: String = stringResource(Res.string.action_back),
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    val glass = containerColor == Color.Unspecified
    Box(
        modifier = modifier
            .size(buttonSize)
            .clip(shape)
            .then(
                if (glass) {
                    // A dark base under the glass keeps the arrow readable over bright artwork.
                    Modifier
                        .background(Color.Black.copy(alpha = 0.28f), shape)
                        .ditheredBackground(NuvioGlass.Fill, shape)
                        .background(if (hovered || focused) Color.White.copy(alpha = 0.08f) else Color.Transparent, shape)
                        .smoothBorder(
                            width = if (focused) 2.dp else 1.dp,
                            color = if (focused) MaterialTheme.nuvio.colors.borderFocus else NuvioGlass.EdgeColor,
                            shape = shape,
                        )
                } else {
                    Modifier.background(containerColor)
                },
            )
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = if (glass) null else LocalIndication.current, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = contentDescription,
            tint = contentColor,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * The app's filled action button.
 *
 * Built on a plain clickable surface rather than a Material [Button] on purpose: Material paints a
 * focus/hover state layer over the container, and because the accent fill here is a brush on the
 * modifier rather than a container colour, that overlay landed on top of the gradient as an inset
 * lighter rectangle — most visible on the button a modal focuses by default. Focus and hover are
 * signalled instead with the same single pass of light the poster cards use.
 */
@Composable
fun NuvioPrimaryButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = NuvioTokens.Space.s48 + NuvioTokens.Space.s4,
    /**
     * Draws the app's 2dp accent ring around the button while the keyboard is on it. Off by
     * default because the sweep of light is already the signal elsewhere; pages whose controls are
     * otherwise plain surfaces ask for the ring so a focused control is never invisible.
     */
    focusRing: Boolean = false,
    onClick: () -> Unit = {},
) {
    val tokens = MaterialTheme.nuvio
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val isHovered by interactionSource.collectIsHoveredAsState()
    // Read from the focus target itself rather than the interaction source: a disabled button's
    // `clickable` emits no interactions, so a ring driven by them disappeared on exactly the
    // control the keyboard was on.
    var hasFocus by remember { mutableStateOf(false) }
    val ringVisible = focusRing && hasFocus && KeyboardInputMode.active
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .onFocusChanged { hasFocus = it.isFocused }
            .nuvioSweepHighlight(
                highlighted = enabled && (isFocused || isHovered),
                cornerRadius = NuvioTokens.Radius.button,
            )
            .clip(tokens.shapes.button)
            // The fill is painted here rather than through buttonColors so a gradient accent can
            // be used; for flat themes accentFill is a SolidColor and this renders identically.
            .background(
                brush = tokens.colors.accentFill,
                shape = tokens.shapes.button,
                alpha = if (enabled) 1f else tokens.opacity.disabled,
            )
            // Painted after the fill, so the stroke is not covered by it. Drawn for a disabled
            // button too: it still has focus, and a focused control that looks unfocused is exactly
            // the dead end this page had.
            .then(
                if (ringVisible) {
                    Modifier.border(
                        width = tokens.borders.medium,
                        color = tokens.colors.borderFocus,
                        shape = tokens.shapes.button,
                    )
                } else {
                    Modifier
                },
            )
            // Focusable and Select-activated, not just clickable: without this the arrows and a
            // pad's D-pad cannot reach the button at all, which is how the sign-in page ended up
            // mouse-only. Existing pointer behaviour is unchanged.
            .selectWithKeyboardOrPointer(
                enabled = enabled,
                interactionSource = interactionSource,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "buttonText",
        ) { animatedText ->
            Text(
                text = animatedText,
                style = MaterialTheme.typography.titleMedium,
                color = tokens.colors.onAccent.copy(
                    alpha = if (enabled) 1f else tokens.opacity.disabled,
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun NuvioInputField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    trailingContent: (@Composable (() -> Unit))? = null,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
) {
    NuvioTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        placeholder = placeholder,
        readOnly = readOnly,
        textStyle = MaterialTheme.typography.bodyLarge,
        trailingContent = trailingContent?.let { content -> { content() } },
        focusRequester = focusRequester,
    )
}

@Composable
fun NuvioInfoBadge(
    text: String,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Box(
        modifier = modifier
            .background(
                color = tokens.colors.surfaceCard,
                shape = tokens.shapes.chip,
            )
            .padding(horizontal = NuvioTokens.Space.s10, vertical = NuvioTokens.Space.s6),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.colors.textMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NuvioInlineMetadata(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textMuted,
        )
        Spacer(modifier = Modifier.width(NuvioTokens.Space.s6))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.nuvio.colors.textPrimary,
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun NuvioStatusModal(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    isVisible: Boolean,
    isBusy: Boolean = false,
    confirmText: String = stringResource(Res.string.action_ok),
    dismissText: String? = null,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    /** An alternative to [confirmText], shown between dismiss and confirm. */
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    if (!isVisible) return
    val tokens = MaterialTheme.nuvio
    // One dismiss path for the dialog itself, the Back key, Escape and a pad's B, all held off while
    // the action is running.
    val dismissRequest: () -> Unit = { if (!isBusy) onDismiss?.invoke() ?: onConfirm() }
    // The dialog opens with the safe action under the keys: Cancel when there is one, since every
    // caller of this dialog is a confirmation whose other buttons change something.
    val firstActionFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val hasDismiss = !isBusy && dismissText != null && onDismiss != null
    val hasSecondary = secondaryText != null && onSecondary != null
    LaunchedEffect(isVisible, isBusy) {
        if (!isVisible || isBusy) return@LaunchedEffect
        withFrameNanos { }
        runCatching { firstActionFocus.requestFocus() }
    }
    val firstActionModifier: Modifier = Modifier.focusRequester(firstActionFocus)

    BasicAlertDialog(
        onDismissRequest = dismissRequest,
    ) {
        DialogBackRegistration(onDismiss = dismissRequest)
        NuvioDialogSurface(modifier = modifier.fillMaxWidth()) {
            Column(
                // A desktop dialog starts with nothing focused and Compose moves focus on Tab only,
                // so the actions below were unreachable with a remote or the arrow keys. With this,
                // the first action takes the keys and Left/Right walk Cancel, the alternative and
                // the confirm button, Enter activates the one that holds them.
                modifier = Modifier
                    .arrowKeyFocusNavigation()
                    .dismissOnBackKey(dismissRequest)
                    .padding(start = tokens.spacing.dialogPadding, end = tokens.spacing.dialogPadding, top = tokens.spacing.dialogPadding, bottom = 8.dp),
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        color = tokens.colors.accent,
                        strokeWidth = NuvioTokens.Border.medium + NuvioTokens.Space.hairline,
                    )
                    Spacer(modifier = Modifier.height(NuvioTokens.Space.s16))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = tokens.colors.textPrimary,
                )
                Spacer(modifier = Modifier.height(tokens.spacing.controlGap))
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = tokens.colors.textMuted,
                )
                Spacer(modifier = Modifier.height(NuvioTokens.Space.s18))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (hasDismiss) {
                        StatusActionButton(
                            text = dismissText,
                            modifier = firstActionModifier,
                            onClick = onDismiss,
                        )
                        Spacer(modifier = Modifier.width(NuvioTokens.Space.s10))
                    }
                    if (secondaryText != null && onSecondary != null) {
                        StatusActionButton(
                            text = secondaryText,
                            // Without a Cancel this is the first button the dialog shows.
                            modifier = if (hasDismiss) Modifier else firstActionModifier,
                            enabled = !isBusy,
                            onClick = onSecondary,
                        )
                        Spacer(modifier = Modifier.width(NuvioTokens.Space.s10))
                    }
                    StatusActionButton(
                        text = confirmText,
                        // The confirm button only leads when it is the only action on the row.
                        modifier = if (hasDismiss || hasSecondary) Modifier else firstActionModifier,
                        enabled = !isBusy,
                        accent = true,
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}

/**
 * One action of [NuvioStatusModal].
 *
 * Material's [Button] paints no focus indicator on the desktop, so a dialog answered with a remote
 * gave no sign of which button Enter would run. The ring is this app's usual one
 * ([NuvioPrimaryButton] draws the same): [NuvioTokens.Borders.medium] of `borderFocus` around the
 * shape, while the keyboard is the input in use. It is drawn by a wrapper rather than on the button
 * itself, because the Button's own fill paints over a border in its modifier chain; the 2dp of room
 * it needs is reserved whether or not the ring shows, so a focused button does not jump.
 */
@Composable
private fun StatusActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    accent: Boolean = false,
) {
    val tokens = MaterialTheme.nuvio
    var hasFocus by remember { mutableStateOf(false) }
    val ringVisible = statusActionRingVisible(hasFocus, KeyboardInputMode.active)
    Box(
        modifier = modifier
            // hasFocus, not isFocused: the focus target is the Button inside this wrapper.
            .onFocusChanged { hasFocus = it.hasFocus }
            .padding(StatusActionRingWidth)
            .then(
                if (ringVisible) {
                    Modifier.border(
                        width = tokens.borders.medium,
                        color = tokens.colors.borderFocus,
                        shape = tokens.shapes.button,
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Button(
            onClick = onClick,
            enabled = enabled,
            shape = tokens.shapes.button,
            colors = if (accent) {
                ButtonDefaults.buttonColors()
            } else {
                ButtonDefaults.buttonColors(
                    containerColor = tokens.colors.surfaceCard,
                    contentColor = tokens.colors.textPrimary,
                )
            },
        ) {
            Text(text)
        }
    }
}

private val StatusActionRingWidth = 2.dp

/**
 * When a modal action shows its focus ring: while it holds focus AND the keyboard or a pad is what
 * is being used. A pointer click focuses a button too, and a ring there would claim a selection the
 * user did not make with the keys.
 */
internal fun statusActionRingVisible(hasFocus: Boolean, keyboardInput: Boolean): Boolean =
    hasFocus && keyboardInput

@Composable
fun NuvioToastHost(
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val toast by NuvioToastController.currentToast.collectAsState()
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val visibilityState = remember { MutableTransitionState(false) }
    var renderedToast by remember { mutableStateOf<NuvioToastMessage?>(null) }

    LaunchedEffect(toast?.id) {
        val currentToast = toast
        if (currentToast != null) {
            renderedToast = currentToast
            visibilityState.targetState = true
            delay(currentToast.durationMillis)
            NuvioToastController.dismiss(currentToast.id)
        } else {
            visibilityState.targetState = false
        }
    }

    LaunchedEffect(
        visibilityState.currentState,
        visibilityState.targetState,
        visibilityState.isIdle,
    ) {
        if (visibilityState.isIdle && !visibilityState.currentState && !visibilityState.targetState) {
            renderedToast = null
        }
    }

    AnimatedVisibility(
        visibleState = visibilityState,
        modifier = modifier,
        enter = fadeIn() + slideInVertically { -it },
        exit = fadeOut() + slideOutVertically { -it },
    ) {
        val currentToast = renderedToast ?: return@AnimatedVisibility
        val isTopEnd = currentToast.placement == NuvioToastPlacement.TopEnd
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = statusBarTop + if (isTopEnd) NuvioTokens.Space.s72 else tokens.spacing.listGap,
                )
                .padding(
                    start = tokens.spacing.screenHorizontal,
                    end = tokens.spacing.screenHorizontal + if (isTopEnd) {
                        NuvioTokens.Space.s24
                    } else {
                        NuvioTokens.Space.none
                    },
                ),
            contentAlignment = if (isTopEnd) Alignment.TopEnd else Alignment.TopCenter,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp),
                shape = RoundedCornerShape(NuvioTokens.Radius.xl),
                color = tokens.colors.surfacePopover,
                tonalElevation = tokens.elevation.raised,
                shadowElevation = tokens.elevation.overlay,
            ) {
                Column(
                    modifier = Modifier.padding(
                        horizontal = NuvioTokens.Space.s16,
                        vertical = NuvioTokens.Space.s12,
                    ),
                ) {
                    currentToast.title?.let { title ->
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.colors.textPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(NuvioTokens.Space.s4))
                    }
                    Text(
                        text = currentToast.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (currentToast.title == null) {
                            tokens.colors.textPrimary
                        } else {
                            tokens.colors.textMuted
                        },
                    )
                }
            }
        }
    }
}

enum class NuvioToastPlacement {
    TopCenter,
    TopEnd,
}

data class NuvioToastMessage(
    val id: Long,
    val message: String,
    val durationMillis: Long,
    val title: String? = null,
    val placement: NuvioToastPlacement = NuvioToastPlacement.TopCenter,
)

object NuvioToastController {
    private val _currentToast = MutableStateFlow<NuvioToastMessage?>(null)
    val currentToast = _currentToast.asStateFlow()
    private var nextToastId = 0L

    fun show(
        message: String,
        durationMillis: Long = 2500L,
        title: String? = null,
        placement: NuvioToastPlacement = NuvioToastPlacement.TopCenter,
    ) {
        nextToastId += 1L
        _currentToast.value = NuvioToastMessage(
            id = nextToastId,
            message = message,
            durationMillis = durationMillis,
            title = title,
            placement = placement,
        )
    }

    fun dismiss(id: Long? = null) {
        val activeToast = _currentToast.value ?: return
        if (id == null || activeToast.id == id) {
            _currentToast.value = null
        }
    }
}
