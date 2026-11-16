package com.nuvio.app.features.settings

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Future-proofing: settings pages must get arrow, Select and Back behaviour from the shared
 * components, never by calling the navigation primitives directly. This test reads the settings
 * source folder and fails if any file outside the shared navigation layer references one of those
 * APIs, so a new setting page cannot reintroduce per-setting navigation code.
 */
class SettingsNavigationApiGuardTest {

    // The navigation primitives a page must never call directly.
    private val navigationApis = listOf(
        "settingsNavItem",
        "settingsNavIconButton",
        "settingsNavKeysActive",
        "SettingsNavMenu",
        "SettingsSidebarFocus",
        "SettingsTopBarFocus",
        "SettingsContentScroll",
        "SettingsBackBridge",
        "contentEntryRequester",
        "settingsTopBarFocusRing",
        "SettingsFocusDiagnostics",
    )

    // The shared navigation layer owns these symbols; every other file under features/settings/
    // must use the shared components instead.
    private val sharedLayer = setOf(
        "SettingsNav.kt",
        "SettingsComponents.kt",
        "SettingsScreen.kt",
        "SettingsNavMenu.kt",
        "SettingsSubScreenNav.kt",
        "SettingsScrollAnchor.kt",
    )

    @Test
    fun settingsPagesNeverCallNavigationApisDirectly() {
        val sourceDir = File("src/commonMain/kotlin/com/nuvio/app/features/settings")
        assertTrue(sourceDir.isDirectory, "settings source folder not found: ${sourceDir.absolutePath}")

        val offenders = sourceDir
            .listFiles { file -> file.isFile && file.extension == "kt" && file.name !in sharedLayer }
            .orEmpty()
            .sortedBy { it.name }
            .flatMap { file ->
                val lines = file.readLines()
                navigationApis
                    .filter { api -> lines.any { line -> line.contains(api) } }
                    .map { api -> "${file.name} references $api" }
            }

        assertTrue(
            offenders.isEmpty(),
            "Settings pages must not call navigation APIs directly; use the shared components:\n" +
                offenders.joinToString("\n"),
        )
    }
}
