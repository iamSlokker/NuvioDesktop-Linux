package com.nuvio.app.features.cloud

import com.nuvio.app.features.catalog.ResolvedName
import com.nuvio.app.features.debrid.DebridCloudLibraryWindow
import com.nuvio.app.features.debrid.DebridProvider
import com.nuvio.app.features.debrid.DebridProviderCapability
import com.nuvio.app.features.debrid.DebridServiceCredential
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertNull

class CloudLibraryStoreTest {
    @Test
    fun `failed provider is isolated and a later refresh recovers`() = runBlocking {
        val alpha = cloudProvider("alpha", "Alpha")
        val beta = cloudProvider("beta", "Beta")
        var failed = true
        val first = FakeCloudProviderApi(alpha, emptyList(), listResult = {
            if (failed) Result.failure(IllegalStateException("fixture failure"))
            else Result.success(listOf(cloudItem(alpha, "recovered")))
        })
        val second = FakeCloudProviderApi(beta, listOf(cloudItem(beta, "healthy")))
        val store = CloudLibraryStore(
            credentialsProvider = { listOf(DebridServiceCredential(alpha, "a"), DebridServiceCredential(beta, "b")) },
            providerApis = listOf(first, second),
        )
        val initial = store.refresh()
        assertEquals("fixture failure", initial.providers.first().errorMessage)
        assertEquals(listOf("healthy"), initial.items.map { it.id })
        failed = false
        val recovered = store.refresh()
        assertTrue(recovered.providers.all { it.errorMessage == null })
        assertEquals(listOf("recovered", "healthy"), recovered.items.map { it.id })
        assertEquals(listOf("healthy"), initial.items.map { it.id })
        assertEquals(listOf("a", "a"), first.listKeys)
        assertEquals(listOf("b", "b"), second.listKeys)
    }

    @Test
    fun `absent credentials produce empty refresh and cannot reuse cached playback url`() = runBlocking {
        val provider = cloudProvider("alpha", "Alpha")
        val api = FakeCloudProviderApi(provider, listOf(cloudItem(provider, "unused")))
        val store = CloudLibraryStore(credentialsProvider = { emptyList() }, providerApis = listOf(api))
        val state = store.refresh()
        assertTrue(state.isLoaded && !state.isRefreshing)
        assertTrue(state.providers.isEmpty())
        val item = cloudItem(provider, "cached")
        assertEquals(CloudLibraryPlaybackResult.MissingCredentials,
            store.resolvePlayback(item, item.files.single().copy(playbackUrl = "https://fixture.test/cached")))
        assertTrue(api.listKeys.isEmpty())
        assertEquals(0, api.resolvePlaybackCalls)
    }

    @Test
    fun `unavailable provider api does not prevent another provider refresh`() = runBlocking {
        val missing = cloudProvider("missing", "Missing")
        val ready = cloudProvider("ready", "Ready")
        val store = CloudLibraryStore(
            credentialsProvider = { listOf(DebridServiceCredential(missing, "m"), DebridServiceCredential(ready, "r")) },
            providerApis = listOf(FakeCloudProviderApi(ready, listOf(cloudItem(ready, "one")))),
        )
        val state = store.refresh()
        assertTrue(!state.providers.first().errorMessage.isNullOrBlank())
        assertEquals(listOf("one"), state.items.map { it.id })
        val item = cloudItem(missing, "one")
        assertTrue(store.resolvePlayback(item, item.files.single()) is CloudLibraryPlaybackResult.Failed)
    }

    @Test
    fun `window boundary uses injected clock and explicit window overrides default`() = runBlocking {
        val provider = cloudProvider("alpha", "Alpha")
        val now = 100L * MILLIS_PER_DAY
        val boundary = now - 7L * MILLIS_PER_DAY
        var clockReads = 0
        val store = CloudLibraryStore(
            credentialsProvider = { listOf(DebridServiceCredential(provider, "token")) },
            providerApis = listOf(FakeCloudProviderApi(provider, listOf(
                cloudItem(provider, "old", boundary - 1), cloudItem(provider, "boundary", boundary),
                cloudItem(provider, "new", now), cloudItem(provider, "undated"),
            ))),
            windowProvider = { DebridCloudLibraryWindow.DAYS_7 },
            nowEpochMs = { clockReads++; now },
        )
        assertEquals(DebridCloudLibraryWindow.DAYS_7, store.window())
        assertEquals(listOf("new", "boundary", "undated"), store.refresh().items.map { it.id })
        assertEquals(listOf("new", "boundary", "old", "undated"), store.refresh(DebridCloudLibraryWindow.ALL).items.map { it.id })
        assertEquals(2, clockReads)
    }

