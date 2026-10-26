package com.nuvio.app.features.calendar

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryStorage
import com.nuvio.app.features.tmdb.TmdbSettingsRepository
import com.nuvio.app.features.trakt.TraktCalendarUiState
import com.nuvio.app.features.watched.WatchedItem
import com.nuvio.app.features.watched.WatchedStorage
import com.nuvio.app.testing.LocalHttpFixture
import com.nuvio.app.testing.respond
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import java.nio.file.Path
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real repositories and disk storage in child JVMs; the only metadata provider is loopback. */
class NuvioCalendarRepositoryIntegrationTest {
    private fun probe(case: String) = withHeadlessFixture {
        runHeadlessProbe(it, NuvioCalendarRepositoryProbe::class.java, case)
    }

    @Test fun cachedSchedulesSurviveRestartAndPruneUnfollowedTitles() = withHeadlessFixture {
        runHeadlessProbe(it, NuvioCalendarRepositoryProbe::class.java, "write-cache")
        runHeadlessProbe(it, NuvioCalendarRepositoryProbe::class.java, "read-cache")
    }

    @Test fun localAddonDatesBecomeCalendarEntriesWithoutTrackingAuthentication() = probe("fetch")
    @Test fun newlyFollowedLibraryIdWinsOverAlreadyCachedHistoryAlias() = probe("alias")
    @Test fun failedRefreshPreservesScheduleAndUsesFailureRetryCache() = probe("failure")
    @Test fun otherProfileCacheCannotSuppressCurrentProfileMetadataFetch() = probe("profile")
    @Test fun legacyCacheWithoutProfileCannotSuppressMetadataFetch() = probe("legacy")
    @Test fun unsupportedCacheVersionCannotSuppressMetadataFetch() = probe("version")
    @Test fun corruptCacheIsReplacedWithSuccessfulLocalMetadata() = probe("corrupt")
    @Test fun profileResetCancelsInflightMetadataWithoutPublishingStaleEntries() = probe("cancel")
}

internal object NuvioCalendarRepositoryProbe {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private const val date = "2030-04-12"
    private val libraryFollow = NuvioCalendarFollow(contentId = "tt100", title = "Library title", posterUrl = "library-poster")
    private val historyFollow = NuvioCalendarFollow(contentId = "tmdb:100", title = "History alias")

    private fun cached(follow: NuvioCalendarFollow, fetchedAt: Long = System.currentTimeMillis(), scheduleKey: String? = null) =
        NuvioCalendarCachedItem(
            follow = follow, fetchedAtEpochMs = fetchedAt, scheduleKey = scheduleKey,
            entries = listOf(NuvioCalendarCachedEntry(
                dateKey = date, contentId = follow.contentId, title = follow.title,
                posterUrl = follow.posterUrl, seasonNumber = 1, episodeNumber = 2,
                episodeTitle = "Cached episode", airAt = "${date}T20:30:00Z",
            )),
        )

    private fun seedLibrary(vararg follows: NuvioCalendarFollow) {
        val items = follows.mapIndexed { index, follow ->
            LibraryItem(id = follow.contentId, type = follow.type, name = follow.title,
                poster = follow.posterUrl, savedAtEpochMs = 100L - index)
        }
        LibraryStorage.savePayload(1, buildJsonObject { put("items", json.encodeToJsonElement(items)) }.toString())
    }

    private fun seedHistory(follow: NuvioCalendarFollow) {
        val items = listOf(WatchedItem(id = follow.contentId, type = follow.type, name = follow.title,
            season = 1, episode = 1, markedAtEpochMs = 1_800_000_000_000L))
        WatchedStorage.savePayload(1, buildJsonObject { put("items", json.encodeToJsonElement(items)) }.toString())
    }

    private fun saveCache(vararg items: NuvioCalendarCachedItem, profileId: Int = 1, version: Int = NuvioCalendarCacheEntry.VERSION) {
        NuvioCalendarCacheStorage.save(json.encodeToString(NuvioCalendarCacheEntry(
            profileId = profileId, version = version, items = items.toList(), builtAtEpochMs = System.currentTimeMillis(),
        )))
    }

