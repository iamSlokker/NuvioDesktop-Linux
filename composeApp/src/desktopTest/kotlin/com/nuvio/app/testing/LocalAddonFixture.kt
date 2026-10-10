package com.nuvio.app.testing

import com.nuvio.app.features.addons.AddonRepository
import com.nuvio.app.features.addons.AddonStorage
import com.nuvio.app.features.addons.ManagedAddon
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/** Loads real manifests from loopback through the stored-install path, without account sync. */
internal suspend fun LocalHttpFixture.loadInstalledAddons(ids: List<String>): List<ManagedAddon> {
    ids.forEach { id ->
        route("/$id/manifest.json") { exchange, _ ->
            exchange.respond(200, """{
                "id":"$id","name":"Provider $id","version":"1.0.0","description":"Fixture",
                "types":["movie","series"],
                "resources":[{"name":"stream","types":["movie","series"],"idPrefixes":["tt"]}],
                "catalogs":[{"type":"movie","id":"all","name":"Catalog $id",
                    "extra":[{"name":"search"},{"name":"genre","options":["Drama","Comedy"]},{"name":"skip"}]}]
            }""".toByteArray())
        }
    }
    AddonStorage.saveInstalledAddonUrls(1, ids.map { "$url/$it/manifest.json" })
    AddonRepository.initialize()
    return withTimeout(12_000) {
        AddonRepository.uiState.first { state ->
            state.addons.size == ids.size && state.addons.all { it.manifest != null && !it.isRefreshing }
        }.addons
    }
}