    @Test
    fun `equal provider item and file ids retain distinct progress identities`() = runBlocking {
        val alpha = cloudProvider("alpha", "Alpha")
        val beta = cloudProvider("beta", "Beta")
        val items = listOf(cloudItem(alpha, "same"), cloudItem(beta, "same"))
        val store = CloudLibraryStore(
            credentialsProvider = { listOf(DebridServiceCredential(alpha, "a"), DebridServiceCredential(beta, "b")) },
            providerApis = listOf(FakeCloudProviderApi(alpha, listOf(items[0])), FakeCloudProviderApi(beta, listOf(items[1]))),
        )
        val state = store.refresh()
        assertEquals(2, state.items.size)
        assertEquals(2, state.items.map { it.stableKey }.toSet().size)
        for (item in items) {
            val target = assertNotNull(state.findPlaybackTargetForProgress(item.stableKey, item.playbackVideoId(item.files.single())))
            assertEquals(item, target.item)
        }
        val updated = state.withResolvedPlaybackUrl(items[0], items[0].files.single(), "https://fixture.test/alpha")
        assertEquals("https://fixture.test/alpha", updated.items[0].files.single().playbackUrl)
        assertNull(updated.items[1].files.single().playbackUrl)
    }

    @Test
    fun `resolve delegates exact credential item and file and preserves provider result`() = runBlocking {
        val provider = cloudProvider("alpha", "Alpha")
        val item = cloudItem(provider, "one")
        val file = item.files.single().copy(sizeBytes = 123L)
        val expected = CloudLibraryPlaybackResult.Success("https://fixture.test/play", "resolved.mkv", 456L)
        val api = FakeCloudProviderApi(provider, emptyList(), playbackResult = { key, actualItem, actualFile ->
            assertEquals("fixture-token", key)
            assertEquals(item, actualItem)
            assertEquals(file, actualFile)
            expected
        })
        val store = CloudLibraryStore(credentialsProvider = { listOf(DebridServiceCredential(provider, "fixture-token")) }, providerApis = listOf(api))
        assertEquals(expected, store.resolvePlayback(item, file))
        assertEquals(1, api.resolvePlaybackCalls)
    }

    @Test
    fun `resolve propagates provider failure and can retry successfully`() = runBlocking {
        val provider = cloudProvider("alpha", "Alpha")
        var result: CloudLibraryPlaybackResult = CloudLibraryPlaybackResult.Failed("fixture unavailable")
        val api = FakeCloudProviderApi(provider, emptyList(), playbackResult = { _, _, _ -> result })
        val store = CloudLibraryStore(credentialsProvider = { listOf(DebridServiceCredential(provider, "token")) }, providerApis = listOf(api))
        val item = cloudItem(provider, "one")
        assertEquals(result, store.resolvePlayback(item, item.files.single()))
        result = CloudLibraryPlaybackResult.Success("https://fixture.test/retry")
        assertEquals(result, store.resolvePlayback(item, item.files.single()))
        assertEquals(2, api.resolvePlaybackCalls)
    }

    @Test
    fun `nonplayable file never reads credentials or invokes provider`() = runBlocking {
        val provider = cloudProvider("alpha", "Alpha")
        val store = CloudLibraryStore(credentialsProvider = { error("credentials must not be read") }, providerApis = emptyList())
        val item = cloudItem(provider, "one")
        assertEquals(CloudLibraryPlaybackResult.NotPlayable, store.resolvePlayback(item, item.files.single().copy(playable = false)))
    }

    @Test
    fun `cancelled suspended refresh does not poison next refresh`() = runBlocking {
        withTimeout(5_000) {
            val provider = cloudProvider("alpha", "Alpha")
            val entered = CompletableDeferred<Unit>()
            val blocked = CompletableDeferred<Unit>()
            var calls = 0
            val api = FakeCloudProviderApi(provider, emptyList(), listResult = {
                if (++calls == 1) { entered.complete(Unit); blocked.await() }
                Result.success(listOf(cloudItem(provider, "ready")))
            })
            val store = CloudLibraryStore(credentialsProvider = { listOf(DebridServiceCredential(provider, "token")) }, providerApis = listOf(api))
            val pending = async { store.refresh() }
            entered.await()
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(listOf("ready"), store.refresh().items.map { it.id })
            assertEquals(2, calls)
        }
    }

