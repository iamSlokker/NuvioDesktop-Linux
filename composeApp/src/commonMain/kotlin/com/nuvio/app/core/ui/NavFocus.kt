package com.nuvio.app.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusTargetModifierNode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Keyboard/gamepad hand-off between page content and the desktop sidebar.
 *
 * Content keeps its own focus model (arrow keys move app-level indices, not Compose focus), so
 * the sidebar cannot be reached by focus traversal. Content asks for the sidebar with
 * [enterSidebar] and the sidebar gives focus back with [leaveSidebar]. Gamepad input arrives as
 * ordinary key events, so nothing here is gamepad-specific.
 */
object RootNavFocus {
    @Volatile
    var sidebarAvailable: Boolean = false

    /**
     * The sidebar's counterpart above the page: a bar drawn across the top of the screen with its row
     * of tabs. Up at the top edge of a page reaches it, Left and Right walk it, Down, Back or Dismiss
     * give the keys back.
     *
     * Two flags rather than one, because Back means different things at the two bars. This one is the
     * floating bar, drawn over every tab but Settings: there Back belongs to it once the page has
     * nothing left to close. The Settings screen's own row sets [settingsTopBarAvailable] instead:
     * Back there goes up a level and then back to the tab Settings was opened from, so the row must
     * not take it ([enterFloatingTopBar]). Up is the same at both ([enterTopBar]).
     */
    @Volatile
    var topBarAvailable: Boolean = false

    /** The Settings screen's own row of tab icons; see [topBarAvailable]. */
    @Volatile
    var settingsTopBarAvailable: Boolean = false

    /** Whether either bar across the top is drawn, which is what decides if Up has anywhere to go. */
    val anyTopBarDrawn: Boolean
        get() = topBarAvailable || settingsTopBarAvailable

    /**
     * Set by a screen that closes itself as it asks for the bar (game mode is drawn over the bar), so
     * the app's focus restore after that close stands aside instead of taking the bar's focus back.
     * Cleared by whoever reads it.
     */
    @Volatile
    var handoffPending: Boolean = false

    private val enter = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val enterTop = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private val leave = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)

    val enterRequests: SharedFlow<Unit> = enter.asSharedFlow()

    /** The top bar's counterpart of [enterRequests]. */
    val topBarEnterRequests: SharedFlow<Unit> = enterTop.asSharedFlow()

    /**
     * One emission per [leaveSidebar]: whether the page should get the keys back. False when the
     * caller has something else ready to take them (the Games row opens game mode over the bar).
     */
    val leaveRequests: SharedFlow<Boolean> = leave.asSharedFlow()

    /** Returns true when a sidebar is shown and took the request. */
    fun enterSidebar(): Boolean = sidebarAvailable && enter.tryEmit(Unit)

    /** Up reaches whichever bar across the top is drawn, so this asks for that one. */
    fun enterTopBar(): Boolean = anyTopBarDrawn && enterTop.tryEmit(Unit)

    /**
     * Back and Escape reach only the floating bar. On Settings they belong to the page's own ladder —
     * up a level, then back to the tab Settings was opened from — so the row drawn there must not take
     * them.
     */
    fun enterFloatingTopBar(): Boolean = topBarAvailable && enterTop.tryEmit(Unit)

    /**
     * [enterSidebar] for a screen drawn over the bar that closes itself as it asks (game mode). The
     * close restores the content's focus a moment later; [handoffPending] tells that restore to stand
     * aside so it does not take the bar's focus back.
     */
    fun enterSidebarAfterClosing(): Boolean {
        if (!sidebarAvailable) return false
        handoffPending = true
        if (enterSidebar()) return true
        handoffPending = false
        return false
    }

    /**
     * The bar is done with the keys and gives them back to the page it sits beside, which is what Back,
     * Right, Escape and Dismiss on the bar do, and what selecting the row of the page already open
     * does: a step out of that page rather than a dead end in the bar.
     *
     * [restoreContent] false for the one row that has something else ready to take the keys — Games
     * opens game mode over the bar — so the page is not focused first and then immediately left again.
     */
    fun leaveSidebar(restoreContent: Boolean = true) {
        leave.tryEmit(restoreContent)
    }

    /** The top bar's counterpart of [leaveSidebar]: Down, Back, Dismiss and Right on the bar. */
    fun leaveTopBar(restoreContent: Boolean = true) {
        leave.tryEmit(restoreContent)
    }

    /** The page whose control held the keys when the bar took them, and that control. */
    private var contentOwner: Any? = null
    private var contentControl: FocusTargetModifierNode? = null

    /**
     * Remembers the control the page had as the bar takes the keys, so [leaveSidebar] can put the
     * keyboard back exactly where the person left it rather than on the page's first control.
     *
     * [owner] names the page the control belongs to (the bar passes the selected tab). A control from a
     * page the person has since navigated away from is never restored: the page it belonged to can
     * still be composed behind the one now showing. Must run before the bar focuses itself, while the
     * page still holds the keys.
     */
    fun rememberContentFocus(owner: Any?, manager: FocusManager) {
        contentOwner = owner
        contentControl = manager.focusedControl()
    }

    fun forgetContentFocus() {
        contentOwner = null
        contentControl = null
    }

    /**
     * Gives the keys back to the control remembered for [owner]'s page. False when there is nothing
     * to give them back to — the bar was entered with nothing focused, the control has left the screen,
     * or the person selected another page while in the bar — which leaves the caller to find the page
     * itself. Always clears what was remembered.
     */
    fun restoreContentFocus(owner: Any?): Boolean {
        val control = contentControl
        val sameOwner = contentOwner == owner
        forgetContentFocus()
        if (!sameOwner || control == null || !control.node.isAttached) return false
        return runCatching { control.requestFocus() }.getOrDefault(false)
    }
}

