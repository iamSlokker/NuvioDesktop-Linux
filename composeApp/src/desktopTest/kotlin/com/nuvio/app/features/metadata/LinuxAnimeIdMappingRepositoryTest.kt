package com.nuvio.app.features.metadata

import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import kotlinx.serialization.json.*
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class LinuxAnimeIdMappingRepositoryTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxAnimeIdMappingRepositoryProbe::class.java, case) }
    @Test fun desktopLoaderReadsActualBundledMapping() = probe("resource")
    @Test fun supportedNativeIdsReachSameBundledEntry() = probe("native")
    @Test fun numericNativeNamespacesDoNotCollide() = probe("namespaces")
    @Test fun tmdbMovieAndTvNamespacesStaySeparate() = probe("tmdb")
    @Test fun imdbCaseAndTitleDisambiguationUseBundledEntry() = probe("imdb")
    @Test fun ambiguousAndMissingLookupsDoNotGuess() = probe("missing")
    @Test fun bundledFranchiseAndSplitOffsetsTranslateCoordinates() = probe("coordinates")
    @Test fun concurrentFirstLookupsShareSafeIndexInitialization() = probe("concurrent")
    @Test fun coldAndCachedLoadsAreDeterministic() = withHeadlessFixture { root ->
        repeat(2) { runHeadlessProbe(root, LinuxAnimeIdMappingRepositoryProbe::class.java, "native") }
    }
}

internal object LinuxAnimeIdMappingRepositoryProbe {
    @JvmStatic fun main(args: Array<String>) {
        fun crest() = assertNotNull(AnimeIdMappingRepository.entryForNativeIds(anidb = 1))
        when (val scenario = args[1]) {
            "resource" -> {
                val text = assertNotNull(AnimeIdMappingStorage.loadAnimeListText())
                val rows = Json.parseToJsonElement(text).jsonArray
                assertTrue(rows.size > 39_000)
                assertTrue(rows.any { it.jsonObject["anime-planet_id"]?.jsonPrimitive?.content == "crest-of-the-stars" })
                assertEquals(265, crest().kitsuId)
            }
            "native" -> {
                val expected = crest()
                assertEquals(265, expected.kitsuId)
                repeat(3) {
                    assertEquals(expected, AnimeIdMappingRepository.entryForNativeIds(anilist = 290))
                    assertEquals(expected, AnimeIdMappingRepository.entryForNativeIds(kitsu = 265))
                    assertEquals(expected, AnimeIdMappingRepository.entryForNativeIds(mal = 290))
                    assertEquals(expected, AnimeIdMappingRepository.entryForNativeIds(simkl = 36462))
                }
            }
            "namespaces" -> {
                assertEquals("crest-of-the-stars", crest().animePlanetId)
                val anilist = assertNotNull(AnimeIdMappingRepository.entryForNativeIds(anilist = 1))
                val kitsu = assertNotNull(AnimeIdMappingRepository.entryForNativeIds(kitsu = 1))
                assertEquals("cowboy-bebop", anilist.animePlanetId)
                assertEquals(anilist, kitsu)
                assertNotEquals(crest(), anilist)
            }
            "tmdb" -> {
                assertNull(AnimeIdMappingRepository.lookup(ResolvedMediaIds(
                    sourceId = "tmdb:26209", contentType = "movie", tmdb = 26209)))
                assertEquals(265, AnimeIdMappingRepository.lookup(ResolvedMediaIds(
                    sourceId = "tmdb:26209", contentType = "series", tmdb = 26209,
                    sourceSeasonNumber = 1, sourceEpisodeNumber = 1))?.kitsuId)
            }
            "imdb" -> assertEquals(crest(), AnimeIdMappingRepository.lookup(ResolvedMediaIds(
                sourceId = "TT0286390", contentType = "series", imdb = "TT0286390",
                sourceTitle = "Crest of the Stars")))
            "missing" -> {
                assertNull(AnimeIdMappingRepository.entryForNativeIds(kitsu = Int.MAX_VALUE))
                assertNull(AnimeIdMappingRepository.entryForNativeIds())
                assertNull(AnimeIdMappingRepository.lookup(ResolvedMediaIds(
                    sourceId = "tt0000000000", contentType = "series", imdb = "tt0000000000")))
                assertNull(AnimeIdMappingRepository.lookup(ResolvedMediaIds(
                    sourceId = "tt0286390", contentType = "series", imdb = "tt0286390")))
            }
            "coordinates" -> {
                assertEquals(363, AnimeIdMappingRepository.franchiseEntryFor(
                    crest(), season = 2, episode = 1, coordinateSystem = AnimeMappingCoordinateSystem.TMDB)?.kitsuId)
                val base = assertNotNull(AnimeIdMappingRepository.entryForNativeIds(kitsu = 7895))
                listOf(1 to 7080, 24 to 7080, 25 to 7586, 38 to 7586, 39 to 7895).forEach { (episode, expected) ->
                    assertEquals(expected, AnimeIdMappingRepository.franchiseEntryFor(base, 15, episode)?.kitsuId)
                }
                val nativeIds = ResolvedMediaIds(sourceId = "kitsu:7895", contentType = "series",
                    kitsu = base.kitsuId, tmdbSeason = base.tmdbSeason, tvdbSeason = base.tvdbSeason,
                    tmdbEpisodeOffset = base.tmdbEpisodeOffset, tvdbEpisodeOffset = base.tvdbEpisodeOffset)
                assertEquals(15, nativeIds.canonicalSeasonNumber(1))
                assertEquals(39, nativeIds.canonicalEpisodeNumber(1))
            }
            "concurrent" -> {
                val pool = Executors.newFixedThreadPool(8)
                val ready = CountDownLatch(8)
                val start = CountDownLatch(1)
                try {
                    val futures = List(8) {
                        pool.submit(Callable {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            AnimeIdMappingRepository.entryForNativeIds(anidb = 1)
                        })
                    }
                    assertTrue(ready.await(10, TimeUnit.SECONDS))
                    start.countDown()
                    futures.forEach { assertEquals(265, assertNotNull(it.get(20, TimeUnit.SECONDS)).kitsuId) }
                } finally {
                    start.countDown()
                    pool.shutdownNow()
                    assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
                }
            }
            else -> error(scenario)
        }
    }
}