    @Test
    fun `overlapping refreshes return independent snapshots even when older request finishes last`() = runBlocking {
        withTimeout(5_000) {
            val provider = cloudProvider("alpha", "Alpha")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var calls = 0
            val api = FakeCloudProviderApi(provider, emptyList(), listResult = {
                val call = ++calls
                if (call == 1) { entered.complete(Unit); release.await() }
                Result.success(listOf(cloudItem(provider, if (call == 1) "old" else "new")))
            })
            val store = CloudLibraryStore(credentialsProvider = { listOf(DebridServiceCredential(provider, "token")) }, providerApis = listOf(api))
            val old = async { store.refresh() }
            entered.await()
            val current = store.refresh()
            assertEquals(listOf("new"), current.items.map { it.id })
            release.complete(Unit)
            assertEquals(listOf("old"), old.await().items.map { it.id })
            assertEquals(listOf("new"), current.items.map { it.id })
        }
    }

    @Test
    fun `filename metadata from playable file enriches cleaned debrid item`() {
        val provider = cloudProvider(id = "torbox", name = "TorBox")
        val releaseFilename = "Hanna.S01E07.1080p.WEB-DL.mkv"
        val item = CloudLibraryItem(
            providerId = provider.id,
            providerName = provider.displayName,
            id = "hanna-episode",
            type = CloudLibraryItemType.Torrent,
            name = "Hanna S01E07",
            files = listOf(CloudLibraryFile(id = "file", name = releaseFilename)),
        )
        val state = CloudLibraryUiState(
            isLoaded = true,
            providers = listOf(CloudLibraryProviderState(provider = provider, items = listOf(item))),
        )
        val match = ResolvedName(
            displayName = "Hanna S01E07",
            poster = "tmdb-poster",
            posterFallback = null,
            backdrop = "tmdb-backdrop",
            year = 2019,
            overview = "Overview",
            lookupId = "tt7846844",
            lookupType = "series",
            imdbId = "tt7846844",
        )

        assertEquals(listOf("Hanna S01E07", releaseFilename), state.filenameResolutionCandidateNames())

        val resolvedItem = state.withResolvedNames(mapOf(releaseFilename to match)).items.single()
        assertEquals("tmdb-poster", resolvedItem.resolvedPoster)
        assertEquals("tmdb-backdrop", resolvedItem.resolvedBackdrop)
        // The identity is what lets the row's hero pull genres/synopsis/ratings — its provider id
        // ("hanna-episode") addresses a download and resolves to no metadata anywhere.
        assertEquals("tt7846844", resolvedItem.resolvedLookupId)
        assertEquals("series", resolvedItem.resolvedLookupType)
        assertEquals("tt7846844", resolvedItem.resolvedImdbId)
        assertEquals("Overview", resolvedItem.resolvedDescription)
    }

    @Test
    fun `refresh aggregates multiple providers without provider-specific assumptions`() = runBlocking {
        val firstProvider = cloudProvider(id = "alpha", name = "Alpha")
        val secondProvider = cloudProvider(id = "beta", name = "Beta")
        val store = CloudLibraryStore(
            credentialsProvider = {
                listOf(
                    DebridServiceCredential(firstProvider, "alpha-token"),
                    DebridServiceCredential(secondProvider, "beta-token"),
                )
            },
            providerApis = listOf(
                FakeCloudProviderApi(
                    provider = firstProvider,
                    items = listOf(cloudItem(firstProvider, "one")),
                ),
                FakeCloudProviderApi(
                    provider = secondProvider,
                    items = listOf(cloudItem(secondProvider, "two")),
                ),
            ),
        )

        val state = store.refresh()

        assertTrue(state.isLoaded)
        assertEquals(listOf("alpha", "beta"), state.providers.map { it.providerId })
        assertEquals(listOf("one", "two"), state.items.map { it.id })
    }

