package com.nuvio.app.features.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import com.nuvio.app.core.ui.DesktopNavigationGestureBridge
import com.nuvio.app.core.ui.NavAction
import com.nuvio.app.core.ui.DialogBackRegistry
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.core.ui.TextInputFocusTracker
import com.nuvio.app.core.ui.navActionForKey
import com.nuvio.app.core.ui.navigationKey
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What a key does on a Settings item. Arrows move focus; Select activates; Escape and Back go up a level. */
internal enum class SettingsNavKeyAction { Up, Down, Left, Right, Activate, Back }

/** Maps a canonical key (see [navigationKey]) to a Settings action. Rebound Select and Back are already canonical. */
internal fun settingsNavKeyAction(key: Key): SettingsNavKeyAction? = when (navActionForKey(key)) {
    NavAction.Up -> SettingsNavKeyAction.Up
    NavAction.Down -> SettingsNavKeyAction.Down
    NavAction.Left -> SettingsNavKeyAction.Left
    NavAction.Right -> SettingsNavKeyAction.Right
    NavAction.Select -> SettingsNavKeyAction.Activate
    NavAction.Back, NavAction.Dismiss -> SettingsNavKeyAction.Back
    null -> null
}

/**
 * Whether the page root should hand focus to the page on this key. Only when the root itself holds
 * focus, so keys that bubble up from a focused child never walk focus through the top bar. Back is left
 * to the app-level handler, and text fields and the search field keep their own keys.
 */
internal fun settingsRootEntersPage(
    rootFocused: Boolean,
    textInputActive: Boolean,
    searchFocused: Boolean,
    action: SettingsNavKeyAction?,
): Boolean = rootFocused && !textInputActive && !searchFocused &&
    action != null && action != SettingsNavKeyAction.Back

/** Settings keys are off while a text field has focus, so typing, Escape and Enter reach the field. */
internal fun settingsNavKeysActive(textInputFocused: Boolean): Boolean = !textInputFocused

/**
 * Which of the Settings shell's three keyboard regions an item belongs to. A Settings screen is a
 * page in the middle, the category list to its left and (on wide layouts) the favourites/search
 * panel to its right. An arrow that leaves one region belongs to its neighbour, and a row needs to
 * know which neighbour that is: Left from the content goes to the categories, but Left from the
 * context panel goes back into the content rather than jumping over it.
 */
internal enum class SettingsFocusRegion { Sidebar, Content, ContextPanel }

/** The region the current subtree is drawn in; see [SettingsFocusRegion]. Content is the default. */
internal val LocalSettingsFocusRegion = staticCompositionLocalOf { SettingsFocusRegion.Content }

/**
 * The focus manager whose Settings shell paints one focus ring over the whole page (see
 * [SettingsScreen]). Items composed under it leave the outline to that overlay instead of drawing a
 * second ring of their own. A dialog composes its own focus manager, so its items still draw the
 * known-good per-item ring where the page overlay cannot reach.
 */
internal val LocalSettingsFocusRingOwner = staticCompositionLocalOf<FocusManager?> { null }

/**
 * The focus stops of one region, in composition order. The page has several entry points into its
 * content — opening a category, stepping Left out of the context panel, giving up a bar — and none
 * of them can name a row ahead of time. Each item registers the requester it already needs, and an
 * entry attempt asks the control the person last used in that region first, then the first one still
 * composed. Requesters for rows that are not on screen fail harmlessly, so a lazy list needs no
 * special case.
 *
 * Entries are keyed by the focus manager that composed them. A dialog composes its own, so a page
 * arrow can never reach into a dialog and a dialog's arrow can never reach the page behind it.
 */
internal class SettingsFocusRegionEntries {
    private data class Entry(val owner: FocusManager, val requester: FocusRequester)

    private val entries = mutableListOf<Entry>()
    private var remembered: Entry? = null

    fun register(requester: FocusRequester, owner: FocusManager) {
        if (entries.none { it.requester === requester }) entries += Entry(owner, requester)
    }

    fun unregister(requester: FocusRequester) {
        entries.removeAll { it.requester === requester }
        if (remembered?.requester === requester) remembered = null
    }

    /** Remembers the control the keys are on, so a return to this region lands back on it. */
    fun remember(requester: FocusRequester, owner: FocusManager) {
        remembered = Entry(owner, requester)
    }

