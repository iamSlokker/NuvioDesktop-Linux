package com.nuvio.app.features.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.build.AppFeaturePolicy
import com.nuvio.app.core.ui.NavAction
import com.nuvio.app.core.ui.NuvioPrimaryButton
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.core.ui.KeyboardInputMode
import com.nuvio.app.core.ui.NuvioTokens
import com.nuvio.app.core.ui.arrowKeyFocusNavigation
import com.nuvio.app.core.ui.navAction
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.nuvioOverlayGradientBrush
import com.nuvio.app.core.ui.nuvioSweepHighlight
import com.nuvio.app.core.ui.selectWithKeyboardOrPointer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_auth_continue_without_account
import nuvio.composeapp.generated.resources.compose_auth_create_account
import nuvio.composeapp.generated.resources.compose_auth_email
import nuvio.composeapp.generated.resources.compose_auth_password
import nuvio.composeapp.generated.resources.compose_auth_sign_in
import nuvio.composeapp.generated.resources.compose_auth_sign_in_subtitle
import nuvio.composeapp.generated.resources.compose_auth_sign_up
import nuvio.composeapp.generated.resources.compose_auth_sign_up_subtitle
import nuvio.composeapp.generated.resources.compose_auth_store_locally
import nuvio.composeapp.generated.resources.compose_auth_tagline
import nuvio.composeapp.generated.resources.compose_auth_terms
import nuvio.composeapp.generated.resources.compose_auth_welcome_back
import org.jetbrains.compose.resources.stringResource

/**
 * The sign-in page at 125%: the density is raised for this page alone, so every dp and sp inside it
 * — fields, buttons, the code, the type — is a quarter larger and lays itself out accordingly.
 *
 * A graphics-layer scale was the first attempt and was wrong twice over: it halves the available
 * width without telling the layout, so text wrapped into a vertical sliver at the card's edge, and
 * it scales about the layer's centre, moving content instead of making room for it.
 */
private const val LoginScale = 1.25f

/**
 * How many times the page tries to put focus on its first control.
 *
 * The request can land before the target has bound its FocusRequester, so a single attempt is a
 * coin flip; a handful of frames is enough for layout to settle and costs nothing when the first
 * one works.
 */
private const val MAX_INITIAL_FOCUS_ATTEMPTS = 8

/** Equal margins on the narrow layout, where the page is one centred column. */
private val AuthPageMargin = 20.dp

/**
 * Widest the two cards together get, before [LoginScale]. Narrower than the old centred page
 * because the cards now live in a rail beside the brand rather than owning the whole window.
 */
private val AuthContentMaxWidth = 520.dp

/** Narrowest window that shows the brand beside the sign-in rail instead of stacking them. */
private val WideLayoutMinWidth = 1120.dp

/** The rail's width, clamped so the cards fit at the smallest wide window and it stays a rail. */
private val AuthRailMinWidth = 730.dp
private val AuthRailMaxWidth = 800.dp
private val AuthRailWidthFraction = 0.38f

private val CardGap = 14.dp
private val SectionGap = 14.dp
private val ActionRowHeight = 48.dp

private val HeroWordmarkWidth = 250.dp
private val HeroWordmarkHeight = 80.dp
private val HeroHorizontalPadding = 56.dp

/** Test handles for the two cards, so their bound heights can be compared as layout, not pixels. */
internal const val AuthFormCardTag = "auth-form-card"
internal const val AuthCodeCardTag = "auth-code-card"
internal const val AuthEmailFieldTag = "auth-email"
internal const val AuthPasswordFieldTag = "auth-password"
internal const val AuthSignInTag = "auth-sign-in"
internal const val AuthToggleModeTag = "auth-toggle-mode"
internal const val AuthContinueWithoutAccountTag = "auth-continue-without-account"
internal const val AuthBrandTag = "auth-brand"

/**
 * The focus stops on the page, laid out the way they are drawn:
 *
 * ```
 * email
 * password
 * [ Sign In ][ Sign Up ]
 * [ Refresh ][ Continue Without Account ]
 * ```
 *
 * Held together so each control's Down knows the same next stop, and so the page has one place that
 * describes its navigation. Up leaves a field; Left and Right inside a field stay with its caret.
 */
