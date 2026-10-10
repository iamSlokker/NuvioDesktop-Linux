package com.nuvio.app.features.setup

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.home.*
import com.nuvio.app.features.mdblist.MdbListSettingsRepository
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.settings.ApiKeysOnboardingController
import com.nuvio.app.features.settings.ApiKeysOnboardingStorage
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class LinuxFirstRunWizardControllerTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxFirstRunWizardControllerProbe::class.java, case) }
    private fun restart(write: String, read: String) = withHeadlessFixture {
        runHeadlessProbe(it, LinuxFirstRunWizardControllerProbe::class.java, write)
        runHeadlessProbe(it, LinuxFirstRunWizardControllerProbe::class.java, read)
    }
    @Test fun freshLaunchAppliesDefaultsAndOpensWizard() = probe("initial")
    @Test fun stepNavigationIsBounded() = probe("navigation")
    @Test fun draftDoesNotWritePreferencesBeforeFinish() = probe("staged")
    @Test fun finishAppliesPreferencesAndDismissesKeyPrompt() = probe("finish")
    @Test fun completionAndPreferencesSurviveRestart() = restart("finish", "completedRead")
    @Test fun unfinishedWizardReopensFromPersistedSettingsNotUnsavedDraft() = restart("partialWrite", "partialRead")
    @Test fun skipCompletesWizardButOnlyDefersKeyPromptUntilRestart() = restart("skipWrite", "skipRead")
    @Test fun manualRerunUsesCurrentSettingsWithoutResettingCompletion() = probe("rerun")
    @Test fun manualTrailerChoiceDisablesAutoplayWithoutZeroDelayWrite() = probe("manualTrailer")
    @Test fun basicLayoutDoesNotRewriteAdaptiveOrTvPreferences() = probe("basic")
    @Test fun blankDraftKeysDoNotEraseExistingKeys() = probe("blankKeys")
    @Test fun malformedCompletionMarkerUsesSafeDefault() = probe("corruptMarker")
    @Test fun futureCompletionMarkerPreventsAutomaticRerun() = probe("futureMarker")
    @Test fun existingInstallDoesNotGetFreshDefaults() = probe("existing")
    @Test fun keyOnboardingCommitsLocallyAndRemembersPermanentDismissal() = restart("keysWrite", "keysRead")
}