    /** Focuses the remembered control, or the first one that still exists, in [owner]'s window. */
    fun requestEntry(owner: FocusManager): Boolean {
        val rememberedHere = remembered?.takeIf { it.owner === owner }?.requester
        val candidates = buildList {
            rememberedHere?.let(::add)
            entries.forEach { if (it.owner === owner) add(it.requester) }
        }
        val focused = candidates.any { requester ->
            runCatching { requester.requestFocus() }.getOrDefault(false)
        }
        SettingsFocusDiagnostics.log(
            "region.requestEntry owners=${entries.count { it.owner === owner }} " +
                "remembered=${rememberedHere != null} focused=$focused",
        )
        return focused
    }

    fun clear() {
        entries.clear()
        remembered = null
    }
}

/** The page content's entry points; see [SettingsFocusRegionEntries]. */
internal val SettingsContentFocus = SettingsFocusRegionEntries()

/** The context panel's entry points; see [SettingsFocusRegionEntries]. */
internal val SettingsContextFocus = SettingsFocusRegionEntries()

/**
 * Lets the content list reveal more rows when an arrow reaches the last composed control. Focus
 * traversal only reaches controls that are composed, so at the bottom edge of the viewport a Down
 * has nowhere to go until the list scrolls. The content `LazyColumn` registers a scroll action here;
 * an item whose Down/Up found no neighbour asks it to page the list and then tries again.
 */
internal object SettingsContentScroll {
    private var scrollBy: ((direction: Int) -> Unit)? = null

    fun register(scrollBy: (direction: Int) -> Unit) {
        this.scrollBy = scrollBy
    }

    fun unregister() {
        scrollBy = null
    }

    fun scrollFurther(direction: Int): Boolean {
        val action = scrollBy ?: return false
        action(direction)
        return true
    }
}

/**
 * Concise, opt-in focus diagnostics. Off by default; set [enabled] to true in a debug build (or from
 * a debugger) to trace where a key went, which region owns focus, and whether a destination
 * requester actually received focus. Logs through `println` so a desktop run surfaces them without
 * any logging framework. Remove or leave disabled before shipping.
 */
internal object SettingsFocusDiagnostics {
    @Volatile
    var enabled: Boolean = false

    fun log(message: String) {
        if (enabled) println("[SettingsFocus] $message")
    }
}

/** Where Back goes from a Settings page: a remembered anchor page first, then the page's parent, then Home. */
internal sealed interface SettingsBackTarget {
    data class Page(val page: SettingsPage) : SettingsBackTarget
    data object Home : SettingsBackTarget
}

internal fun settingsBackTarget(anchorPage: SettingsPage?, parentPage: SettingsPage?): SettingsBackTarget =
    (anchorPage ?: parentPage)?.let { SettingsBackTarget.Page(it) } ?: SettingsBackTarget.Home

/**
 * Lets the app-level Back handler ask the open Settings layers to go up one level first, innermost
 * first: an editor or sub-screen closes before the page ladder runs. Each layer registers with its
 * own [token] and removes exactly that token on disposal, so a sub-screen closing can never take
 * the page's registration with it (a nested editor sits above a page that is still composed, or
 * replaces it and re-registers on return).
 *
 * With nothing registered, or when every layer declines, Back belongs to the app (Home).
 */
internal object SettingsBackBridge {
    private class Entry(val token: Any, val handler: () -> Boolean)

    private val entries = mutableListOf<Entry>()

    /** Registers a scoped Back handler; the newest registration answers first. */
    fun register(token: Any, handler: () -> Boolean) {
        entries.removeAll { it.token === token }
        entries += Entry(token, handler)
    }

    /** Removes only this token's handler; every other registration is untouched. */
    fun unregister(token: Any) {
        entries.removeAll { it.token === token }
    }

    /** True when a Settings layer took the Back request: the top dialog first, then the innermost handler. */
    fun handle(): Boolean {
        if (DialogBackRegistry.dismissTop()) return true
        for (entry in entries.asReversed()) {
            if (entry.handler()) return true
        }
        return false
    }

    /** Test hook: drops every registration, so one test's page cannot leak into the next. */
    fun clearForTest() {
        entries.clear()
    }
}