/**
 * The page's own way back in from the bar, for the bar to compose while it is drawn: the keys go back
 * to the control the page had when the bar took them (remembered by [RootNavFocus.rememberContentFocus]),
 * so a person who stepped out of a row lands back on that row instead of at the top of the page.
 *
 * When that control has since left the screen — the page reloaded, the row it was on went away, the bar
 * was reached by pointer rather than from the page — the bar is drawn left of the page, so the page's
 * own controls to the right take the keys. Leaving the bar therefore always ends up in the page beside
 * it, which is what Back, Dismiss, Right and the row of the page already open are for.
 *
 * [sidebarHasFocus] keeps a leave that arrives after the page has already taken the keys from moving
 * focus on again, and [pageOwner] names the page the keyboard was remembered for.
 */
@Composable
internal fun SidebarLeaveRestoresContent(
    sidebarHasFocus: () -> Boolean,
    pageOwner: () -> Any?,
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusManager) {
        RootNavFocus.leaveRequests.collect { restoreContent ->
            if (!restoreContent || !sidebarHasFocus()) return@collect
            if (RootNavFocus.restoreContentFocus(pageOwner())) return@collect
            focusManager.moveFocus(FocusDirection.Right)
        }
    }
}

/**
 * The top bar's counterpart of [SidebarLeaveRestoresContent]. The bar is drawn above the page, so with
 * nothing remembered the keys go down into the page rather than to the right.
 */
@Composable
internal fun TopBarLeaveRestoresContent(
    topBarHasFocus: () -> Boolean,
    pageOwner: () -> Any?,
) {
    val focusManager = LocalFocusManager.current
    LaunchedEffect(focusManager) {
        RootNavFocus.leaveRequests.collect { restoreContent ->
            if (!restoreContent || !topBarHasFocus()) return@collect
            if (RootNavFocus.restoreContentFocus(pageOwner())) return@collect
            focusManager.moveFocus(FocusDirection.Down)
        }
    }
}

/** What a key does while a bar drawn across the top holds the focus. */
internal sealed interface TopBarKeyAction {
    data class Move(val index: Int) : TopBarKeyAction
    data object Activate : TopBarKeyAction
    data object Leave : TopBarKeyAction
}