private data class AuthFocus(
    val email: FocusRequester,
    val password: FocusRequester,
    val signIn: FocusRequester,
    val signUp: FocusRequester,
    val refresh: FocusRequester,
    val continueWithoutAccount: FocusRequester,
    val hasQr: Boolean,
) {
    /** First control below the form: the code's refresh button, or Continue Without Account. */
    val belowForm: FocusRequester get() = if (hasQr) refresh else continueWithoutAccount
}

@Composable
fun AuthScreen(
    modifier: Modifier = Modifier,
) {
    val authError by AuthRepository.error.collectAsStateWithLifecycle()
    val serverConnectionState by ServerConnectionController.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    // Pairing is offered only where the backend advertises it (custom servers may be older).
    val supportsQrLogin = serverConnectionState.activeServer.capabilities.tvLogin
    val qrController = remember { QrLoginController() }
    val qrState by qrController.state.collectAsStateWithLifecycle()
    // The code is the first thing on the screen, so it starts with the page and survives the email
    // form being used next to it. It is dropped only when the whole screen goes away.
    LaunchedEffect(supportsQrLogin) {
        if (supportsQrLogin) qrController.start() else qrController.cancel()
    }
    DisposableEffect(qrController) {
        onDispose { qrController.cancel() }
    }
    var isSignUp by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isLoading by rememberSaveable { mutableStateOf(false) }
    var emailFieldBounds by remember { mutableStateOf<Rect?>(null) }
    var passwordFieldBounds by remember { mutableStateOf<Rect?>(null) }
    var showServerDialog by rememberSaveable { mutableStateOf(false) }
    var showOfficialServerDialog by rememberSaveable { mutableStateOf(false) }

    // A completed switch resets the controller to a fresh state, which clears discoveredServer.
    // Without also clearing these flags the URL prompt springs straight back open behind the
    // closing trust dialog, so a successful switch looks like nothing happened.
    LaunchedEffect(serverConnectionState.activeServer.backendUrl) {
        showServerDialog = false
        showOfficialServerDialog = false
    }

    val submit: () -> Unit = {
        isLoading = true
        scope.launch {
            if (isSignUp) AuthRepository.signUpWithEmail(email, password)
            else AuthRepository.signInWithEmail(email, password)
            isLoading = false
        }
    }

    val emailFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val signInFocus = remember { FocusRequester() }
    val signUpFocus = remember { FocusRequester() }
    val refreshFocus = remember { FocusRequester() }
    val continueFocus = remember { FocusRequester() }
    val focus = AuthFocus(
        email = emailFocus,
        password = passwordFocus,
        signIn = signInFocus,
        signUp = signUpFocus,
        refresh = refreshFocus,
        continueWithoutAccount = continueFocus,
        hasQr = supportsQrLogin,
    )

    /*
     * Land on the first action a pad or a remote can reach without typing: Refresh QR, or Continue
     * Without Account when this server has no pairing. Starting in the email field put the
     * keyboard's opening move in a text field, so the very first D-pad press typed a letter
     * instead of moving.
     *
     * `requestFocus()` does not throw when its FocusRequester is not yet bound to a node; it
     * silently does nothing. `onGloballyPositioned` can therefore fire a frame before the button
     * inside the wrapper has bound its requester, and a one-shot flag would record "done" while
     * the page still had no focus. That is why the page looked navigable on some launches and dead
     * on others.
     *
     * So the request is retried until the target reports focus through `onFocusChanged`, not until
     * the first call returns. `delay` rather than `withFrameNanos`: a headless scene never advances
     * its frame clock, and the retry must not depend on one.
     */
    var initialTargetFocused by remember { mutableStateOf(false) }
    // Any real key or pointer press means the person is driving the page now. From then on the page
    // must never take focus back on its own.
    var userInteractedWithAuth by remember { mutableStateOf(false) }
    // The control the keyboard was opened from. Restored when the keyboard is explicitly dismissed.
    var lastFocusedAuthRequester by remember { mutableStateOf<FocusRequester?>(null) }
    val requestInitialFocus: () -> Unit = request@{
        if (userInteractedWithAuth || initialTargetFocused) return@request
        val target = if (supportsQrLogin) refreshFocus else continueFocus
        runCatching { target.requestFocus() }
    }
    // Keep asserting focus through the startup transition. The page is composed windowed and the
    // saved fullscreen placement is applied a moment later; that window-state change clears the
    // Compose focus that was just set. A one-shot request therefore lands and is immediately lost,
    // and every pad press after that has no focused control to reach. Re-request while the target
    // is not focused, but stop the moment the person touches a key or the pointer.
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(supportsQrLogin, windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused) return@LaunchedEffect
        repeat(MAX_INITIAL_FOCUS_ATTEMPTS * 5) {
            if (userInteractedWithAuth || initialTargetFocused) return@LaunchedEffect
            requestInitialFocus()
            delay(50)
        }
    }
    // The on-screen keyboard is its own popup window. Closing it can leave Compose with no focus
    // owner, so put the keys back on the control the keyboard was opened from.
    LaunchedEffect(supportsQrLogin) {
        AuthFocusRestore.requests.collect {
            val target = lastFocusedAuthRequester
                ?: if (supportsQrLogin) refreshFocus else continueFocus
            runCatching { target.requestFocus() }
        }
    }

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val baseDensity = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            /*
             * Escape and the pad's Back button raise the three-dot menu.
             *
             * That menu holds the on-screen keyboard toggle, and on a couch PC it is the only way
             * to type a password — so it has to be reachable from the pad alone. Preview phase, so
             * a text field cannot swallow the key first; the field keeps Escape for clearing its
             * own text only while it is actually being typed in.
             */
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    userInteractedWithAuth = true
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (com.nuvio.app.core.ui.TextInputFocusTracker.active.value) {
                    return@onPreviewKeyEvent false
                }
                when (event.navAction()) {
                    NavAction.Dismiss, NavAction.Back -> {
                        // While the menu is up, Escape belongs to it: the popup dismisses itself and
                        // hands focus back. Re-opening it here is what left the menu permanently
                        // open and the page's arrows permanently dead.
                        if (AuthMenuRequest.isOpen) return@onPreviewKeyEvent false
                        AuthMenuRequest.request()
                        true
                    }
                    else -> false
                }
            }
            .pointerInput(emailFieldBounds, passwordFieldBounds) {
                awaitEachGesture {
                    val down = awaitFirstDown(
                        requireUnconsumed = false,
                        pass = PointerEventPass.Initial,
                    )
                    userInteractedWithAuth = true
                    val tappedTextField = listOfNotNull(emailFieldBounds, passwordFieldBounds)
                        .any { bounds -> bounds.contains(down.position) }
                    if (!tappedTextField) {
                        focusManager.clearFocus(force = true)
                    }
                }
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brush = nuvioOverlayGradientBrush()),
        )

        val wide = maxWidth >= WideLayoutMinWidth
        val railWidth = (maxWidth * AuthRailWidthFraction)
            .coerceIn(AuthRailMinWidth, AuthRailMaxWidth)

        // One density, one layout: raising it makes every dp on this page a quarter larger and
        // gives the layout a correspondingly smaller viewport, so nothing is clipped or wrapped
        // into a column the way a graphics-layer scale would.
        val authContent: @Composable ColumnScope.() -> Unit = {
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density * LoginScale),
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = AuthContentMaxWidth)
                        .fillMaxWidth()
                        /*
                         * The arrows walk this page by geometry, exactly as they do on Settings and
                         * the tab pages: Compose Desktop moves focus on Tab only, so a screen built
                         * from ordinary focusable controls needs this to answer a D-pad at all.
                         *
                         * This replaced a hand-written graph of explicit requesters. That graph
                         * only ever described one path: Refresh went *sideways* to Continue Without
                         * Account, Continue's Down was unhandled, and the email/password fields were
                         * therefore unreachable by walking down — the reported "Down leaves the
                         * password with focus nowhere". Geometry cannot dead-end that way, and it
                         * follows the layout when the window changes shape.
                         *
                         * A text field keeps its own Left/Right for the caret (see the field's
                         * preview handler), and the row's buttons keep their explicit Left/Right so
                         * the two halves of the action row stay in step.
                         */
                        .arrowKeyFocusNavigation(releaseWhenStuck = true)
                        // If focus lands on any control other than the initial target, the person
                        // has taken over: stop the startup retry so it cannot pull focus back.
                        .onFocusChanged { state ->
                            if (state.hasFocus && !initialTargetFocused) userInteractedWithAuth = true
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    if (!wide) {
                        CompactAuthWordmark(
                            modifier = Modifier
                                .width(HeroWordmarkWidth)
                                .height(HeroWordmarkHeight),
                            widthFraction = 1f,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = stringResource(Res.string.compose_auth_tagline),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    val formContent: @Composable () -> Unit = {
                        EmailSignInCard(
                            isSignUp = isSignUp,
                            email = email,
                            password = password,
                            isLoading = isLoading,
                            error = authError,
                            onEmailChange = { email = it },
                            onPasswordChange = { password = it },
                            onEmailBounds = { emailFieldBounds = it },
                            onPasswordBounds = { passwordFieldBounds = it },
                            onSubmit = submit,
                            onToggleMode = {
                                isSignUp = !isSignUp
                                AuthRepository.clearError()
                            },
                            onControlFocused = { lastFocusedAuthRequester = it },
                            focus = focus,
                        )
                    }
                    val codeContent: @Composable () -> Unit = {
                        // Centred in the card's height rather than pinned to its top: the code card
                        // is as tall as the form beside it, and the symbol should sit in the middle
                        // of that space.
                        Column(
                            modifier = Modifier.fillMaxHeight(),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            QrLoginPanel(
                                state = qrState,
                                onRefresh = qrController::refresh,
                                // The page draws the refresh action in the row beside Continue
                                // Without Account, matching the layout of the phone app.
                                showRefreshButton = false,
                            )
                        }
                    }

                    if (wide) {
                        /*
                         * Two cards, one height: the taller of the two.
                         *
                         * A Row cannot do this by itself. Its `weight()` children are laid out
                         * against the row's incoming constraints before intrinsic heights are
                         * consulted, so with `IntrinsicSize.Max` the *shorter* card decided the row
                         * and the taller one was clipped — which is exactly how the countdown and the
                         * refresh button vanished from the bottom of the code card while the page
                         * still looked tidy. Measured first, then laid out, instead.
                         */
                        EqualHeightCards(
                            modifier = Modifier.fillMaxWidth(),
                            gap = CardGap,
                            firstWeight = 1.2f,
                            first = { cardModifier ->
                                NuvioSurfaceCard(
                                    cardModifier
                                        .fillMaxHeight()
                                        .testTag(AuthFormCardTag),
                                ) { formContent() }
                            },
                            second = if (supportsQrLogin) {
                                { cardModifier ->
                                    NuvioSurfaceCard(
                                        cardModifier
                                            .fillMaxHeight()
                                            .testTag(AuthCodeCardTag),
                                    ) { codeContent() }
                                }
                            } else {
                                null
                            },
                        )
                    } else {
                        // Too narrow for two columns: the same content stacked, at its natural size.
                        NuvioSurfaceCard(Modifier.testTag(AuthFormCardTag)) { formContent() }
                        if (supportsQrLogin) {
                            Spacer(modifier = Modifier.height(CardGap))
                            NuvioSurfaceCard(Modifier.testTag(AuthCodeCardTag)) { codeContent() }
                        }
                    }

                    Spacer(modifier = Modifier.height(SectionGap))

                    Text(
                        text = stringResource(Res.string.compose_auth_terms),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )

                    Spacer(modifier = Modifier.height(SectionGap))

                    // The page's own action row, as on the phone app: the code's refresh beside the
                    // way past signing in. Both are ordinary focus stops in the walk above.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (supportsQrLogin) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .onGloballyPositioned { requestInitialFocus() },
                            ) {
                                QrRefreshButton(
                                    enabled = !qrState.isBusy,
                                    onClick = qrController::refresh,
                                    requester = refreshFocus,
                                    // Passed as its own parameter, not on `modifier`: the button
                                    // applies the click/key wiring to `modifier` first, and a preview
                                    // handler placed there sits outside that node where `focusable()`
                                    // can take the arrow before it runs. Applied last, this handler's
                                    // preview phase runs first and the page's own Down reaches it.
                                    onKeyNavigation = Modifier
                                        .onFocusChanged {
                                            initialTargetFocused = it.isFocused
                                            if (it.isFocused) lastFocusedAuthRequester = refreshFocus
                                        }
                                        .onPreviewKeyEvent { event ->
                                            if (event.type != KeyEventType.KeyDown) {
                                                return@onPreviewKeyEvent false
                                            }
                                            when (event.navAction()) {
                                                NavAction.Up -> {
                                                    signInFocus.requestFocus()
                                                    true
                                                }
                                                // The row's bottom edge: wrap to the top of the form.
                                                // Geometry finds nothing beneath this row in either
                                                // layout, and a page the arrows cannot leave reads as
                                                // "navigation is broken" — the report this replaces.
                                                NavAction.Down -> {
                                                    emailFocus.requestFocus()
                                                    true
                                                }
                                                NavAction.Left -> {
                                                    signInFocus.requestFocus()
                                                    true
                                                }
                                                NavAction.Right -> {
                                                    continueFocus.requestFocus()
                                                    true
                                                }
                                                else -> false
                                            }
                                        },
                                )
                            }
                        }
                        AuthPillButton(
                            text = stringResource(Res.string.compose_auth_continue_without_account),
                            enabled = !isLoading,
                            focusRequester = continueFocus,
                            onClick = { AuthRepository.signInAnonymously() },
                            modifier = Modifier
                                .weight(if (supportsQrLogin) 1.35f else 1f)
                                .testTag(AuthContinueWithoutAccountTag)
                                .then(
                                    if (supportsQrLogin) Modifier
                                    else Modifier.onGloballyPositioned { requestInitialFocus() },
                                ),
                            onKeyNavigation = Modifier
                                .onFocusChanged {
                                    initialTargetFocused = it.isFocused
                                    if (it.isFocused) lastFocusedAuthRequester = continueFocus
                                }
                                .onPreviewKeyEvent { event ->
                                    if (event.type != KeyEventType.KeyDown) {
                                        return@onPreviewKeyEvent false
                                    }
                                    when (event.navAction()) {
                                        NavAction.Up -> {
                                            signUpFocus.requestFocus()
                                            true
                                        }
                                        // The row's bottom edge: wrap to the top of the form, matching
                                        // the refresh button beside it.
                                        NavAction.Down -> {
                                            emailFocus.requestFocus()
                                            true
                                        }
                                        NavAction.Left -> {
                                            (if (supportsQrLogin) refreshFocus else signInFocus)
                                                .requestFocus()
                                            true
                                        }
                                        else -> false
                                    }
                                },
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = stringResource(Res.string.compose_auth_store_locally),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        if (wide) {
            Row(modifier = Modifier.fillMaxSize()) {
                BrandPanel(modifier = Modifier.weight(1f).fillMaxHeight())
                AuthRail(
                    modifier = Modifier.width(railWidth).fillMaxHeight(),
                    content = authContent,
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(AuthPageMargin)
                    // Last resort only: if a window is dragged smaller than the content needs, the
                    // page scrolls rather than clipping the action row off the bottom.
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                authContent()
            }
        }

        if (AppFeaturePolicy.customServerConnectionsEnabled) {
            ServerConnectionMenu(
                activeServer = serverConnectionState.activeServer,
                onUseOfficial = {
                    ServerConnectionController.resetDiscovery()
                    showOfficialServerDialog = true
                },
                onConnectCustom = {
                    ServerConnectionController.resetDiscovery()
                    showServerDialog = true
                },
                onMenuFocused = { userInteractedWithAuth = true },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 8.dp, top = statusBarTop + 4.dp),
            )
        }
    }

    if (AppFeaturePolicy.customServerConnectionsEnabled) {
        // The URL prompt gives way to the trust dialog as soon as discovery succeeds, so the two
        // are mutually exclusive rather than stacked.
        if (showServerDialog && serverConnectionState.discoveredServer == null) {
            ServerConnectionDialog(
                state = serverConnectionState,
                onDiscover = ServerConnectionController::discover,
                onDismiss = {
                    showServerDialog = false
                    ServerConnectionController.resetDiscovery()
                },
            )
        }

        serverConnectionState.discoveredServer?.let { server ->
            ServerTrustDialog(
                server = server,
                isSwitching = serverConnectionState.isSwitching,
                switchFailure = serverConnectionState.switchFailure,
                onConfirm = ServerConnectionController::connectDiscovered,
                onDismiss = {
                    showServerDialog = false
                    ServerConnectionController.resetDiscovery()
                },
            )
        }

        if (showOfficialServerDialog) {
            OfficialServerDialog(
                isSwitching = serverConnectionState.isSwitching,
                switchFailure = serverConnectionState.switchFailure,
                onConfirm = ServerConnectionController::useOfficial,
                onDismiss = {
                    showOfficialServerDialog = false
                    ServerConnectionController.resetDiscovery()
                },
            )
        }
    }
}