/** The arrow a [SettingsNavKeyAction] stands for, or null for Select and Back. */
internal fun SettingsNavKeyAction.focusDirection(): FocusDirection? = when (this) {
    SettingsNavKeyAction.Up -> FocusDirection.Up
    SettingsNavKeyAction.Down -> FocusDirection.Down
    SettingsNavKeyAction.Left -> FocusDirection.Left
    SettingsNavKeyAction.Right -> FocusDirection.Right
    SettingsNavKeyAction.Activate, SettingsNavKeyAction.Back -> null
}

/**
 * The category sidebar's keyboard entry, for a page row with nothing further left. The sidebar
 * registers the row that holds its entry while it is drawn; a Left that moved focus nowhere asks
 * for it, so the categories and the page read as one traversal.
 *
 * The focus manager is part of the registration because a dialog composes its own: Left at the edge
 * of a dialog's row must stay in the dialog rather than reaching past it to the sidebar behind.
 */
internal object SettingsSidebarFocus {
    private var entry: FocusRequester? = null
    private var owner: FocusManager? = null
    private var fallback: (() -> Boolean)? = null

    fun register(requester: FocusRequester, focusManager: FocusManager, fallback: (() -> Boolean)? = null) {
        entry = requester
        owner = focusManager
        this.fallback = fallback
    }

    fun unregister(requester: FocusRequester) {
        if (entry === requester) {
            entry = null
            owner = null
            fallback = null
        }
    }

    /**
     * True when the sidebar took the focus. [fallback] covers the row that is the entry being
     * scrolled out of the sidebar's viewport: a lazy list has not composed it, so it has no node to
     * focus until the list scrolls to it.
     */
    fun requestEntry(focusManager: FocusManager): Boolean {
        val requester = entry ?: return false
        if (owner !== focusManager) return false
        if (runCatching { requester.requestFocus() }.getOrDefault(false)) return true
        return fallback?.invoke() ?: false
    }
}

/**
 * Where the keys go when a page has just let go of them — the search field clearing itself, say:
 * the page's first content entry, and the sidebar entry when the page has no content entry point
 * composed (an empty result set, a filtered page).
 *
 * Lives here rather than at the call site so the search UI keeps taking its focus behaviour from
 * this layer instead of reaching for the navigation primitives itself (SettingsNavigationApiGuardTest).
 */
internal fun requestSettingsContentOrSidebarEntry(focusManager: FocusManager): Boolean =
    SettingsContentFocus.requestEntry(focusManager) ||
        SettingsSidebarFocus.requestEntry(focusManager)

/**
 * The top bar's counterpart of [SettingsSidebarFocus]: the row of tab icons drawn above the Settings
 * page. Up at the top of the page has nothing further on the page itself, so it belongs to that row,
 * the way Left at the page's edge belongs to the categories.
 *
 * Registered with the page's focus manager for the same reason the sidebar is: a dialog composes its
 * own, and Up at the top of a dialog must stay in the dialog rather than reach the row behind it.
 */
internal object SettingsTopBarFocus {
    private var entry: FocusRequester? = null
    private var owner: FocusManager? = null
    private var rememberContent: (() -> Unit)? = null

    fun register(
        requester: FocusRequester,
        focusManager: FocusManager,
        rememberContentFocus: (() -> Unit)? = null,
    ) {
        entry = requester
        owner = focusManager
        rememberContent = rememberContentFocus
    }

    fun unregister(requester: FocusRequester) {
        if (entry === requester) {
            entry = null
            owner = null
            rememberContent = null
        }
    }

    /**
     * True when the row took the focus. False for a dialog's focus manager: Up released at the top
     * of a dialog's row must stay in the dialog rather than reach the row behind it. The control the
     * page had is remembered before the bar takes the keys, so leaving the bar comes back to it
     * rather than to the page's first control.
     */
    fun requestEntry(focusManager: FocusManager): Boolean {
        val requester = entry ?: return false
        if (owner !== focusManager) return false
        rememberContent?.invoke()
        return runCatching { requester.requestFocus() }.getOrDefault(false)
    }
}

/**
 * The value one step to the left ([direction] -1) or right (+1) of [value] on a settings slider.
 * A stepped slider lands on the step grid rather than drifting off it as a held key repeats; a
 * continuous one moves a twentieth of its range. Both stop at the ends.
 */
