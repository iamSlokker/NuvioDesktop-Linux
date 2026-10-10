package com.nuvio.app.features.streams

import com.nuvio.app.testing.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlin.test.*

class LinuxStreamSearchServiceTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxStreamSearchServiceProbe::class.java, case) }
    @Test fun concurrentRequestsCompleteOutOfOrderButGroupsKeepInstalledOrder() = probe("concurrent")
    @Test fun failedEmptyAndMalformedProvidersRemainSeparateFromSuccess() = probe("isolation")
    @Test fun cancelledSearchCompletesObserversWithoutPublishingAnswers() = probe("cancel")
    @Test fun cancelledOldSearchDoesNotInterfereWithReplacement() = probe("replace")
    @Test fun providerFilterRetainsExactInstalledIdentity() = probe("filter")
    @Test fun unsupportedContentIdsDoNotHitProviders() = probe("unsupported")
    @Test fun flatSearchUsesTheSameOrderedProviderResults() = probe("flat")
}

internal object LinuxStreamSearchServiceProbe {
    private fun payload(id: String) = """{"streams":[{"name":"Provider $id","title":"Film.1080p.WEB-DL.mkv","url":"http://127.0.0.1/$id.mp4","language":"ja"}]}"""
    private suspend fun search(id: String = "tt123", addonId: String? = null, observer: ProviderFetchObserver? = null) =
        withTimeout(12_000) {
            StreamSearchService.searchGrouped("movie", id, id, null, null, addonId,
                ProviderPacing.Concurrent, observer)
        }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val case = args[1]
        LocalHttpFixture().use { http ->
            val ids = if (case == "isolation") listOf("a", "empty", "error", "malformed") else listOf("a", "b", "c")
            val arrived = CountDownLatch(3)
            val releases = ids.associateWith { CountDownLatch(1) }
            val finished = ids.associateWith { CountDownLatch(1) }
            val completionOrder = CopyOnWriteArrayList<String>()
            val observerResults = ConcurrentHashMap<String, Boolean>()
            val started = CopyOnWriteArrayList<String>()
            val observer = object : ProviderFetchObserver {
                override fun onProviderStarted(providerId: String) { started += providerId }
                override fun onProviderFinished(providerId: String, streams: List<StreamItem>?) {
                    val id = ids.single { providerId == "addon:$it:${http.url}/$it/manifest.json" }
                    observerResults[id] = streams != null
                    completionOrder += id
                    finished.getValue(id).countDown()
                }
            }
            ids.forEach { id ->
                http.route("/$id/stream/") { exchange, request ->
                    val blocked = case == "concurrent" ||
                        (case in setOf("cancel", "replace") && request.path.contains("tt123"))
                    if (blocked) {
                        arrived.countDown()
                        check(releases.getValue(id).await(12, TimeUnit.SECONDS))
                    }
                    val body = when (id) {
                        "empty" -> """{"streams":[]}"""
                        "error" -> "unavailable"
                        "malformed" -> "{invalid"
                        else -> payload(id)
                    }
                    exchange.respond(if (id == "error") 503 else 200, body.toByteArray())
                }
            }
            http.start()
            try {
                val addons = http.loadInstalledAddons(ids)
                http.drainRequests()
                when (case) {
                    "concurrent" -> {
                        val result = async(Dispatchers.Default) { search(observer = observer) }
                        // All requests arrive before ANY response is released. Sequential fetching cannot pass.
                        assertTrue(arrived.await(12, TimeUnit.SECONDS))
                        ids.reversed().forEach { id ->
                            releases.getValue(id).countDown()
                            assertTrue(finished.getValue(id).await(12, TimeUnit.SECONDS))
                        }
                        val groups = result.await()
                        assertEquals(listOf("c", "b", "a"), completionOrder.toList())
                        assertEquals(ids.map { "Provider $it" }, groups.map { it.addonName })
                        assertEquals(ids.map { "addon:$it:${http.url}/$it/manifest.json" }, groups.map { it.addonId })
                        groups.forEach { group ->
                            assertFalse(group.isLoading)
                            assertNull(group.error)
                            assertEquals(group.addonId, group.streams.single().addonId)
                            assertEquals(listOf("ja"), group.streams.single().audioLanguages)
                        }
                        assertEquals(3, http.drainRequests().size)
                    }
                    "isolation" -> {
                        val groups = search(observer = observer)
                        assertEquals(4, groups.size)
                        assertEquals(1, groups[0].streams.size)
                        assertTrue(groups[1].streams.isEmpty())
                        assertNull(groups[1].error)
                        groups.drop(2).forEach { assertNotNull(it.error); assertTrue(it.streams.isEmpty()) }
                        assertEquals(mapOf("a" to true, "empty" to true, "error" to false, "malformed" to false), observerResults)
                    }
                    "cancel", "replace" -> {
                        val old = async(Dispatchers.Default) { search(observer = observer) }
                        assertTrue(arrived.await(12, TimeUnit.SECONDS))
                        old.cancel()
                        if (case == "replace") {
                            val fresh = search("tt456")
                            assertEquals(ids.map { "Provider $it" }, fresh.map { it.addonName })
                            assertTrue(fresh.all { it.streams.size == 1 && it.error == null })
                        }
                        releases.values.forEach { it.countDown() }
                        withTimeout(12_000) { old.join() }
                        assertTrue(old.isCancelled)
                        assertEquals(3, started.size)
                        assertEquals(3, completionOrder.size)
                        assertEquals(false, observerResults["a"])
                        assertEquals(false, observerResults["b"])
                        assertEquals(false, observerResults["c"])
                    }
                    "filter" -> {
                        val expectedId = "addon:b:${http.url}/b/manifest.json"
                        val groups = search(addonId = expectedId)
                        assertEquals(expectedId, groups.single().addonId)
                        assertEquals(expectedId, groups.single().streams.single().addonId)
                        assertEquals("/b/stream/movie/tt123.json", http.drainRequests().single().path)
                    }
                    "unsupported" -> {
                        assertTrue(search("unsupported:123").isEmpty())
                        assertTrue(http.drainRequests().isEmpty())
                    }
                    "flat" -> {
                        val streams = StreamSearchService.search("movie", "tt123", "tt123", null, null,
                            pacing = ProviderPacing.Concurrent)
                        assertEquals(addons.map { "addon:${it.manifest!!.id}:${it.manifestUrl}" }, streams.map { it.addonId })
                    }
                }
            } finally { releases.values.forEach { it.countDown() } }
        }
    }
}