/** The brand half: the lockup and the line under it, left-aligned and centred in its column. */
@Composable
private fun BrandPanel(modifier: Modifier = Modifier) {
    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(horizontal = HeroHorizontalPadding)
                .testTag(AuthBrandTag),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            CompactAuthWordmark(
                modifier = Modifier
                    .width(HeroWordmarkWidth)
                    .height(HeroWordmarkHeight),
                widthFraction = 1f,
            )
            Text(
                text = stringResource(Res.string.compose_auth_tagline),
                style = MaterialTheme.typography.displaySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.widthIn(max = 420.dp),
            )
        }
    }
}

/**
 * The sign-in half of the wide layout. A dark pane with one lit edge, so the brand and the controls
 * read as two columns rather than one page with a gap in it.
 */
@Composable
private fun AuthRail(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier.background(Color.Black.copy(alpha = 0.4f)),
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.35f)),
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

/**
 * Email/password half of the page: its own card, so it sits beside the pairing code as a peer rather
 * than one conditional branch of a shared card.
 */
@Composable
private fun EmailSignInCard(
    isSignUp: Boolean,
    email: String,
    password: String,
    isLoading: Boolean,
    error: String?,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onEmailBounds: (Rect) -> Unit,
    onPasswordBounds: (Rect) -> Unit,
    onSubmit: () -> Unit,
    onToggleMode: () -> Unit,
    onControlFocused: (FocusRequester) -> Unit,
    focus: AuthFocus,
) {
    AnimatedContent(
        targetState = isSignUp,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "emailHeading",
    ) { signUp ->
        Text(
            text = if (signUp) stringResource(Res.string.compose_auth_create_account)
            else stringResource(Res.string.compose_auth_welcome_back),
            style = MaterialTheme.typography.headlineLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(modifier = Modifier.height(4.dp))
    AnimatedContent(
        targetState = isSignUp,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "emailSubtitle",
    ) { signUp ->
        Text(
            text = if (signUp) stringResource(Res.string.compose_auth_sign_up_subtitle)
            else stringResource(Res.string.compose_auth_sign_in_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Spacer(modifier = Modifier.height(18.dp))

    NuvioTextField(
        value = email,
        onValueChange = {
            onEmailChange(it)
            AuthRepository.clearError()
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AuthEmailFieldTag)
            .onPreviewKeyEvent { event ->
                // Preview phase, not bubble: BasicTextField consumes the arrow for its caret before
                // an onKeyEvent on this modifier would ever see it. Down means "next control" here,
                // so the walk can leave the field the page lands on. Left and Right are left alone:
                // they belong to the caret while this field is being typed in.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.navAction()) {
                    NavAction.Down -> {
                        focus.password.requestFocus()
                        true
                    }
                    // Up from the top of the form wraps to the action row at the bottom, so the
                    // page is a closed loop in both layouts instead of dead-ending at its edges.
                    NavAction.Up -> {
                        focus.belowForm.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            .onGloballyPositioned { coordinates ->
                onEmailBounds(coordinates.boundsInRoot())
            },
        placeholder = stringResource(Res.string.compose_auth_email),
        textStyle = MaterialTheme.typography.bodyLarge,
        keyboardType = KeyboardType.Email,
        imeAction = ImeAction.Next,
        onImeAction = { focus.password.requestFocus() },
        // The field puts this on its inner text node: a requester attached to the outer box never
        // reaches the thing that can actually hold focus.
        focusRequester = focus.email,
        onFocusChanged = { if (it) onControlFocused(focus.email) },
    )

    Spacer(modifier = Modifier.height(10.dp))

    NuvioTextField(
        value = password,
        onValueChange = {
            onPasswordChange(it)
            AuthRepository.clearError()
        },
        modifier = Modifier
            .fillMaxWidth()
            .testTag(AuthPasswordFieldTag)
            .onPreviewKeyEvent { event ->
                // Preview phase: the field consumes arrows for its caret before a bubble handler runs.
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.navAction()) {
                    NavAction.Down -> {
                        focus.signIn.requestFocus()
                        true
                    }
                    NavAction.Up -> {
                        focus.email.requestFocus()
                        true
                    }
                    else -> false
                }
            }
            .onGloballyPositioned { coordinates ->
                onPasswordBounds(coordinates.boundsInRoot())
            },
        placeholder = stringResource(Res.string.compose_auth_password),
        secret = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        onImeAction = {
            if (email.isNotBlank() && password.isNotBlank() && !isLoading) onSubmit()
        },
        // Enter on an empty/invalid form must not drop focus: the page would then have no focused
        // control and every D-pad press would go nowhere. A valid submit changes the screen anyway.
        clearFocusOnDone = false,
        focusRequester = focus.password,
        onFocusChanged = { if (it) onControlFocused(focus.password) },
    )

    error?.let { errorText ->
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = errorText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Two buttons rather than a button and a line of small text: the mode switch is a control, and
    // a control has to look pressable and take the keyboard's focus ring like the rest.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NuvioPrimaryButton(
            text = if (isLoading) {
                ""
            } else if (isSignUp) {
                stringResource(Res.string.compose_auth_create_account)
            } else {
                stringResource(Res.string.compose_auth_sign_in)
            },
            enabled = email.isNotBlank() && password.length >= 6 && !isLoading,
            focusRing = true,
            onClick = onSubmit,
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus.signIn)
                .testTag(AuthSignInTag)
                .onFocusChanged { if (it.isFocused) onControlFocused(focus.signIn) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.navAction()) {
                        NavAction.Up -> {
                            focus.password.requestFocus()
                            true
                        }
                        // Right stays explicit so the two halves of this row keep their order.
                        NavAction.Right -> {
                            focus.signUp.requestFocus()
                            true
                        }
                        // Down is deliberately released: geometry finds the action row below the
                        // form, and releasing means an edge case cannot swallow the key.
                        else -> false
                    }
                },
        )
        NuvioSecondaryButton(
            text = if (isSignUp) {
                stringResource(Res.string.compose_auth_sign_in)
            } else {
                stringResource(Res.string.compose_auth_sign_up)
            },
            onClick = onToggleMode,
            focusRequester = focus.signUp,
            modifier = Modifier
                .weight(1f)
                .testTag(AuthToggleModeTag)
                .onFocusChanged { if (it.isFocused) onControlFocused(focus.signUp) }
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.navAction()) {
                        NavAction.Up -> {
                            focus.password.requestFocus()
                            true
                        }
                        NavAction.Left -> {
                            focus.signIn.requestFocus()
                            true
                        }
                        // Down released to the page walker, as on Sign In above.
                        else -> false
                    }
                },
        )
    }

    if (isLoading) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(top = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
                strokeWidth = 2.5.dp,
            )
        }
    }
}

