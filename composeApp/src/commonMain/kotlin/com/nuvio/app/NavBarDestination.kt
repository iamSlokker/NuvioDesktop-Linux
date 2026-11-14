package com.nuvio.app

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.nuvio.app.features.navbar.NavBarEntry
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_nav_discover
import nuvio.composeapp.generated.resources.compose_nav_home
import nuvio.composeapp.generated.resources.compose_nav_library
import nuvio.composeapp.generated.resources.compose_nav_search
import nuvio.composeapp.generated.resources.compose_settings_page_games
import nuvio.composeapp.generated.resources.iptv_settings_page
import nuvio.composeapp.generated.resources.sidebar_library
import nuvio.composeapp.generated.resources.sidebar_search
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/*
 * One descriptor per navigation entry: the icon both bars draw, the name spoken by a screen
 * reader, the tab it opens and the line the Navigation order dialog shows.
 *
 * Adding a page's icon used to mean editing an icon `when` in the floating bar, another in the
 * Settings row, a tab `when` twice and the order dialog's description. Now it is one exhaustive
 * `when` in this file: the compiler names every place a new NavBarEntry has to be described, and
 * both bars draw it from the same descriptor, so they cannot disagree about an entry.
 *
 * What stays at the call sites is what genuinely differs between surfaces: which callback opens the
 * page, and the bar's own selected/focused state.
 */

/** An icon the bars can draw: a vector glyph, or a bundled drawable (the sidebar's own artwork). */
internal sealed interface NavBarGlyph {
    data class Vector(val imageVector: ImageVector) : NavBarGlyph
    data class Drawable(val resource: DrawableResource) : NavBarGlyph
}

/** A name that is either a localized resource or the literal the entry has always used. */
internal sealed interface NavBarLabel {
    data class Resource(val resource: StringResource) : NavBarLabel
    data class Literal(val text: String) : NavBarLabel
}

@Composable
internal fun NavBarLabel.resolve(): String = when (this) {
    is NavBarLabel.Resource -> stringResource(resource)
    is NavBarLabel.Literal -> text
}

internal data class NavBarDestination(
    /** The glyph both bars draw for this entry. */
    val glyph: NavBarGlyph,
    /** The entry's name for hover/accessibility, exactly as each bar announced it before. */
    val label: NavBarLabel,
    /**
     * The page the entry opens, or null for an entry that is not a tab (Games opens game mode over
     * the app; Search opens its field over the current page but keeps [AppScreenTab.Search] for the
     * selection state and history, as it always has).
     */
    val tab: AppScreenTab?,
    /** The line explaining the entry in Settings > Appearance > Navigation order. */
    val orderDescription: String,
)

/** Everything the bars know about an entry besides how to open it. */
internal val NavBarEntry.destination: NavBarDestination
    get() = when (this) {
        NavBarEntry.Home -> NavBarDestination(
            glyph = NavBarGlyph.Vector(Icons.Filled.Home),
            label = NavBarLabel.Resource(Res.string.compose_nav_home),
            tab = AppScreenTab.Home,
            orderDescription = "Your home rows. Always shown.",
        )
        NavBarEntry.Search -> NavBarDestination(
            glyph = NavBarGlyph.Drawable(Res.drawable.sidebar_search),
            label = NavBarLabel.Resource(Res.string.compose_nav_search),
            tab = AppScreenTab.Search,
            orderDescription = "Search across your addons. Always shown.",
        )
        NavBarEntry.Tonight -> NavBarDestination(
            glyph = NavBarGlyph.Vector(Icons.Rounded.AutoAwesome),
            label = NavBarLabel.Literal("What to watch"),
            // Not a tab: it opens the Tonight picker over Home, Library or Discover.
            tab = null,
            orderDescription = "Opens the Tonight picker over Home, Library or Discover.",
        )
        NavBarEntry.Discover -> NavBarDestination(
            glyph = NavBarGlyph.Vector(Icons.Rounded.Explore),
            label = NavBarLabel.Resource(Res.string.compose_nav_discover),
            tab = AppScreenTab.Discover,
            orderDescription = "Browse catalogs by genre, year and more.",
        )
        NavBarEntry.Library -> NavBarDestination(
            glyph = NavBarGlyph.Drawable(Res.drawable.sidebar_library),
            label = NavBarLabel.Resource(Res.string.compose_nav_library),
            tab = AppScreenTab.Library,
            orderDescription = "Your saved titles and collections. Always shown.",
        )
        NavBarEntry.LiveTv -> NavBarDestination(
            glyph = NavBarGlyph.Vector(Icons.Rounded.LiveTv),
            label = NavBarLabel.Resource(Res.string.iptv_settings_page),
            tab = AppScreenTab.LiveTv,
            orderDescription = "Shown while a Live TV playlist is added (Settings > Live TV).",
        )
        NavBarEntry.YouTube -> NavBarDestination(
            glyph = NavBarGlyph.Vector(Icons.Rounded.SmartDisplay),
            label = NavBarLabel.Literal("YouTube"),
            tab = AppScreenTab.YouTube,
            orderDescription = "Your YouTube home, subscriptions and playlists. Needs the account under YouTube account.",
        )
        NavBarEntry.Music -> NavBarDestination(
            glyph = NavBarGlyph.Vector(Icons.Rounded.MusicNote),
            label = NavBarLabel.Literal("Music"),
            tab = AppScreenTab.Music,
            orderDescription = "Your music library and YouTube Music.",
        )
        NavBarEntry.Games -> NavBarDestination(
            glyph = NavBarGlyph.Vector(Icons.Rounded.SportsEsports),
            label = NavBarLabel.Resource(Res.string.compose_settings_page_games),
            // Not a tab: game mode opens over the app and takes the keys itself.
            tab = null,
            orderDescription = "Opens game mode, your game library, over the app.",
        )
    }

/** The tab an entry opens; null for [NavBarEntry.Games], and Settings when [entry] is null (the trailing slot). */
internal fun navBarTab(entry: NavBarEntry?): AppScreenTab? =
    if (entry == null) AppScreenTab.Settings else entry.destination.tab

/**
 * Whether the floating bar should light this entry: Search is an overlay opened over any page, so
 * its own flag decides, and the others are lit while their tab is the open one.
 */
internal fun NavBarEntry.isDestinationSelected(selectedTab: AppScreenTab, searchActive: Boolean): Boolean =
    if (this == NavBarEntry.Search) searchActive else destination.tab == selectedTab

/** Draws the entry's glyph in one of the bars. */
@Composable
internal fun NavBarEntry.DestinationIcon(modifier: Modifier, tint: Color) {
    val destination = destination
    val label = destination.label.resolve()
    when (val glyph = destination.glyph) {
        is NavBarGlyph.Vector -> Icon(glyph.imageVector, contentDescription = label, modifier = modifier, tint = tint)
        is NavBarGlyph.Drawable -> Icon(painterResource(glyph.resource), contentDescription = label, modifier = modifier, tint = tint)
    }
}
