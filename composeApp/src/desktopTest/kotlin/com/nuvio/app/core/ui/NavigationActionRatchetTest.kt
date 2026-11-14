package com.nuvio.app.core.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A ratchet for the action funnel: navigation handlers must read the canonical key
 * (`event.navigationKey()` / `event.navAction()`) instead of comparing `event.key` with a physical
 * Key. Comparing the physical key ignores the user's rebinding in Settings > Keyboard shortcuts, so
 * the rebind works on the surfaces that were migrated and silently not on the new one.
 *
 * The counts below are the handlers that still do it. They may shrink, never grow: a new one fails
 * this test and has to go through the funnel. Drop a file from the map once it reads the funnel.
 * Migrating a handler does not need the number updated by hand (a lower count passes).
 *
 * Not caught here on purpose: text fields reading their own keys, and `WasdNavigation.kt`, which is
 * the canonicalizer itself.
 */
class NavigationActionRatchetTest {

    private val projectDir: File = listOf(File("."), File("composeApp"))
        .firstOrNull { File(it, "src/commonMain").isDirectory }
        ?: fail("composeApp source tree not found from ${File(".").absolutePath}")

    /** Physical navigation-key comparisons, as in `event.key == Key.Enter`. */
    private val rawComparison = Regex(
        """\.key\s*(?:==|!=)\s*Key\.(DirectionUp|DirectionDown|DirectionLeft|DirectionRight|Enter|NumPadEnter|Escape|Backspace|Back)\b""",
    )

    /** Baseline: relative path -> occurrences when the funnel landed. Must only ever go down. */
    private val baseline: Map<String, Int> = mapOf(
        "commonMain/kotlin/com/nuvio/app/core/ui/NuvioInputFields.kt" to 3,
        "commonMain/kotlin/com/nuvio/app/features/librarypvr/ManualLinkDialog.kt" to 1,
        // Upstream 1.39's PIN pad (PinKeyInput.kt) reads the number keys, Backspace and Escape like a
        // text field, so they are not navigation actions to rebind. Allowed as-is.
        "commonMain/kotlin/com/nuvio/app/features/profiles/PinKeyInput.kt" to 2,
        // Merged in from upstream 1.38.1 (Umbra sync 8e2adb00b): their variants of these handlers
        // still compare the physical key, and SettingsScreen.kt/AlbumLensEdit.kt are new there.
        // The numbers are the merged counts; migrating them is the next step, and they may only shrink.
        "commonMain/kotlin/com/nuvio/app/features/settings/SettingsScreen.kt" to 2,
        "commonMain/kotlin/com/nuvio/app/features/music/AlbumLensEdit.kt" to 2,
        "commonMain/kotlin/com/nuvio/app/features/music/MusicPlayerHost.kt" to 2,
        "commonMain/kotlin/com/nuvio/app/features/music/MusicMiniPlayerBar.kt" to 6,
        "commonMain/kotlin/com/nuvio/app/features/music/MusicTabScreen.kt" to 5,
        "commonMain/kotlin/com/nuvio/app/features/music/MusicAppHooks.kt" to 2,
        "commonMain/kotlin/com/nuvio/app/features/music/MusicCornerCard.kt" to 4,
        "commonMain/kotlin/com/nuvio/app/features/music/MusicDockNotch.kt" to 4,
                "commonMain/kotlin/com/nuvio/app/features/music/MusicNowPlaying.kt" to 2,
        "commonMain/kotlin/com/nuvio/app/features/music/MusicNowPlayingWidgets.kt" to 2,
                "commonMain/kotlin/com/nuvio/app/features/music/StageOptionsMenu.kt" to 1,
        "commonMain/kotlin/com/nuvio/app/features/music/library/ConstellationView.kt" to 3,
        "commonMain/kotlin/com/nuvio/app/features/music/library/LibraryErasView.kt" to 1,
        "commonMain/kotlin/com/nuvio/app/features/music/library/MusicLadderScreen.kt" to 1,
        "commonMain/kotlin/com/nuvio/app/features/settings/SettingsLocalLibraryPage.kt" to 4,
        "commonMain/kotlin/com/nuvio/app/features/settings/SettingsSearch.kt" to 2,
        "commonMain/kotlin/com/nuvio/app/features/settings/SettingsSubScreenNav.kt" to 1,
        "desktopMain/kotlin/com/nuvio/app/features/games/GameRowsSettings.desktop.kt" to 4,
    )

    private fun sources(): Map<String, Int> {
        val roots = listOf("commonMain", "desktopMain").map { File(projectDir, "src/$it/kotlin") }
        return roots.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }.mapNotNull { file ->
            val count = rawComparison.findAll(file.readText()).count()
            if (count == 0) {
                null
            } else {
                val relative = file.relativeTo(projectDir).invariantSeparatorsPath
                    .removePrefix("src/")
                relative to count
            }
        }.toMap()
    }

    @Test
    fun noHandlerComparesAPhysicalNavigationKeyOutsideTheBaseline() {
        val found = sources()
        val grown = found.filter { (path, count) -> count > (baseline[path] ?: 0) }
        assertTrue(
            grown.isEmpty(),
            "New navigation handlers must read event.navigationKey()/event.navAction() so keyboard " +
                "shortcuts keep working after a rebind. Offenders: " +
                grown.entries.joinToString { "${it.key} (${it.value} > ${baseline[it.key] ?: 0})" },
        )
    }
}