internal fun settingsSliderAdjustedValue(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    direction: Int,
): Float {
    val span = valueRange.endInclusive - valueRange.start
    if (span <= 0f || direction == 0) return value.coerceIn(valueRange.start, valueRange.endInclusive)
    val clamped = value.coerceIn(valueRange.start, valueRange.endInclusive)
    if (steps <= 0) {
        return (clamped + span / SettingsSliderContinuousDivisions * direction)
            .coerceIn(valueRange.start, valueRange.endInclusive)
    }
    val intervals = steps + 1
    val interval = span / intervals
    val index = ((clamped - valueRange.start) / interval).roundToInt().coerceIn(0, intervals)
    val next = (index + direction).coerceIn(0, intervals)
    return (valueRange.start + interval * next).coerceIn(valueRange.start, valueRange.endInclusive)
}

/** How many steps one arrow press moves a settings slider that has no fixed steps. */
private const val SettingsSliderContinuousDivisions = 20f

/**
 * Moves a two-thumb band one [settingsSliderAdjustedValue] step in [direction], both ends together
 * so it keeps its width. The move stops at the room the band has left, so an end already against
 * the range stays there rather than the pair squeezing. A band has two independent ends and one
 * pair of keys cannot set both, which is why the keys move the pair as one.
 *
 * Unchanged at the end of the track, which lets the key fall through to focus traversal.
 */
internal fun settingsRangeShifted(
    low: Float,
    high: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    direction: Int,
): Pair<Float, Float> {
    if (direction == 0 || low > high) return low to high
    val candidate = settingsSliderAdjustedValue(low, valueRange, steps, direction)
    if (candidate == low) return low to high
    val shift = (candidate - low)
        .coerceAtLeast(valueRange.start - low)
        .coerceAtMost(valueRange.endInclusive - high)
    if (shift == 0f) return low to high
    return (low + shift) to (high + shift)
}

/**
 * The option Select picks on a segmented row: the next one, wrapping. On a two-option row that is
 * exactly the other option, so Select flips a binary row as it does the switch row.
 */
internal fun <T> settingsNextChoiceOption(options: List<SettingsChoiceOption<T>>, selected: T): T? {
    if (options.isEmpty()) return null
    val index = options.indexOfFirst { it.value == selected }
    return if (index < 0) options.first().value else options[(index + 1) % options.size].value
}

/**
 * The option index a Left ([forward] false) or Right (true) press steps a segmented row's selection
 * to, or null when it is already at that end. Null leaves the key to focus traversal, so Left at
 * the leftmost option still leaves the row for the sidebar. Null too when the selection matches no
 * option, which only happens while a page swaps its option list.
 */
internal fun settingsChoiceStepIndex(index: Int, count: Int, forward: Boolean): Int? {
    if (count <= 1 || index !in 0 until count) return null
    val next = if (forward) index + 1 else index - 1
    return next.takeIf { it in 0 until count }
}

/**
 * The value one arrow press moves a stepper to: one [step] along, clamped to [min]..[max]. Unchanged
 * at an end, so the key falls through to focus traversal and the person can step out of the row
 * instead of being held there.
 */
internal fun stepperStep(value: Double, step: Double, min: Double, max: Double, direction: Int): Double {
    if (direction == 0 || step <= 0.0) return value
    val clamped = value.coerceIn(min, max)
    return (clamped + step * direction).coerceIn(min, max)
}

/** The focus ring every Settings stop shares: the app accent, 2dp, one corner radius. */
internal val SettingsFocusRingShape = RoundedCornerShape(10.dp)

/**
 * The shared Settings focus outline. Applied outside the focus target (before `clickable` or the
 * item's own focusable) so it draws only for the stop that actually holds the keys — the sidebar
 * categories, the top icon bar and the app sidebar all use it, so they read as one system.
 */
@Composable
internal fun Modifier.settingsFocusRing(clipContent: Boolean = false): Modifier {
    val accent = androidx.compose.material3.MaterialTheme.nuvio.colors.accent
    val ring = border(2.dp, accent.copy(alpha = 0.9f), SettingsFocusRingShape)
    // Rows clip their focused content (a ripple or gradient mask) to the ring; icon buttons and
    // the profile avatar keep their own shape and only gain the outline.
    return if (clipContent) clip(SettingsFocusRingShape).then(ring) else ring
}

