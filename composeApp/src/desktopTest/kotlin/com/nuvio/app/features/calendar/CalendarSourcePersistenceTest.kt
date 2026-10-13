package com.nuvio.app.features.calendar

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.core.storage.ProfileScopedKey
import com.nuvio.app.features.tracking.CalendarSource
import com.nuvio.app.features.tracking.CalendarSourceRepository
import com.nuvio.app.features.tracking.ContinueWatchingSourceStorage
import com.nuvio.app.features.tracking.TrackingProviderId
import com.nuvio.app.features.tracking.resolveCalendarSource
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CalendarSourcePersistenceTest {
    @Test fun explicitLocalSourceSurvivesFreshJvmWithoutChangingOtherSelections() = withHeadlessFixture {
        runHeadlessProbe(it, CalendarSourcePersistenceProbe::class.java, "write")
        runHeadlessProbe(it, CalendarSourcePersistenceProbe::class.java, "read")
    }

    @Test fun disconnectedProviderFallsBackLocallyWithoutOverwritingUserSelection() = withHeadlessFixture {
        runHeadlessProbe(it, CalendarSourcePersistenceProbe::class.java, "fallback")
    }

    @Test fun legacyMigrationRunsOnceAndProfileReloadReadsStoredLocalSource() = withHeadlessFixture {
        runHeadlessProbe(it, CalendarSourcePersistenceProbe::class.java, "migration")
    }
}

internal object CalendarSourcePersistenceProbe {
    @JvmStatic fun main(args: Array<String>) {
        val root = Path.of(args[0]).toAbsolutePath()
        assertTrue(DesktopStorage.rootDir.startsWith(root))
        assertTrue(DesktopStorage.accountDir.startsWith(root))
        val store = DesktopStorage.store("nuvio_tracking_sources")
        val otherProfile = ProfileScopedKey.of("calendar_source", 2)
        when (args[1]) {
            "write" -> {
                ContinueWatchingSourceStorage.saveSelection("simkl")
                ContinueWatchingSourceStorage.saveLibrarySelection("local")
                store.putString(otherProfile, "mdblist")
                CalendarSourceRepository.setSource(CalendarSource.NUVIO)
                assertEquals("nuvio", ContinueWatchingSourceStorage.loadCalendarSelection())
            }
            "read" -> {
                CalendarSourceRepository.legacyMigrationProbe = { error("Stored source must bypass legacy migration") }
                assertEquals(CalendarSource.NUVIO, CalendarSourceRepository.selectedSource())
                assertEquals(CalendarSource.NUVIO, CalendarSourceRepository.uiState.value)
                assertNull(CalendarSource.NUVIO.providerId)
                assertEquals(CalendarSource.NUVIO, resolveCalendarSource(CalendarSource.NUVIO) {
                    error("Local calendar must not probe authentication")
                })
                assertEquals("simkl", ContinueWatchingSourceStorage.loadSelection())
                assertEquals("local", ContinueWatchingSourceStorage.loadLibrarySelection())
                assertEquals("mdblist", store.getString(otherProfile))
            }
            "fallback" -> {
                CalendarSourceRepository.setSource(CalendarSource.SIMKL)
                assertEquals(CalendarSource.NUVIO, resolveCalendarSource(CalendarSourceRepository.selectedSource()) { false })
                assertEquals("simkl", ContinueWatchingSourceStorage.loadCalendarSelection())
                assertEquals(CalendarSource.SIMKL, resolveCalendarSource(CalendarSourceRepository.selectedSource()) {
                    it == TrackingProviderId.SIMKL
                })
                assertEquals(CalendarSource.SIMKL, CalendarSourceRepository.uiState.value)
            }
            "migration" -> {
                var migrations = 0
                CalendarSourceRepository.legacyMigrationProbe = { migrations++; CalendarSource.MDBLIST }
                CalendarSourceRepository.ensureLoaded()
                CalendarSourceRepository.ensureLoaded()
                assertEquals(CalendarSource.MDBLIST, CalendarSourceRepository.selectedSource())
                assertEquals(1, migrations)
                assertEquals("mdblist", ContinueWatchingSourceStorage.loadCalendarSelection())
                ContinueWatchingSourceStorage.saveCalendarSelection(" NUVIO ")
                CalendarSourceRepository.onProfileChanged()
                assertEquals(CalendarSource.NUVIO, CalendarSourceRepository.selectedSource())
                assertEquals(CalendarSource.NUVIO, CalendarSourceRepository.uiState.value)
                assertEquals(1, migrations)
            }
            else -> error(args[1])
        }
        DesktopStorage.flushAll()
    }
}
