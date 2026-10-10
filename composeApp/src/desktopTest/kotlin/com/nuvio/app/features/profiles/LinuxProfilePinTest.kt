package com.nuvio.app.features.profiles

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.test.*

class LinuxProfilePinTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxProfilePinProbe::class.java, case) }
    @Test fun correctCachedPinUnlocksThroughRepository() = probe("correct")
    @Test fun wrongPinDoesNotUnlockOrInvalidateGoodCache() = probe("wrong")
    @Test fun emptyPinDoesNotUnlockLockedProfile() = probe("empty")
    @Test fun missingCacheRequiresOnlineVerification() = probe("missing")
    @Test fun corruptCacheCannotUnlock() = probe("corrupt")
    @Test fun profileWithoutPinDoesNotRequireCache() = probe("disabled")
    @Test fun timestampMismatchRemovesCacheDuringVerification() = probe("timestamp")
    @Test fun reloadInvalidatesCacheForChangedProfile() = probe("reloadChanged")
    @Test fun independentProfilesCannotReuseEachOthersPin() = probe("independent")
    @Test fun copiedCacheIsBoundToProfileIndex() = probe("copied")
    @Test fun cacheAndProfileSurviveFreshProcessReload() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxProfilePinProbe::class.java, "write")
        runHeadlessProbe(it, LinuxProfilePinProbe::class.java, "read")
    }
}

internal object LinuxProfilePinProbe {
    private val json = Json { encodeDefaults = true }
    private fun profiles(updatedAt: String = "version-1", enabled: Boolean = true) {
        ProfileStorage.savePayload(buildJsonObject {
            put("userId", "fixture-user")
            put("activeProfileIndex", 1)
            put("profiles", json.encodeToJsonElement(listOf(
                NuvioProfile(profileIndex = 1, pinEnabled = enabled, updatedAt = updatedAt),
                NuvioProfile(profileIndex = 2, pinEnabled = true, updatedAt = "version-1"),
            )))
        }.toString())
    }
    private fun cache(index: Int, pin: String, updatedAt: String = "version-1") {
        val salt = "fixture-salt-$index"
        ProfilePinCacheStorage.savePayload(index, json.encodeToString(CachedProfilePinPayload(
            salt, hashProfilePin(index, salt, pin), updatedAt,
        )))
    }

    @JvmStatic fun main(args: Array<String>) = runBlocking {
        val case = args[1]
        assertFalse(AuthRepository.state.value is AuthState.Authenticated)
        if (case != "read") {
            profiles(enabled = case != "disabled")
            cache(1, "1234")
            cache(2, "9876")
        }
        assertTrue(ProfileRepository.loadCachedProfiles())
        when (case) {
            "correct", "read", "write" -> {
                assertTrue(ProfileRepository.verifyPin(1, "1234").unlocked)
                assertTrue(ProfileRepository.verifyPin(2, "9876").unlocked)
            }
            "wrong", "empty" -> {
                val before = ProfilePinCacheStorage.loadPayload(1)
                val result = ProfileRepository.verifyPin(1, if (case == "empty") "" else "9999")
                assertFalse(result.unlocked)
                assertFalse(result.message.isNullOrBlank())
                assertEquals(before, ProfilePinCacheStorage.loadPayload(1))
                assertTrue(ProfileRepository.verifyPin(1, "1234").unlocked)
            }
            "missing", "corrupt" -> {
                if (case == "missing") ProfilePinCacheStorage.removePayload(1)
                else ProfilePinCacheStorage.savePayload(1, "{corrupt")
                val result = ProfileRepository.verifyPin(1, "1234")
                assertFalse(result.unlocked)
                assertFalse(result.message.isNullOrBlank())
                assertTrue(ProfileRepository.verifyPin(2, "9876").unlocked)
            }
            "disabled" -> {
                assertNull(ProfilePinCacheStorage.loadPayload(1)) // Cache synchronization removed it.
                assertTrue(ProfileRepository.verifyPin(1, "").unlocked)
            }
            "timestamp" -> {
                cache(1, "1234", "stale-version")
                assertFalse(ProfileRepository.verifyPin(1, "1234").unlocked)
                assertNull(ProfilePinCacheStorage.loadPayload(1))
                assertTrue(ProfileRepository.verifyPin(2, "9876").unlocked)
            }
            "reloadChanged" -> {
                profiles(updatedAt = "version-2")
                ProfileRepository.clearInMemory()
                assertTrue(ProfileRepository.loadCachedProfiles())
                assertNull(ProfilePinCacheStorage.loadPayload(1))
                assertFalse(ProfileRepository.verifyPin(1, "1234").unlocked)
                cache(1, "4321", "version-2")
                assertTrue(ProfileRepository.verifyPin(1, "4321").unlocked)
            }
            "independent" -> {
                assertFalse(ProfileRepository.verifyPin(1, "9876").unlocked)
                assertFalse(ProfileRepository.verifyPin(2, "1234").unlocked)
                assertTrue(ProfileRepository.verifyPin(1, "1234").unlocked)
                assertTrue(ProfileRepository.verifyPin(2, "9876").unlocked)
            }
            "copied" -> {
                ProfilePinCacheStorage.savePayload(2, assertNotNull(ProfilePinCacheStorage.loadPayload(1)))
                assertFalse(ProfileRepository.verifyPin(2, "1234").unlocked)
                assertTrue(ProfileRepository.verifyPin(1, "1234").unlocked)
            }
        }
    }
}