    @Test
    fun `refresh lists newest first and drops items outside the window`() = runBlocking {
        val provider = cloudProvider(id = "torbox", name = "TorBox")
        val now = 100L * MILLIS_PER_DAY
        val store = CloudLibraryStore(
            credentialsProvider = { listOf(DebridServiceCredential(provider, "token")) },
            providerApis = listOf(
                FakeCloudProviderApi(
                    provider = provider,
                    // Oldest-first, the order TorBox hands an account back in.
                    items = listOf(
                        cloudItem(provider, "ancient", addedAtEpochMs = now - 200L * MILLIS_PER_DAY),
                        cloudItem(provider, "undated", addedAtEpochMs = null),
                        cloudItem(provider, "last-month", addedAtEpochMs = now - 29L * MILLIS_PER_DAY),
                        cloudItem(provider, "today", addedAtEpochMs = now),
                    ),
                ),
            ),
            windowProvider = { DebridCloudLibraryWindow.DAYS_30 },
            nowEpochMs = { now },
        )

        val state = store.refresh()

        // Newest first, and an item the provider gave no date for is kept but sorts last: a missing
        // timestamp must never make a file unreachable.
        assertEquals(listOf("today", "last-month", "undated"), state.items.map { it.id })
    }

    @Test
    fun `unbounded window keeps every item and still orders newest first`() = runBlocking {
        val provider = cloudProvider(id = "torbox", name = "TorBox")
        val now = 100L * MILLIS_PER_DAY
        val store = CloudLibraryStore(
            credentialsProvider = { listOf(DebridServiceCredential(provider, "token")) },
            providerApis = listOf(
                FakeCloudProviderApi(
                    provider = provider,
                    items = listOf(
                        cloudItem(provider, "ancient", addedAtEpochMs = now - 200L * MILLIS_PER_DAY),
                        cloudItem(provider, "today", addedAtEpochMs = now),
                    ),
                ),
            ),
            windowProvider = { DebridCloudLibraryWindow.ALL },
            nowEpochMs = { now },
        )

        assertEquals(listOf("today", "ancient"), store.refresh().items.map { it.id })
    }

    @Test
    fun `refresh ignores connected providers without cloud library capability`() = runBlocking {
        val cloudProvider = cloudProvider(id = "cloud", name = "Cloud")
        val unsupportedProvider = DebridProvider(
            id = "plain",
            displayName = "Plain",
            shortName = "P",
            capabilities = setOf(DebridProviderCapability.ClientResolve),
        )
        val store = CloudLibraryStore(
            credentialsProvider = {
                listOf(
                    DebridServiceCredential(cloudProvider, "cloud-token"),
                    DebridServiceCredential(unsupportedProvider, "plain-token"),
                )
            },
            providerApis = listOf(
                FakeCloudProviderApi(
                    provider = cloudProvider,
                    items = listOf(cloudItem(cloudProvider, "cloud-item")),
                ),
            ),
        )

        val state = store.refresh()

        assertEquals(listOf("cloud"), state.providers.map { it.providerId })
        assertEquals(listOf("cloud-item"), state.items.map { it.id })
    }

    @Test
    fun `playback target lookup matches cloud watch progress video id`() {
        val provider = cloudProvider(id = "torbox", name = "TorBox")
        val item = CloudLibraryItem(
            providerId = provider.id,
            providerName = provider.displayName,
            id = "29773238",
            type = CloudLibraryItemType.Torrent,
            name = "Torrent",
            files = listOf(
                CloudLibraryFile(id = "7", name = "sample.mkv", playable = true),
                CloudLibraryFile(id = "8", name = "movie.mkv", playable = true),
            ),
        )
        val state = CloudLibraryUiState(
            isLoaded = true,
            providers = listOf(
                CloudLibraryProviderState(
                    provider = provider,
                    items = listOf(item),
                ),
            ),
        )

        val target = assertNotNull(
            state.findPlaybackTargetForProgress(
                contentId = "torbox:Torrent:29773238",
                videoId = "torbox:Torrent:29773238:8",
            ),
        )

        assertEquals(item, target.item)
        assertEquals("8", target.file.id)
    }

    @Test
    fun `playback target lookup falls back to single playable file`() {
        val provider = cloudProvider(id = "torbox", name = "TorBox")
        val item = cloudItem(provider, "29773238")
        val state = CloudLibraryUiState(
            isLoaded = true,
            providers = listOf(
                CloudLibraryProviderState(
                    provider = provider,
                    items = listOf(item),
                ),
            ),
        )

        val target = assertNotNull(
            state.findPlaybackTargetForProgress(
                contentId = item.stableKey,
                videoId = item.stableKey,
            ),
        )

        assertEquals(item, target.item)
        assertEquals(item.playableFiles.single(), target.file)
    }