/**
 * Makes a Settings item respond to the keys in [settingsNavKeyAction]. Arrows move focus, Select
 * runs [onActivate], and Escape goes up a level through the gesture bridge. **Apply it before the
 * row's focus target** — `focusable()`, `clickable()` or `toggleable()`. Compose only reports focus
 * to an `onFocusChanged` on the outside of the target, so a row that applies this afterwards has no
 * focus ring and never scrolls itself into view.
 *
 * The item's own target owns Select, Back and the keys a control takes through [onHorizontal] and
 * [onVertical]. A control *inside* the row — a switch beside a catalog's name, an edit button on a
 * list row — keeps every key it handles, because the row's handler runs in the preview phase and
 * stands aside while that control holds the focus. What the control does not take (an arrow, which
 * desktop Compose would otherwise leave dead inside the row) bubbles back up to the row, which
 * moves focus by geometry.
 *
 * [onHorizontal] lets a control own Left and Right instead of focus traversal: it receives -1 for
 * Left and +1 for Right and returns whether it took the key. Returning false (or leaving the
 * callback out) moves focus as usual, so a control at the end of its range still lets the user step
 * out of the row. [onHorizontalReleased] runs once on the key-up of a key the control took, for a
 * control that saves when the user stops stepping rather than on every repeat.
 *
 * [onVertical] does the same for Up (-1) and Down (+1). The category sidebar uses it: its rows sit
 * in a lazy list, where focus traversal cannot reach a row that is not composed yet, so it moves
 * the selection by index instead.
 *
 * A focused item brings itself into view, so an arrow that reaches a row below the fold scrolls the
 * page to it instead of leaving the focus somewhere the user cannot see.
 */
