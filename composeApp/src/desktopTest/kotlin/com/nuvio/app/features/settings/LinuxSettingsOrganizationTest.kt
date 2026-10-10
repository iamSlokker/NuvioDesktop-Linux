package com.nuvio.app.features.settings

import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LinuxSettingsOrganizationTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxSettingsOrganizationProbe::class.java, case) }
    @Test fun freshDefaultsAreStable() = probe("defaults")
    @Test fun renameSanitizesAndResetRestoresDefault() = probe("rename")
    @Test fun orderingKeepsHiddenAndFutureCategorySlots() = probe("order")
    @Test fun hideUnhideAndConfigureIconPersist() = probe("hidden")
    @Test fun favoritesDeduplicateReorderAndRemove() = probe("favorites")
    @Test fun collapsedAnchorsKeepCaseAndSurviveReload() = probe("collapsed")
    @Test fun futureIdentifiersSurviveKnownCategoryMutations() = probe("future")
    @Test fun malformedPayloadsFallBackSafely() = probe("corrupt")
    @Test fun existingInstallationDoesNotSeedFavorites() = probe("existing")
    @Test fun organizationSurvivesFreshProcess() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxSettingsOrganizationProbe::class.java, "write")
        runHeadlessProbe(it, LinuxSettingsOrganizationProbe::class.java, "read")
    }
}