/**
 * [sidebarKeyAction] turned sideways: Left and Right walk the bar's entries, Select activates, and
 * Down, Back or Dismiss hand the keys back to the page under it. Up is not an action here: there is
 * nothing above the bar, so [topBarKeyNavigation] keeps it on the bar.
 */
internal fun topBarKeyAction(key: Key, index: Int, count: Int): TopBarKeyAction? {
    if (count <= 0) return null
    return when (navActionForKey(key)) {
        NavAction.Right -> TopBarKeyAction.Move((index + 1).coerceAtMost(count - 1))
        NavAction.Left -> TopBarKeyAction.Move((index - 1).coerceAtLeast(0))
        NavAction.Select -> TopBarKeyAction.Activate
        NavAction.Down, NavAction.Dismiss, NavAction.Back -> TopBarKeyAction.Leave
        else -> null
    }
}

/**
 * Handles the top bar's keys, the sidebar's list turned sideways. Movement and leave act on key-down
 * and are always consumed, so an arrow at either end of the bar stays on the bar instead of
 * wandering into the page. Activation acts on key-up, as the sidebar's does: a held Select fires
 * once, and its release cannot land on the page that just took the keys.
 *
 * Up is consumed as well: there is nothing above a bar across the top of the screen, so it must not
 * travel back to the page's hand-off, which would only ask for the bar again. Left at the first
 * entry is released instead, so the bar's own arrow navigation can step out to the utility icons
 * drawn to its left.
 */
internal fun Modifier.topBarKeyNavigation(
    index: () -> Int,
    count: () -> Int,
    onMove: (Int) -> Unit,
    onActivate: () -> Unit,
    onLeave: () -> Unit,
): Modifier = onPreviewKeyEvent { event: KeyEvent ->
    val key = event.navigationKey()
    // Nothing is drawn above the bar: Up stays on it rather than asking for it a second time.
    if (key == Key.DirectionUp) return@onPreviewKeyEvent true
    // Nothing further left inside the bar: let the bar's outer arrow navigation reach the utility
    // icons beside it, rather than pinning the focus to the first tab. The same at the right edge
    // reaches the search field drawn beyond the last tab.
    if (key == Key.DirectionLeft && index() <= 0) return@onPreviewKeyEvent false
    if (key == Key.DirectionRight && index() >= count() - 1) return@onPreviewKeyEvent false
    val action = topBarKeyAction(key, index(), count())
        ?: return@onPreviewKeyEvent false
    when (action) {
        is TopBarKeyAction.Move -> if (event.type == KeyEventType.KeyDown) onMove(action.index)
        TopBarKeyAction.Leave -> if (event.type == KeyEventType.KeyDown) onLeave()
        TopBarKeyAction.Activate -> if (event.type == KeyEventType.KeyUp) onActivate()
    }
    true
}

/**
 * Escape/Back on Home enters the sidebar only when nothing else on the page consumed it. With
 * "Escape goes windowed first" on, the player's Escape rule owns Escape, so the handoff stays off.
 */
internal fun shouldEnterSidebarOnDismiss(
    isHomeSelected: Boolean,
    dismissHandled: Boolean,
    sidebarAvailable: Boolean,
    escWindowedFirst: Boolean,
): Boolean = isHomeSelected && !dismissHandled && sidebarAvailable && !escWindowedFirst

/** What a key does while the sidebar holds focus. */
internal sealed interface SidebarKeyAction {
    data class Move(val index: Int) : SidebarKeyAction
    data object Activate : SidebarKeyAction
    data object Leave : SidebarKeyAction
}

internal fun sidebarKeyAction(key: Key, index: Int, count: Int, minIndex: Int = 0): SidebarKeyAction? {
    if (count <= 0) return null
    return when (navActionForKey(key)) {
        NavAction.Down -> SidebarKeyAction.Move((index + 1).coerceAtMost(count - 1))
        NavAction.Up -> SidebarKeyAction.Move((index - 1).coerceAtLeast(minIndex))
        NavAction.Select -> SidebarKeyAction.Activate
        NavAction.Right, NavAction.Dismiss, NavAction.Back -> SidebarKeyAction.Leave
        else -> null
    }
}