    @Test
    fun `resolve playback reuses already resolved file url`() = runBlocking {
        val provider = cloudProvider(id = "premiumize", name = "Premiumize")
        val api = FakeCloudProviderApi(
            provider = provider,
            items = emptyList(),
        )
        val store = CloudLibraryStore(
            credentialsProvider = {
                listOf(DebridServiceCredential(provider, "token"))
            },
            providerApis = listOf(api),
        )
        val item = cloudItem(provider, "ready")
        val file = item.playableFiles.single().copy(playbackUrl = "https://cached.example/video.mkv")

        val result = store.resolvePlayback(item = item, file = file)

        assertTrue(result is CloudLibraryPlaybackResult.Success)
        assertEquals("https://cached.example/video.mkv", result.url)
        assertEquals(0, api.resolvePlaybackCalls)
    }

    @Test
    fun `resolved playback url is remembered in cloud library state`() {
        val provider = cloudProvider(id = "torbox", name = "TorBox")
        val item = cloudItem(provider, "29773238")
        val file = item.playableFiles.single()
        val state = CloudLibraryUiState(
            isLoaded = true,
            providers = listOf(
                CloudLibraryProviderState(
                    provider = provider,
                    items = listOf(item),
                ),
            ),
        )

        val updated = state.withResolvedPlaybackUrl(
            item = item,
            file = file,
            url = "https://resolved.example/movie.mkv",
        )

        val target = assertNotNull(
            updated.findPlaybackTargetForProgress(
                contentId = item.stableKey,
                videoId = item.playbackVideoId(file),
            ),
        )
        assertEquals("https://resolved.example/movie.mkv", target.file.playbackUrl)
    }

    @Test
    fun `provider poster urls are mapped for cloud services`() {
        assertEquals(
            TorboxCloudLibraryPosterUrl,
            cloudLibraryProviderPosterUrl("torbox:Torrent:29773238"),
        )
        assertEquals(
            PremiumizeCloudLibraryPosterUrl,
            cloudLibraryProviderPosterUrl("cloud:premiumize"),
        )
        assertTrue(
            cloudLibraryDisplayArtworkUrl(TorboxCloudLibraryPosterUrl)
                ?.startsWith("data:image/svg+xml;base64,") == true,
        )
        assertEquals(
            PremiumizeCloudLibraryPosterUrl,
            cloudLibraryDisplayArtworkUrl(PremiumizeCloudLibraryPosterUrl),
        )
    }
}

private class FakeCloudProviderApi(
    override val provider: DebridProvider,
    private val items: List<CloudLibraryItem>,
    private val listResult: (suspend (String) -> Result<List<CloudLibraryItem>>)? = null,
    private val playbackResult: (suspend (String, CloudLibraryItem, CloudLibraryFile) -> CloudLibraryPlaybackResult)? = null,
) : CloudLibraryProviderApi {
    val listKeys = mutableListOf<String>()
    var resolvePlaybackCalls: Int = 0
        private set

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> {
        listKeys += apiKey
        return listResult?.invoke(apiKey) ?: Result.success(items)
    }

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile,
    ): CloudLibraryPlaybackResult {
        resolvePlaybackCalls += 1
        return playbackResult?.invoke(apiKey, item, file)
            ?: CloudLibraryPlaybackResult.Success(url = "https://example.test/${item.id}/${file.id}")
    }
}

private fun cloudProvider(id: String, name: String): DebridProvider =
    DebridProvider(
        id = id,
        displayName = name,
        shortName = name.take(1),
        capabilities = setOf(DebridProviderCapability.CloudLibrary),
    )

private fun cloudItem(
    provider: DebridProvider,
    id: String,
    addedAtEpochMs: Long? = null,
): CloudLibraryItem =
    CloudLibraryItem(
        providerId = provider.id,
        providerName = provider.displayName,
        id = id,
        type = CloudLibraryItemType.Torrent,
        name = id,
        addedAtEpochMs = addedAtEpochMs,
        files = listOf(
            CloudLibraryFile(
                id = "file-$id",
                name = "$id.mkv",
                playable = true,
            ),
        ),
    )

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