internal object LinuxSettingsOrganizationProbe {
    private val favorite = SettingsFavorite("Playback", "heading:Playback:Fixture", "Fixture")
    private fun reload() {
        SettingsCategoryNamesRepository.onProfileChanged()
        SettingsCategoryOrderRepository.onProfileChanged()
        SettingsHiddenCategoriesRepository.onProfileChanged()
        SettingsFavoritesRepository.onProfileChanged()
        SettingsCollapsedSectionsRepository.onProfileChanged()
    }
    @JvmStatic fun main(args: Array<String>) {
        val scenario = args[1]
        if (scenario == "existing") Files.createDirectories(Path.of(args[0], "config", "nuviohtpc"))
        if (scenario == "corrupt") {
            SettingsCategoryNamesStorage.savePayload("{broken")
            SettingsCategoryOrderStorage.savePayload("{broken")
            SettingsHiddenCategoriesStorage.savePayload("{broken")
            SettingsFavoritesStorage.savePayload("{broken")
            SettingsCollapsedSectionsStorage.savePayload("{broken")
        }
        when (scenario) {
            "defaults", "existing", "corrupt" -> {
                reload()
                assertTrue(SettingsCategoryNamesRepository.names.value.isEmpty())
                assertTrue(SettingsCategoryOrderRepository.order.value.isEmpty())
                assertTrue(SettingsHiddenCategoriesRepository.hiddenPages.value.isEmpty())
                assertTrue(SettingsHiddenCategoriesRepository.configureIconVisible.value)
                assertTrue(SettingsCollapsedSectionsRepository.collapsed.value.isEmpty())
                assertEquals(if (scenario == "defaults") 7 else 0, SettingsFavoritesRepository.favorites.value.size)
                val before = SettingsFavoritesRepository.favorites.value
                reload()
                assertEquals(before, SettingsFavoritesRepository.favorites.value)
            }
            "rename" -> {
                SettingsCategoryNamesRepository.setName(SettingsPage.Playback, "  My\n Player  ", "Playback")
                assertEquals("My Player", SettingsCategoryNamesRepository.labelFor(SettingsPage.Playback, "Playback"))
                reload()
                assertEquals("My Player", SettingsCategoryNamesRepository.names.value["Playback"])
                SettingsCategoryNamesRepository.setName(SettingsPage.Playback, "Playback", "Playback")
                assertEquals("Playback", SettingsCategoryNamesRepository.labelFor(SettingsPage.Playback, "Playback"))
                SettingsCategoryNamesRepository.setName(SettingsPage.Playback, "x".repeat(40), "Playback")
                assertEquals(32, SettingsCategoryNamesRepository.names.value.getValue("Playback").length)
                SettingsCategoryNamesRepository.resetAll()
                assertTrue(SettingsCategoryNamesRepository.names.value.isEmpty())
            }
            "order" -> {
                SettingsCategoryOrderRepository.moveByIndex(1, 0, listOf("Playback", "Appearance"),
                    listOf("Playback", "FuturePage", "Appearance"))
                reload()
                assertEquals(listOf("Appearance", "FuturePage", "Playback"), SettingsCategoryOrderRepository.order.value)
                SettingsCategoryOrderRepository.moveByIndex(-1, 0, listOf("Playback"))
                assertEquals(listOf("Appearance", "FuturePage", "Playback"), SettingsCategoryOrderRepository.order.value)
            }
            "hidden" -> {
                SettingsHiddenCategoriesRepository.setHidden(SettingsPage.Games, true)
                SettingsHiddenCategoriesRepository.setConfigureIconVisible(false)
                reload()
                assertTrue(SettingsHiddenCategoriesRepository.isHidden(SettingsPage.Games))
                assertFalse(SettingsHiddenCategoriesRepository.configureIconVisible.value)
                SettingsHiddenCategoriesRepository.setHidden(SettingsPage.Games, false)
                assertFalse(SettingsHiddenCategoriesRepository.isHidden(SettingsPage.Games))
                SettingsHiddenCategoriesRepository.setHidden(SettingsPage.Playback, true)
                SettingsHiddenCategoriesRepository.showAll()
                assertTrue(SettingsHiddenCategoriesRepository.hiddenPages.value.isEmpty())
            }
            "favorites" -> {
                SettingsFavoritesStorage.savePayload("{\"items\":[]}")
                SettingsFavoritesRepository.onProfileChanged()
                SettingsFavoritesRepository.add(favorite)
                SettingsFavoritesRepository.add(favorite.copy(title = "Duplicate"))
                val second = favorite.copy(anchor = "heading:Future:Section", page = "Future", title = "Future")
                SettingsFavoritesRepository.toggle(second)
                SettingsFavoritesRepository.moveByIndex(1, 0)
                reload()
                assertEquals(listOf(second, favorite), SettingsFavoritesRepository.favorites.value)
                SettingsFavoritesRepository.toggle(second)
                SettingsFavoritesRepository.remove(favorite.anchor)
                reload()
                assertTrue(SettingsFavoritesRepository.favorites.value.isEmpty())
            }
            "collapsed" -> {
                SettingsCollapsedSectionsRepository.setCollapsed("heading:Playback:PLAYER", true)
                SettingsCollapsedSectionsRepository.setCollapsed("heading:playback:player", true)
                reload()
                assertEquals(2, SettingsCollapsedSectionsRepository.collapsed.value.size)
                SettingsCollapsedSectionsRepository.setCollapsed("heading:Playback:PLAYER", false)
                assertEquals(setOf("heading:playback:player"), SettingsCollapsedSectionsRepository.collapsed.value)
            }
            "future" -> {
                SettingsCategoryNamesStorage.savePayload("""{"names":{"FuturePage":"Keep","playback":"Distinct"}}""")
                SettingsHiddenCategoriesStorage.savePayload("""{"pages":["FuturePage","playback"]}""")
                SettingsCollapsedSectionsStorage.savePayload("""{"anchors":["heading:FuturePage:New"]}""")
                reload()
                SettingsCategoryNamesRepository.setName(SettingsPage.Playback, "Player", "Playback")
                SettingsHiddenCategoriesRepository.setHidden(SettingsPage.Playback, true)
                SettingsCollapsedSectionsRepository.setCollapsed("heading:Playback:PLAYER", true)
                reload()
                assertEquals(mapOf("FuturePage" to "Keep", "playback" to "Distinct", "Playback" to "Player"), SettingsCategoryNamesRepository.names.value)
                assertEquals(setOf("FuturePage", "playback", "Playback"), SettingsHiddenCategoriesRepository.hiddenPages.value)
                assertTrue("heading:FuturePage:New" in SettingsCollapsedSectionsRepository.collapsed.value)
            }
            "write", "read" -> {
                if (scenario == "write") {
                    SettingsCategoryNamesRepository.setName(SettingsPage.Playback, "Player", "Playback")
                    SettingsCategoryOrderRepository.moveByIndex(1, 0, listOf("Playback", "Appearance"))
                    SettingsHiddenCategoriesRepository.setHidden(SettingsPage.Games, true)
                    SettingsFavoritesRepository.add(favorite)
                    SettingsCollapsedSectionsRepository.setCollapsed(favorite.anchor, true)
                } else reload()
                assertEquals("Player", SettingsCategoryNamesRepository.names.value["Playback"])
                assertEquals(listOf("Appearance", "Playback"), SettingsCategoryOrderRepository.order.value)
                assertTrue(SettingsHiddenCategoriesRepository.isHidden(SettingsPage.Games))
                assertTrue(SettingsFavoritesRepository.isFavorite(favorite.anchor))
                assertTrue(favorite.anchor in SettingsCollapsedSectionsRepository.collapsed.value)
            }
            else -> error(scenario)
        }
    }
}
