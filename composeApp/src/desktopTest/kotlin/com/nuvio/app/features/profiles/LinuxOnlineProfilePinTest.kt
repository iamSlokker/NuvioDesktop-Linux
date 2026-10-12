package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.network.SupabaseProvider
import com.nuvio.app.testing.LocalHttpFixture
import com.nuvio.app.testing.respond
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.*

class LinuxOnlineProfilePinTest {
    @Test fun verifiedPinUsesRefreshedProfileVersionForOfflineUnlock() = withHeadlessFixture {
        runHeadlessProbe(it, OnlineProfilePinProbe::class.java, "accepted")
    }
    @Test fun rejectedPinDoesNotRefreshOrCache() = withHeadlessFixture {
        runHeadlessProbe(it, OnlineProfilePinProbe::class.java, "rejected")
    }
}

internal object OnlineProfilePinProbe {
    @Suppress("UNCHECKED_CAST")
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val accepted = args[1] == "accepted"
        val json = Json { encodeDefaults = true }
        fun profile(version: String) = NuvioProfile(profileIndex = 1, pinEnabled = true, updatedAt = version)
        ProfileStorage.savePayload(buildJsonObject {
            put("userId", "fixture-user")
            put("activeProfileIndex", 1)
            put("profiles", json.encodeToJsonElement(listOf(profile("version-1"))))
        }.toString())
        assertTrue(ProfileRepository.loadCachedProfiles())
        val field = AuthRepository.javaClass.getDeclaredField("_state").apply { isAccessible = true }
        val auth = field.get(AuthRepository) as MutableStateFlow<AuthState>
        auth.value = AuthState.Authenticated("fixture-user", null, false)
        LocalHttpFixture().use { server ->
            server.route("/rest/v1/rpc/verify_profile_pin") { exchange, request ->
                assertEquals("POST", request.method)
                assertEquals("1234", Json.parseToJsonElement(request.body).jsonObject.getValue("p_pin").jsonPrimitive.content)
                exchange.respond(200, "[{\"unlocked\":$accepted,\"retry_after_seconds\":17}]".toByteArray())
            }
            server.route("/rest/v1/rpc/sync_pull_profiles") { exchange, _ ->
                exchange.respond(200, json.encodeToJsonElement(listOf(profile("version-2"))).toString().toByteArray())
            }
            server.start()
            assertTrue(ServerConfigurationRepository.saveCustom(ServerConfiguration(
                server.url, "public-fixture-key", ServerCapabilities(true, false), true,
            )))
            try {
                val result = ProfileRepository.verifyPin(1, "1234")
                assertEquals(accepted, result.unlocked)
                assertEquals(17, result.retryAfterSeconds) // Proves RPC decoding, not local fallback.
                val requests = server.drainRequests().map { it.path }
                if (accepted) {
                    assertEquals(listOf("/rest/v1/rpc/verify_profile_pin", "/rest/v1/rpc/sync_pull_profiles"), requests)
                    val cache = json.decodeFromString<CachedProfilePinPayload>(assertNotNull(ProfilePinCacheStorage.loadPayload(1)))
                    assertEquals("version-2", cache.profileUpdatedAt)
                    auth.value = AuthState.Unauthenticated
                    ProfileRepository.clearInMemory()
                    assertTrue(ProfileRepository.loadCachedProfiles())
                    assertTrue(ProfileRepository.verifyPin(1, "1234").unlocked)
                } else {
                    assertEquals(listOf("/rest/v1/rpc/verify_profile_pin"), requests)
                    assertNull(ProfilePinCacheStorage.loadPayload(1))
                }
            } finally {
                SupabaseProvider.reset()
            }
        }
    }
}