/**
 * The mode switch beside Sign In. Dark like the page's other secondary surfaces, with the same 2dp
 * accent ring the app draws on a focused control.
 */
@Composable
private fun NuvioSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onKeyNavigation: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(NuvioTokens.Radius.button)
    val interactionSource = remember { MutableInteractionSource() }
    var hasFocus by remember { mutableStateOf(false) }
    val ringVisible = hasFocus && KeyboardInputMode.active
    Box(
        modifier = modifier
            .height(ActionRowHeight + 4.dp)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { hasFocus = it.isFocused }
            .nuvioSweepHighlight(highlighted = ringVisible, cornerRadius = NuvioTokens.Radius.button)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (ringVisible) tokens.borders.medium else tokens.borders.thin,
                color = if (ringVisible) tokens.colors.borderFocus else tokens.colors.borderSubtle,
                shape = shape,
            )
            .selectWithKeyboardOrPointer(
                enabled = true,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            // Inside the click/key node, so its preview phase sees the arrow first.
            .then(onKeyNavigation),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The way past signing in, drawn as the light pill from the phone app. A plain surface with the
 * same focus ring as everything else rather than a Material Button, whose state layer would paint
 * over the fill.
 */
@Composable
private fun AuthPillButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onKeyNavigation: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val shape = RoundedCornerShape(percent = 50)
    val interactionSource = remember { MutableInteractionSource() }
    var hasFocus by remember { mutableStateOf(false) }
    val ringVisible = hasFocus && KeyboardInputMode.active
    Box(
        modifier = modifier
            .height(ActionRowHeight)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { hasFocus = it.isFocused }
            .clip(shape)
            .background(
                tokens.colors.textPrimary.copy(alpha = if (enabled) 1f else tokens.opacity.disabled),
            )
            .border(
                width = if (ringVisible) tokens.borders.medium else 0.dp,
                color = if (ringVisible) tokens.colors.borderFocus else Color.Transparent,
                shape = shape,
            )
            .selectWithKeyboardOrPointer(
                enabled = enabled,
                interactionSource = interactionSource,
                onClick = onClick,
            )
            // Inside the click/key node, so its preview phase sees the arrow first.
            .then(onKeyNavigation),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.colors.textInverse,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

private fun LayoutCoordinates.boundsInRoot(): Rect {
    val position = positionInRoot()
    return Rect(
        left = position.x,
        top = position.y,
        right = position.x + size.width,
        bottom = position.y + size.height,
    )
}

/**
 * Two cards side by side at the same height, each keeping its own width share.
 *
 * A plain [Row] cannot express this: `weight()` children are measured against the row's incoming
 * constraints before their own heights matter, so with `IntrinsicSize.Max` the *shorter* card
 * decided the row and the taller one was clipped — which is how the countdown and the refresh
 * button disappeared off the bottom of the code card while the page still looked tidy.
 *
 * Measured in two passes on the *arguments* rather than the children: each card reports the height
 * it needs (free height), the taller wins, and both are then given exactly that height. The children
 * are composed once and measured once each, for the final constraints only.
 *
 * [second] may be null, in which case the first card simply takes the full width.
 */
@Composable
internal fun EqualHeightCards(
    modifier: Modifier = Modifier,
    gap: Dp,
    firstWeight: Float,
    first: @Composable (Modifier) -> Unit,
    second: (@Composable (Modifier) -> Unit)?,
) {
    if (second == null) {
        Box(modifier) { first(Modifier.fillMaxWidth()) }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Max),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Top,
    ) {
        CardColumn(weight = firstWeight, content = first)
        CardColumn(weight = 1f, content = second)
    }
}

/**
 * One equal-height slot.
 *
 * The card is measured with a free height inside a `Layout` whose own height reports that natural
 * value; the Row then resolves to the taller of the two and the card is drawn to the row's height
 * because its surface carries `fillMaxHeight`.
 */
@Composable
private fun RowScope.CardColumn(
    weight: Float,
    content: @Composable (Modifier) -> Unit,
) {
    Box(modifier = Modifier.weight(weight).fillMaxHeight()) {
        content(Modifier.fillMaxWidth().fillMaxHeight())
    }
}