/**
 * At the first card of a row, Left leaves the row for the sidebar; anywhere else it steps back
 * within the row. Callers must offer the sidebar first: the content's moveItem clamps at zero, so a
 * bare Left at the first card would otherwise be swallowed and the sidebar never reached.
 */
internal fun leftLeavesRowForSidebar(itemIndex: Int): Boolean = itemIndex <= 0

/**
 * The sidebar's rows below the profile trigger: one per shown navigation entry, then Settings, then
 * the voice row while voice control is on with a model. The draw loop and the arrow keys both walk
 * this count, so a row that is drawn always has an index and no index exists without a row.
 */
internal fun sidebarRowCount(
    entryCount: Int,
    voiceRowShown: Boolean,
    musicRowShown: Boolean = false,
): Int = entryCount + 1 + (if (voiceRowShown) 1 else 0) + (if (musicRowShown) 1 else 0)

/** The row the voice item takes, or -1 when it is not drawn. Settings takes [entryCount]. */
internal fun sidebarVoiceRowIndex(entryCount: Int, voiceRowShown: Boolean): Int =
    if (voiceRowShown) entryCount + 1 else -1

/** The album-cover row, after Settings and the optional voice row, or -1 when nothing is playing. */
internal fun sidebarMusicRowIndex(
    entryCount: Int,
    voiceRowShown: Boolean,
    musicRowShown: Boolean,
): Int = if (musicRowShown) entryCount + 1 + (if (voiceRowShown) 1 else 0) else -1

/** Keeps the focused row on a row that still exists after the shown set changes under it. */
internal fun clampSidebarIndex(index: Int, rowCount: Int, minIndex: Int): Int =
    if (rowCount <= 0) minIndex else index.coerceIn(minIndex, rowCount - 1)

/** What a key a tab screen has no use for does: close a page it opened, or reach the navigation. */
internal enum class TabHandoffKey { Back, Escape, Left, Up }

/**
 * Which of the keys a tab screen has no use for hands off to the app's navigation bar. [key] is a
 * [navigationKey] result, so a rebound Back or Dismiss key arrives here as Backspace or Escape.
 *
 * [homeSelected] keeps Escape on Home, which owns it: the hero's trailer closes on it and the
 * "Escape goes windowed first" setting decides whether it may also reach the bar. Handling it here
 * as well would fire twice and defeat that setting.
 *
 * None of the three needs a per-screen flag. Every screen/element that closes a level of its own
 * (Live TV's regions popup, the Music pages' selection, a tab's ladder) runs in the preview phase
 * and answers false when it has nothing left to close, so those levels are spent before this is
 * reached and only the last press arrives here. Left is the same: a page of ordinary focusable
 * controls moves focus with it and releases it when it moved nowhere (see
 * [arrowKeyFocusNavigation]), and a page that runs its own selection declines it at the first item,
 * so a Left that arrives here has nothing further left in the page.
 */
internal fun tabHandoffKey(
    key: Key,
    homeSelected: Boolean,
    topBarAvailable: Boolean = false,
): TabHandoffKey? = when (navActionForKey(key)) {
    NavAction.Dismiss -> if (homeSelected) null else TabHandoffKey.Escape
    NavAction.Back -> TabHandoffKey.Back
    NavAction.Left -> TabHandoffKey.Left
    // Up reaches a bar drawn across the top of the screen, the way Left reaches the sidebar. A page
    // that still had somewhere to go with it kept the key, so one that arrives here is at its top.
    NavAction.Up -> if (topBarAvailable) TabHandoffKey.Up else null
    else -> null
}

/**
 * Dismiss pressed in a focused text field that has nothing left to clear. [tabSidebarHandoff] stands
 * aside for every text field (a field is where Back and Escape delete and clear first), so the field
 * itself has to ask for the bar. [fallback] runs when there is no bar on screen, which is the old
 * behaviour: let go of the field. Returns whether the bar took it.
 */