internal fun Modifier.settingsNavItem(
    onActivate: () -> Unit,
    onHorizontal: ((Int) -> Boolean)? = null,
    onHorizontalReleased: (() -> Unit)? = null,
    onVertical: ((Int) -> Boolean)? = null,
    /**
     * Takes Back instead of the app-level gesture bridge when it returns true. A sidebar sub-entry
     * uses it to put focus back on its parent category row rather than walking the page ladder (and
     * leaving Settings) from inside the sidebar. Null (the default) keeps Back on the bridge.
     */
    onBack: (() -> Boolean)? = null,
    /**
     * Whether this item is one of its region's entry points. True for settings rows and controls;
     * false for section headings, which are stops the arrows walk through but must not steal the
     * page's first-control focus away from the rows beneath them.
     */
    registerAsEntry: Boolean = true,
): Modifier = composed {
    // The item's own target holds the keys, rather than a control inside it: Select, Back and the
    // control callbacks belong to it only then.
    var selfFocused by remember { mutableStateOf(false) }
    // Anything in the item holds the keys. A control inside it that declines an arrow leaves the
    // row to move focus, so an arrow never dies inside a row.
    var subtreeFocused by remember { mutableStateOf(false) }
    // Set while a Left/Right this item took is still held, so its key-up commits exactly once.
    var horizontalHeld by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val focusRingOwner = LocalSettingsFocusRingOwner.current
    val region = LocalSettingsFocusRegion.current
    // The item also serves as one of its region's entry points; see [SettingsFocusRegionEntries].
    // The region is filtered by focus manager, so a dialog's rows never become a page's fallback.
    val regionEntries = if (registerAsEntry) {
        when (region) {
            SettingsFocusRegion.Sidebar -> null
            SettingsFocusRegion.Content -> SettingsContentFocus
            SettingsFocusRegion.ContextPanel -> SettingsContextFocus
        }
    } else {
        null
    }
    val selfRequester = if (registerAsEntry) remember { FocusRequester() } else null
    if (selfRequester != null) {
        DisposableEffect(regionEntries, selfRequester, focusManager) {
            regionEntries?.register(selfRequester, focusManager)
            onDispose { regionEntries?.unregister(selfRequester) }
        }
    }
    val inView = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    this
        .bringIntoViewRequester(inView)
        .then(if (selfRequester != null) Modifier.focusRequester(selfRequester) else Modifier)
        .onFocusChanged { state ->
            selfFocused = state.isFocused
            subtreeFocused = state.hasFocus
            if (state.hasFocus) {
                if (selfRequester != null) regionEntries?.remember(selfRequester, focusManager)
                scope.launch { runCatching { inView.bringIntoView() } }
            } else {
                horizontalHeld = false
            }
        }
        .onPreviewKeyEvent { event ->
            if (!selfFocused) return@onPreviewKeyEvent false
            if (!settingsNavKeysActive(TextInputFocusTracker.active.value)) return@onPreviewKeyEvent false
            val action = settingsNavKeyAction(event.navigationKey()) ?: return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown) {
                SettingsFocusDiagnostics.log("item key=$action region=$region selfFocused=$selfFocused")
            }
            if (action == SettingsNavKeyAction.Back) {
                if (event.type == KeyEventType.KeyDown) {
                    // Back inside Settings walks the focus outward before it walks the app's history:
                    // search results and page content go to the category list, the category list goes
                    // to the navigation bar, and only the bar itself leaves Settings. A control with
                    // its own Back (a sidebar sub-entry returning to its parent row) answers first.
                    if (onBack?.invoke() != true) {
                        val handled = when (region) {
                            SettingsFocusRegion.Content ->
                                SettingsSidebarFocus.requestEntry(focusManager)
                            SettingsFocusRegion.ContextPanel ->
                                SettingsContentFocus.requestEntry(focusManager) ||
                                    SettingsSidebarFocus.requestEntry(focusManager)
                            SettingsFocusRegion.Sidebar ->
                                SettingsTopBarFocus.requestEntry(focusManager)
                        }
                        if (!handled) DesktopNavigationGestureBridge.requestBack()
                    }
                }
                return@onPreviewKeyEvent true
            }
            val horizontal = action == SettingsNavKeyAction.Left || action == SettingsNavKeyAction.Right
            val vertical = action == SettingsNavKeyAction.Up || action == SettingsNavKeyAction.Down
            if (event.type != KeyEventType.KeyDown) {
                // Select's key-up is the clickable's own press release: keep it from firing again.
                if (action == SettingsNavKeyAction.Activate) return@onPreviewKeyEvent true
                if (horizontal && horizontalHeld) {
                    horizontalHeld = false
                    onHorizontalReleased?.invoke()
                    return@onPreviewKeyEvent true
                }
                return@onPreviewKeyEvent false
            }
            if (horizontal && onHorizontal?.invoke(if (action == SettingsNavKeyAction.Left) -1 else 1) == true) {
                horizontalHeld = true
                return@onPreviewKeyEvent true
            }
            if (vertical && onVertical?.invoke(if (action == SettingsNavKeyAction.Up) -1 else 1) == true) {
                return@onPreviewKeyEvent true
            }
            when (action) {
                SettingsNavKeyAction.Up -> {
                    // Nothing above the item. In the content, page the list up to reveal more rows
                    // and try again; only when there is nothing left above does the row of tabs take
                    // the keys. Asked only of this page's own focus manager, so a dialog's top row
                    // keeps its Up instead of reaching past the dialog.
                    if (!focusManager.moveFocus(FocusDirection.Up)) {
                        if (region == SettingsFocusRegion.Content) {
                            scope.launch {
                                if (SettingsContentScroll.scrollFurther(-1)) {
                                    withFrameNanos { }
                                    if (!focusManager.moveFocus(FocusDirection.Up)) {
                                        SettingsTopBarFocus.requestEntry(focusManager)
                                    }
                                } else {
                                    SettingsTopBarFocus.requestEntry(focusManager)
                                }
                            }
                        } else if (!focusManager.moveFocus(FocusDirection.Previous)) {
                            // The category list's rows are separate lazy items, where a spatial Up
                            // can find nothing; traversal order is the list's own order.
                            SettingsTopBarFocus.requestEntry(focusManager)
                        }
                    }
                }
                SettingsNavKeyAction.Down -> {
                    if (region == SettingsFocusRegion.Content) {
                        if (!focusManager.moveFocus(FocusDirection.Down)) {
                            // No composed control below: page the list down and let the newly
                            // revealed row take the keys.
                            scope.launch {
                                SettingsContentScroll.scrollFurther(1)
                                withFrameNanos { }
                                focusManager.moveFocus(FocusDirection.Down)
                            }
                        }
                    } else if (!focusManager.moveFocus(FocusDirection.Down)) {
                        // Same as Up: a lazy list's next row is not found spatially, so walk on in
                        // traversal order.
                        focusManager.moveFocus(FocusDirection.Next)
                    }
                }
                SettingsNavKeyAction.Left, SettingsNavKeyAction.Right -> {
                    val moved = focusManager.moveFocus(action.focusDirection() ?: FocusDirection.Right)
                    // Right at a row's own edge enters its controls — a switch beside its name, its
                    // action buttons — which sit inside the row and are invisible to spatial
                    // moveFocus. Enter descends to the row's first focusable child.
                    val entered = !moved && action == SettingsNavKeyAction.Right &&
                        focusManager.moveFocus(FocusDirection.Enter)
                    // Nothing further that way: the arrow belongs to the next region over. Left from
                    // the content reaches the categories and Left from the context panel goes back
                    // into the content; Right from the content reaches the context panel.
                    if (!moved && !entered) when {
                        action == SettingsNavKeyAction.Left && region == SettingsFocusRegion.Content ->
                            SettingsSidebarFocus.requestEntry(focusManager)
                        action == SettingsNavKeyAction.Left && region == SettingsFocusRegion.ContextPanel ->
                            SettingsContentFocus.requestEntry(focusManager)
                        action == SettingsNavKeyAction.Right && region == SettingsFocusRegion.Sidebar ->
                            SettingsContentFocus.requestEntry(focusManager)
                        action == SettingsNavKeyAction.Right && region == SettingsFocusRegion.Content ->
                            SettingsContextFocus.requestEntry(focusManager)
                    }
                }
                SettingsNavKeyAction.Activate -> onActivate()
                SettingsNavKeyAction.Back -> Unit
            }
            true
        }
        // A control inside the row holds the keys and left an arrow unconsumed: desktop Compose
        // moves focus on Tab only, so the arrow would otherwise die inside the row and the person
        // would have to reach for the mouse. Select and Back stay the control's own.
        .onKeyEvent { event ->
            if (selfFocused || !subtreeFocused) return@onKeyEvent false
            if (!settingsNavKeysActive(TextInputFocusTracker.active.value)) return@onKeyEvent false
            if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
            val direction = when (settingsNavKeyAction(event.navigationKey())) {
                SettingsNavKeyAction.Up -> FocusDirection.Up
                SettingsNavKeyAction.Down -> FocusDirection.Down
                SettingsNavKeyAction.Left -> FocusDirection.Left
                SettingsNavKeyAction.Right -> FocusDirection.Right
                else -> return@onKeyEvent false
            }
            focusManager.moveFocus(direction)
        }
        .then(
            // The ring marks the stop the keys are actually on: a row that merely contains the
            // focused control does not ring around the whole row as well. A Settings shell that
            // paints its own page-wide ring is the one exception: drawing this one too put two
            // outlines around the same stop.
            if (selfFocused && focusRingOwner !== focusManager) {
                Modifier.settingsFocusRing(clipContent = true)
            } else {
                Modifier
            },
        )
}