internal object LinuxFirstRunWizardControllerProbe {
    private fun state() = FirstRunWizardController.uiState.value
    private fun home() = HomeCatalogSettingsRepository.uiState.value
    private fun player() = PlayerSettingsRepository.uiState.value
    @JvmStatic fun main(args: Array<String>) {
        val scenario = args[1]
        if (scenario == "existing") {
            Files.createDirectories(Path.of(args[0], "config", "nuviohtpc"))
            HomeCatalogSettingsRepository.setDisplayMode(HomeDisplayMode.Adaptive)
        }
        if (scenario == "corruptMarker" || scenario == "futureMarker") {
            FirstRunWizardStorage.isEligible()
            if (scenario == "corruptMarker") DesktopStorage.store("nuvio_first_run_wizard").putString("completed_version", "invalid")
            else FirstRunWizardStorage.setCompletedVersion(FIRST_RUN_WIZARD_VERSION + 1)
        }
        // App.warmProfileStartupRepositories loads these before the wizard host appears.
        HomeCatalogSettingsRepository.snapshot()
        PlayerSettingsRepository.ensureLoaded()
        FirstRunWizardController.evaluateOnLaunch()
        ApiKeysOnboardingController.evaluateOnLaunch()
        when (scenario) {
            "initial", "corruptMarker" -> {
                assertTrue(state().visible)
                assertEquals(FirstRunWizardStep.Experience, state().step)
                assertFalse(state().isRerun)
                assertEquals(HomeDisplayMode.TvMode, state().draft.displayMode)
                assertEquals(5, state().draft.trailerDelaySeconds)
                assertTrue(state().draft.trailerSoundEnabled)
                assertTrue(FirstRunWizardStorage.defaultsApplied())
                assertEquals(0, FirstRunWizardStorage.completedVersion())
            }
            "navigation" -> {
                FirstRunWizardController.back()
                assertTrue(state().isFirstStep)
                repeat(4) { FirstRunWizardController.next() }
                assertTrue(state().isLastStep)
                repeat(4) { FirstRunWizardController.back() }
                assertTrue(state().isFirstStep)
            }
            "staged", "partialWrite" -> {
                HomeCatalogSettingsRepository.setSmoothScrollingEnabled(false)
                FirstRunWizardController.updateDraft { it.copy(displayMode = HomeDisplayMode.Basic,
                    tmdbApiKey = "fixture-unsaved", trailerDelaySeconds = 19) }
                FirstRunWizardController.next()
                assertEquals(HomeDisplayMode.TvMode, homeDisplayModeOf(home()))
                assertEquals(5, player().heroTvTrailerDelaySeconds)
                assertEquals("", TmdbSettingsRepository.snapshot().apiKey)
                FirstRunWizardController.close()
                assertFalse(state().visible)
                assertEquals(0, FirstRunWizardStorage.completedVersion())
                assertFalse(ApiKeysOnboardingStorage.isPermanentlyDismissed())
            }
            "partialRead" -> {
                assertTrue(state().visible)
                assertTrue(state().isFirstStep)
                assertEquals(HomeDisplayMode.TvMode, state().draft.displayMode)
                assertFalse(state().draft.smoothScrollingEnabled)
                assertEquals("", state().draft.tmdbApiKey)
                assertEquals(5, state().draft.trailerDelaySeconds)
            }
            "finish" -> {
                FirstRunWizardController.updateDraft { it.copy(displayMode = HomeDisplayMode.TvMode,
                    smoothScrollingEnabled = false, tvFullBackdropEnabled = false, tvRowDotsEnabled = false,
                    trailerDelaySeconds = 2, trailerFullscreen = true, trailerSoundEnabled = false,
                    trailerInSearchEnabled = false, tmdbApiKey = "  fixture-tmdb  ", mdbListApiKey = " fixture-mdb ") }
                FirstRunWizardController.finish()
                assertFinishedPreferences()
                assertFalse(state().visible)
                assertTrue(ApiKeysOnboardingStorage.isPermanentlyDismissed())
                assertFalse(ApiKeysOnboardingController.uiState.value.visible)
            }
            "completedRead" -> {
                assertFalse(state().visible)
                assertFinishedPreferences()
                assertFalse(ApiKeysOnboardingController.uiState.value.visible)
            }
            "skipWrite", "skipRead" -> {
                if (scenario == "skipWrite") {
                    FirstRunWizardController.updateDraft { it.copy(trailerDelaySeconds = 19, tmdbApiKey = "unsaved") }
                    FirstRunWizardController.skip()
                    assertFalse(ApiKeysOnboardingController.uiState.value.visible)
                } else assertTrue(ApiKeysOnboardingController.uiState.value.visible)
                assertFalse(state().visible)
                assertEquals(FIRST_RUN_WIZARD_VERSION, FirstRunWizardStorage.completedVersion())
                assertFalse(ApiKeysOnboardingStorage.isPermanentlyDismissed())
                assertEquals(5, player().heroTvTrailerDelaySeconds)
                assertEquals("", TmdbSettingsRepository.snapshot().apiKey)
            }
            "rerun" -> {
                FirstRunWizardController.skip()
                HomeCatalogSettingsRepository.setDisplayMode(HomeDisplayMode.Adaptive)
                PlayerSettingsRepository.setHeroTvTrailerDelaySeconds(8)
                FirstRunWizardController.openManually()
                assertTrue(state().visible && state().isRerun)
                assertEquals(HomeDisplayMode.Adaptive, state().draft.displayMode)
                assertEquals(8, state().draft.trailerDelaySeconds)
                FirstRunWizardController.updateDraft { it.copy(trailerDelaySeconds = 20) }
                FirstRunWizardController.close()
                assertEquals(8, player().heroTvTrailerDelaySeconds)
                assertEquals(FIRST_RUN_WIZARD_VERSION, FirstRunWizardStorage.completedVersion())
            }
            "manualTrailer" -> {
                FirstRunWizardController.updateDraft { it.copy(trailerDelaySeconds = 0) }
                FirstRunWizardController.finish()
                assertFalse(player().heroTvTrailerEnabled)
                assertEquals(5, player().heroTvTrailerDelaySeconds)
            }
            "basic" -> {
                val before = home()
                FirstRunWizardController.updateDraft { it.copy(displayMode = HomeDisplayMode.Basic,
                    adaptiveHeroVerticalBias = 0.9f, tvRowDotsEnabled = !before.tvRowDotsEnabled) }
                FirstRunWizardController.finish()
                assertEquals(HomeDisplayMode.Basic, homeDisplayModeOf(home()))
                assertEquals(before.adaptiveHeroVerticalBias, home().adaptiveHeroVerticalBias)
                assertEquals(before.tvRowDotsEnabled, home().tvRowDotsEnabled)
            }
            "blankKeys" -> {
                TmdbSettingsRepository.setApiKey("fixture-existing-tmdb")
                MdbListSettingsRepository.setApiKey("fixture-existing-mdb")
                FirstRunWizardController.updateDraft { it.copy(tmdbApiKey = " ", mdbListApiKey = "") }
                FirstRunWizardController.finish()
                assertEquals("fixture-existing-tmdb", TmdbSettingsRepository.snapshot().apiKey)
                assertEquals("fixture-existing-mdb", MdbListSettingsRepository.snapshot().apiKey)
            }
            "futureMarker" -> {
                assertFalse(state().visible)
                assertEquals(FIRST_RUN_WIZARD_VERSION + 1, FirstRunWizardStorage.completedVersion())
            }
            "existing" -> {
                assertFalse(state().visible)
                assertFalse(FirstRunWizardStorage.isEligible())
                assertFalse(FirstRunWizardStorage.defaultsApplied())
                assertEquals(HomeDisplayMode.Adaptive, homeDisplayModeOf(home()))
            }
            "keysWrite", "keysRead" -> {
                if (scenario == "keysWrite") {
                    assertTrue(ApiKeysOnboardingController.uiState.value.visible)
                    ApiKeysOnboardingController.onTmdbApiKeyCommitted("fixture-tmdb")
                    assertTrue(ApiKeysOnboardingController.uiState.value.visible)
                    ApiKeysOnboardingController.onMdbListApiKeyCommitted("fixture-mdb")
                    assertFalse(ApiKeysOnboardingController.uiState.value.visible)
                    ApiKeysOnboardingController.dismissPermanently()
                }
                assertEquals("fixture-tmdb", TmdbSettingsRepository.snapshot().apiKey)
                assertEquals("fixture-mdb", MdbListSettingsRepository.snapshot().apiKey)
                assertTrue(ApiKeysOnboardingStorage.isPermanentlyDismissed())
                assertFalse(ApiKeysOnboardingController.uiState.value.visible)
            }
            else -> error(scenario)
        }
    }

    private fun assertFinishedPreferences() {
        assertEquals(FIRST_RUN_WIZARD_VERSION, FirstRunWizardStorage.completedVersion())
        assertFalse(home().smoothScrollingEnabled)
        assertFalse(home().tvFullBackdropEnabled)
        assertFalse(home().tvRowDotsEnabled)
        assertTrue(player().heroTvTrailerEnabled && player().heroTvTrailerFullscreen)
        assertEquals(2, player().heroTvTrailerDelaySeconds)
        assertFalse(player().heroTvTrailerSoundEnabled || player().heroTvTrailerSearchEnabled)
        assertEquals("fixture-tmdb", TmdbSettingsRepository.snapshot().apiKey)
        assertEquals("fixture-mdb", MdbListSettingsRepository.snapshot().apiKey)
        assertTrue(TmdbSettingsRepository.snapshot().enabled && MdbListSettingsRepository.snapshot().enabled)
    }
}