    private fun activeJob(): Job = NuvioCalendarRepository::class.java.getDeclaredField("loadJob").let {
        it.isAccessible = true
        it.get(NuvioCalendarRepository) as Job
    }

    private fun isolateUnrelatedArtworkPrefetch() {
        // LibraryRepository also prefetches artwork on publication. Seed only its separate
        // lightweight cache before any repository worker starts, so that unrelated path cannot
        // call Metahub. Calendar fetchForSchedule uses the full/schedule namespace, not this map.
        val field = MetaDetailsRepository::class.java.getDeclaredField("lightweightMetaCache")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val cache = field.get(MetaDetailsRepository) as MutableMap<String, MetaDetails>
        listOf("tt100", "tt200").forEach { id ->
            cache["series:$id:addon"] = MetaDetails(id = id, type = "series", name = "Fixture artwork only")
        }
    }

    private suspend fun load(): TraktCalendarUiState {
        NuvioCalendarRepository.ensureLoaded()
        withTimeout(12_000) { activeJob().join() }
        return NuvioCalendarRepository.uiState.value.also {
            assertFalse(it.isLoading)
            assertTrue(it.hasLoaded)
            assertTrue(it.isAuthenticated)
            assertNull(it.errorMessage)
        }
    }

    private suspend fun persisted(predicate: (NuvioCalendarCacheEntry) -> Boolean): NuvioCalendarCacheEntry = withTimeout(12_000) {
        while (true) {
            val snapshot = NuvioCalendarCacheStorage.load()?.let { json.decodeFromString<NuvioCalendarCacheEntry>(it) }
            if (snapshot != null && predicate(snapshot)) return@withTimeout snapshot
            delay(10)
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val root = Path.of(args[0]).toAbsolutePath()
        assertTrue(DesktopStorage.rootDir.startsWith(root))
        assertTrue(DesktopStorage.accountDir.startsWith(root))
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        TmdbSettingsRepository.setApiKey("") // No TMDB fallback/enrichment or external service calls.
        isolateUnrelatedArtworkPrefetch()
        val case = args[1]
        if (case == "write-cache") {
            val alpha = libraryFollow.copy(contentId = "tt200", title = "Alpha")
            seedLibrary(libraryFollow, alpha)
            seedHistory(historyFollow)
            // Disk order intentionally differs from follow priority; duplicate episodes are pruned too.
            val preferred = cached(libraryFollow, scheduleKey = "same-show")
            saveCache(cached(historyFollow, scheduleKey = "same-show"),
                cached(libraryFollow.copy(contentId = "tt-unfollowed")),
                preferred.copy(entries = preferred.entries + preferred.entries), cached(alpha))
            DesktopStorage.flushAll()
            return@runBlocking
        }
        if (case == "read-cache") {
            val state = load()
            val entries = state.entriesByDate.getValue(date)
            assertEquals(listOf("tt200", "tt100"), entries.map { it.contentId })
            assertEquals("Cached episode", entries.last().episodeTitle)
            assertEquals("${date}T20:30:00Z", entries.last().airAt)
            assertEquals("library-poster", entries.last().posterUrl)
            assertTrue(AddonRepository.uiState.value.addons.isEmpty())
            val saved = persisted { it.items.size == 3 }
            assertEquals(listOf("tt100", "tt200", "tmdb:100"), saved.items.map { it.follow.contentId })
            DesktopStorage.flushAll()
            return@runBlocking
        }

        seedLibrary(libraryFollow)
        val previous = cached(libraryFollow, fetchedAt = 1, scheduleKey = "shared-show")
        when (case) {
            "alias" -> { seedHistory(historyFollow); saveCache(cached(historyFollow, scheduleKey = "tt100")) }
            "failure" -> saveCache(previous)
            "profile" -> saveCache(cached(libraryFollow), profileId = 2)
            "legacy" -> NuvioCalendarCacheStorage.save(json.encodeToString(NuvioCalendarCacheEntry(items = listOf(cached(libraryFollow)))))
            "version" -> saveCache(cached(libraryFollow), version = 999)
            "corrupt" -> NuvioCalendarCacheStorage.save("{broken")
        }

        LocalHttpFixture().use { http ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(if (case == "cancel") 1 else 0)
            http.route("/fixture/manifest.json") { exchange, _ ->
                exchange.respond(200, """{"id":"calendar-fixture","name":"Calendar fixture","version":"1.0.0",
                    "description":"Loopback","types":["series"],"catalogs":[],
                    "resources":[{"name":"meta","types":["series"],"idPrefixes":["tt"]}]}""".toByteArray())
            }
            http.route("/fixture/meta/series/tt100.json") { exchange, _ ->
                entered.countDown()
                check(release.await(12, TimeUnit.SECONDS))
                exchange.respond(if (case == "failure") 503 else 200, """{"meta":{
                    "id":"tt100","imdb_id":"tt100","type":"series","name":"Fetched show","status":"Returning Series",
                    "videos":[
                      {"id":"tt100:0:1","season":0,"episode":1,"title":"Special","released":"$date"},
                      {"id":"tt100:1:2","season":1,"episode":2,"title":"  Precise episode  ","released":"${date}T20:30:00Z"},
                      {"id":"tt100:1:3","season":1,"episode":3,"title":"Date only","released":"2030-04-13"},
                      {"id":"tt100:1:4","season":1,"episode":4,"title":"Undated"}
                    ]}}""".toByteArray())
            }
            http.start()
            AddonStorage.saveInstalledAddonUrls(1, listOf("${http.url}/fixture/manifest.json"))
            try {
                if (case == "cancel") {
                    NuvioCalendarRepository.ensureLoaded()
                    assertTrue(entered.await(12, TimeUnit.SECONDS))
                    val staleJob = activeJob()
                    NuvioCalendarRepository.onProfileChanged()
                    release.countDown()
                    withTimeout(12_000) { staleJob.join() }
                    assertTrue(staleJob.isCancelled)
                    assertEquals(TraktCalendarUiState(isAuthenticated = true), NuvioCalendarRepository.uiState.value)
                    assertNull(NuvioCalendarCacheStorage.load())
                } else {
                    val state = load()
                    if (case == "failure") {
                        assertEquals(previous.entries.map { it.toEntry() }, state.entriesByDate.getValue(date))
                        val saved = persisted { it.items.single().failed }
                        assertEquals(previous.entries, saved.items.single().entries)
                        assertEquals("shared-show", saved.items.single().scheduleKey)
                        NuvioCalendarRepository.onProfileChanged()
                        assertEquals(state.entriesByDate, load().entriesByDate)
                    } else {
                        val entry = state.entriesByDate.getValue(date).single()
                        // This also proves alias precedence: cached history has a different ID and title.
                        assertEquals("tt100", entry.contentId, "The library ID must win over the cached history alias")
                        assertEquals(setOf(date, "2030-04-13"), state.entriesByDate.keys)
                        assertEquals("Fetched show", entry.title)
                        assertEquals("library-poster", entry.posterUrl)
                        assertEquals("Precise episode", entry.episodeTitle)
                        assertEquals(2, entry.episodeNumber)
                        assertEquals("${date}T20:30:00Z", entry.airAt)
                        assertNull(state.entriesByDate.getValue("2030-04-13").single().airAt)
                        persisted { it.profileId == 1 && it.items.any { item -> item.entries.any { e -> e.title == "Fetched show" } } }
                        NuvioCalendarRepository.ensureMonthsAround(2030, 4)
                        withTimeout(12_000) { activeJob().join() }
                        assertEquals(state, NuvioCalendarRepository.uiState.value)
                    }
                    assertEquals(1, http.drainRequests().count { it.path == "/fixture/meta/series/tt100.json" })
                }
            } finally {
                release.countDown()
            }
        }
        DesktopStorage.flushAll()
    }
}