/**
 * The category sidebar's Back handling, without taking its arrows: Back from a category row asks
 * [onBack] for the navigation bar, and falls back to the app's ladder when no bar is drawn. The
 * rows' Up/Down stay with the page's existing focus traversal, which already walks the lazy list and
 * enters the open category's section list.
 *
 * Applied outside the row's focus target, like [settingsNavItem], so it sees the key before the
 * row's clickable.
 */
internal fun Modifier.settingsBackOnly(onBack: () -> Boolean): Modifier = composed {
    onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        if (settingsNavKeyAction(event.navigationKey()) != SettingsNavKeyAction.Back) {
            return@onPreviewKeyEvent false
        }
        if (!onBack()) DesktopNavigationGestureBridge.requestBack()
        true
    }
}

/**
 * An icon-only action inside a settings row, section header or dialog, as its own keyboard stop:
 * Select activates it, the arrows walk off it to the rows beside it, and it draws the settings
 * focus ring. A bare `IconButton` is unreachable by the arrow keys (desktop Compose moves focus on
 * Tab only) and draws no focus mark of its own.
 *
 * Apply on the `IconButton`'s `modifier`, before its own clickable, exactly as [settingsNavItem] on
 * a row must sit outside the row's focus target. `registerAsEntry` stays false so an icon action
 * never steals a page's first-control focus away from the rows beneath it.
 */
internal fun Modifier.settingsNavIconButton(onClick: () -> Unit): Modifier =
    settingsNavItem(onActivate = onClick, registerAsEntry = false)