internal fun dismissToSidebarOr(fallback: () -> Unit): Boolean {
    val took = RootNavFocus.enterSidebar()
    if (!took) fallback()
    return took
}

/**
 * [dismissToSidebarOr] for a window whose navigation is the floating bar across the top rather than
 * the sidebar. Clearing a text field's focus used to be the fallback there, which left the window
 * with no focused control at all: the next Back/Escape is then delivered nowhere and the bar can
 * never be reached with the keyboard or a pad. Ask either bar first, and only clear focus when no
 * bar is drawn (a window with no navigation chrome at all keeps the old behaviour).
 */
internal fun dismissToBarOr(fallback: () -> Unit): Boolean {
    val took = RootNavFocus.enterSidebar() || RootNavFocus.enterFloatingTopBar()
    if (!took) fallback()
    return took
}

/**
 * Hands the keys an ordinary tab screen has no use for to the navigation bar, as Home's own handlers
 * do at its first card and on an empty Dismiss. Bubble phase, so a context menu's popup, a tab's own
 * page ladder or a text field that is typing all get the key first; a text field keeps every key.
 *
 * Left included, for every tab: a page that moves focus itself releases a Left that moved it nowhere
 * (see [arrowKeyFocusNavigation]) and a page that runs its own selection declines it at the first
 * item, so a Left that gets here is one with nothing further left on the page. Up is the same for a
 * bar drawn across the top, which [topBarAvailable] reports: a page that still had a row above it
 * moved into it and kept the key.
 */
internal fun Modifier.tabSidebarHandoff(
    homeSelected: () -> Boolean,
    sidebarAvailable: () -> Boolean,
    onBack: () -> Boolean,
    topBarAvailable: () -> Boolean = { RootNavFocus.anyTopBarDrawn },
): Modifier = onKeyEvent { event: KeyEvent ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    // Typing fields keep every Back/Escape: they delete and clear first (see navigationKey).
    if (TextInputFocusTracker.active.value) return@onKeyEvent false
    when (tabHandoffKey(event.navigationKey(), homeSelected(), topBarAvailable())) {
        // Back and Escape close what the tab opened over itself before a bar takes the focus. A
        // window with no bar on it keeps the key, as it always has. Only the floating bar takes them:
        // on Settings the page's own ladder owns Back, up a level and then out of the tab.
        TabHandoffKey.Back, TabHandoffKey.Escape ->
            if (onBack()) true else sidebarAvailable() && RootNavFocus.enterSidebar() ||
                RootNavFocus.enterFloatingTopBar()
        TabHandoffKey.Left -> sidebarAvailable() && RootNavFocus.enterSidebar()
        TabHandoffKey.Up -> RootNavFocus.enterTopBar()
        null -> false
    }
}

/**
 * Handles the sidebar's keys. Movement and leave act on key-down; activation acts on key-up so a
 * held Enter fires once and its release cannot land on the content that just took focus.
 */
internal fun Modifier.sidebarKeyNavigation(
    index: () -> Int,
    count: () -> Int,
    onMove: (Int) -> Unit,
    onActivate: () -> Unit,
    onLeave: () -> Unit,
    minIndex: Int = 0,
    enabled: Boolean = true,
): Modifier = onPreviewKeyEvent { event: KeyEvent ->
    if (!enabled) return@onPreviewKeyEvent false
    val action = sidebarKeyAction(event.navigationKey(), index(), count(), minIndex) ?: return@onPreviewKeyEvent false
    val isActivate = action == SidebarKeyAction.Activate
    when (event.type) {
        KeyEventType.KeyDown -> if (!isActivate) when (action) {
            is SidebarKeyAction.Move -> onMove(action.index)
            SidebarKeyAction.Leave -> onLeave()
            SidebarKeyAction.Activate -> Unit
        }
        KeyEventType.KeyUp -> if (isActivate) onActivate()
        else -> Unit
    }
    true
}
