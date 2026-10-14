package com.nuvio.app.features.metadata

import com.nuvio.app.core.build.PackagedResources

internal actual object AnimeIdMappingStorage {
    actual fun loadAnimeListText(): String? =
        PackagedResources.open("anime-list-mini.json")
            ?.bufferedReader()
            ?.use { it.readText() }
            // Linux release packages omit the dataset; fetch the pinned revision instead.
            ?: AnimeListDownload.loadOrFetch()
}
