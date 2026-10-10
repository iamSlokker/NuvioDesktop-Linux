package com.nuvio.app.features.search

import com.nuvio.app.testing.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.*

class LinuxSearchRepositoryTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxSearchRepositoryProbe::class.java, case) }
    @Test fun federatedResultsPublishPartialSuccessThenInstalledOrder() = probe("federated")
    @Test fun replacingQueryCancelsOldResults() = probe("replace")
    @Test fun clearingQueryCancelsOldResults() = probe("clear")
    @Test fun allProviderFailuresPublishAnError() = probe("failure")
    @Test fun blankAndUnavailableCatalogsHaveExplicitEmptyStates() = probe("empty")
    @Test fun discoverGenreSelectionUsesCatalogHttpPath() = probe("discover")
    @Test fun historyAddsDeduplicatesRemovesAndClears() = probe("history")
    @Test fun historyLimitAndMostRecentOrder() = probe("limit")
    @Test fun corruptHistoryCanBeReplaced() = probe("corrupt")
    @Test fun historySurvivesFreshProcessReload() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxSearchRepositoryProbe::class.java, "write")
        runHeadlessProbe(it, LinuxSearchRepositoryProbe::class.java, "read")
    }
}

internal object LinuxSearchRepositoryProbe {
    private fun catalog(id: String) = """{"metas":[{"id":"tt$id","type":"movie","name":"Title $id","poster":"http://127.0.0.1/poster.jpg"}]}"""
    private suspend fun finished(query: String) = withTimeout(12_000) {
        SearchRepository.uiState.first { it.query == query && !it.isLoading }
    }
    // Join the actual cancelled job, so absence of a stale publication is checked after it settles.
    private fun activeJob() = SearchRepository::class.java.getDeclaredField("activeJob").let {
        it.isAccessible = true
        it.get(SearchRepository) as Job
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val case = args[1]
        if (case in setOf("history", "limit", "corrupt", "write", "read")) {
            when (case) {
                "history" -> {
                    SearchHistoryRepository.recordSearch(" x ")
                    assertTrue(SearchHistoryRepository.uiState.value.isEmpty())
                    listOf(" Dune ", "Silo", "Dune").forEach(SearchHistoryRepository::recordSearch)
                    assertEquals(listOf("Dune", "Silo"), SearchHistoryRepository.uiState.value)
                    SearchHistoryRepository.removeSearch("Dune")
                    assertEquals(listOf("Silo"), SearchHistoryRepository.uiState.value)
                    SearchHistoryRepository.clearHistory()
                    SearchHistoryRepository.onProfileChanged()
                    assertTrue(SearchHistoryRepository.uiState.value.isEmpty())
                }
                "limit" -> {
                    (1..15).forEach { SearchHistoryRepository.recordSearch("Query $it") }
                    assertEquals((15 downTo 6).map { "Query $it" }, SearchHistoryRepository.uiState.value)
                    SearchHistoryRepository.recordSearch("Query 10")
                    assertEquals(listOf("Query 10") + (15 downTo 6).filter { it != 10 }.map { "Query $it" },
                        SearchHistoryRepository.uiState.value)
                }
                "corrupt" -> {
                    SearchHistoryStorage.savePayload("{broken")
                    SearchHistoryRepository.ensureLoaded()
                    assertTrue(SearchHistoryRepository.uiState.value.isEmpty())
                    SearchHistoryRepository.recordSearch("Recovered")
                    SearchHistoryRepository.onProfileChanged()
                    assertEquals(listOf("Recovered"), SearchHistoryRepository.uiState.value)
                }
                "write" -> listOf("Dune", "Silo", "Dune").forEach(SearchHistoryRepository::recordSearch)
                "read" -> {
                    SearchHistoryRepository.ensureLoaded()
                    assertEquals(listOf("Dune", "Silo"), SearchHistoryRepository.uiState.value)
                    SearchHistoryRepository.removeSearch("Silo")
                    SearchHistoryRepository.onProfileChanged()
                    assertEquals(listOf("Dune"), SearchHistoryRepository.uiState.value)
                }
            }
            return@runBlocking
        }
        LocalHttpFixture().use { http ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            http.route("/first/catalog/") { exchange, request ->
                if (case == "federated" || request.path.contains("search=old")) {
                    entered.countDown()
                    check(release.await(12, TimeUnit.SECONDS))
                }
                exchange.respond(if (case == "failure") 500 else 200, catalog("1").toByteArray())
            }
            http.route("/second/catalog/") { exchange, _ -> exchange.respond(200, catalog("2").toByteArray()) }
            http.route("/bad/catalog/") { exchange, _ -> exchange.respond(503, "unavailable".toByteArray()) }
            http.start()
            try {
                val ids = if (case == "federated") listOf("first", "second", "bad") else listOf("first")
                val addons = http.loadInstalledAddons(ids)
                http.drainRequests() // Manifest traffic is separately proven by the install fixture.
                when (case) {
                    "federated" -> {
                        SearchRepository.search(" test ", addons)
                        assertTrue(entered.await(12, TimeUnit.SECONDS))
                        val partial = withTimeout(12_000) { SearchRepository.uiState.first { it.sections.size == 1 } }
                        assertTrue(partial.isLoading)
                        assertEquals("Provider second", partial.sections.single().addonName)
                        release.countDown()
                        val result = finished("test")
                        assertNull(result.errorMessage)
                        assertEquals(listOf("Provider first", "Provider second"), result.sections.map { it.addonName })
                        assertEquals(listOf("tt1", "tt2"), result.sections.flatMap { it.items }.map { it.id })
                        assertEquals(3, http.drainRequests().size)
                    }
                    "replace", "clear" -> {
                        SearchRepository.search("old", addons)
                        assertTrue(entered.await(12, TimeUnit.SECONDS))
                        val oldJob = activeJob()
                        if (case == "replace") {
                            SearchRepository.search("new", addons)
                            assertEquals("tt1", finished("new").sections.single().items.single().id)
                        } else SearchRepository.search("  ", addons)
                        release.countDown()
                        withTimeout(12_000) { oldJob.join() }
                        assertTrue(oldJob.isCancelled)
                        assertEquals(if (case == "replace") "new" else "", SearchRepository.uiState.value.query)
                        if (case == "clear") assertEquals(SearchUiState(), SearchRepository.uiState.value)
                    }
                    "failure" -> {
                        SearchRepository.search("test", addons)
                        val result = finished("test")
                        assertEquals(SearchEmptyStateReason.RequestFailed, result.emptyStateReason)
                        assertNotNull(result.errorMessage)
                        assertTrue(result.sections.isEmpty())
                    }
                    "empty" -> {
                        SearchRepository.search("test", emptyList())
                        assertEquals(SearchEmptyStateReason.NoActiveAddons, SearchRepository.uiState.value.emptyStateReason)
                        SearchRepository.search("test", addons.map { it.copy(manifest = it.manifest!!.copy(catalogs = emptyList())) })
                        assertEquals(SearchEmptyStateReason.NoSearchCatalogs, SearchRepository.uiState.value.emptyStateReason)
                        SearchRepository.search(" ", addons)
                        assertEquals(SearchUiState(), SearchRepository.uiState.value)
                        assertTrue(http.drainRequests().isEmpty())
                    }
                    "discover" -> {
                        SearchRepository.refreshDiscover(addons)
                        withTimeout(12_000) { SearchRepository.discoverUiState.first { !it.isLoading && it.items.isNotEmpty() } }
                        SearchRepository.selectDiscoverGenre("Drama")
                        val result = withTimeout(12_000) { SearchRepository.discoverUiState.first { !it.isLoading && it.selectedGenre == "Drama" } }
                        assertEquals("tt1", result.items.single().id)
                        assertTrue(http.drainRequests().any { it.path.contains("genre=Drama") })
                    }
                }
            } finally {
                release.countDown()
                SearchRepository.reset()
            }
        }
    }
}
