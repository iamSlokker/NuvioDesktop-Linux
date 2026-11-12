package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.SupabaseProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Checks that the app's own Supabase client (not curl, not a fake) can start and poll a real
 * pairing session, so a schema change or a wrong parameter name fails here rather than on a user's
 * TV-bound login screen.
 *
 * Opt-in because it hits the network: `NUVIO_DEVICE_LOGIN_LIVE=1`. CI does not set it. Only
 * `start` and `poll` run — `exchange` would need a human approving the code on a phone.
 */
class DeviceLoginLiveTest {
    private val live = System.getenv("NUVIO_DEVICE_LOGIN_LIVE") == "1"

    @Test
    fun `starts and polls a pairing session against the official backend`() {
        if (!live) return
        // Nothing from the developer's own account may be touched by a test.
        SupabaseProvider.memoryOnlySession = true
        val server = ServerConfiguration(
            backendUrl = "https://api.nuvio.tv",
            publishableKey = OFFICIAL_TEST_KEY,
            capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
            isCustom = false,
        )
        runBlocking {
            val request = DeviceLoginRequest(
                deviceNonce = DeviceLoginNonce.generate(),
                deviceName = "nuvio-desktop-test",
                redirectBaseUrl = deviceLoginWebBaseUrl(server),
            )
            val session = SupabaseDeviceLoginClient.start(request)

            assertTrue(session.deviceCode.isNotBlank(), "device code")
            assertTrue(session.userCode.length in 4..12, "user code '${session.userCode}'")
            assertTrue(
                session.verificationUriComplete.contains(session.userCode),
                "pairing URL lacks the code: ${session.verificationUriComplete}",
            )
            assertTrue(session.pollIntervalSeconds >= 1, "poll interval")
            assertNotNull(session.expiresAt.toEpochMillisOrNull(), "expiry parses: ${session.expiresAt}")

            val poll = SupabaseDeviceLoginClient.poll(session.deviceCode, request.deviceNonce)
            assertEquals(DeviceLoginStatus.Pending, DeviceLoginStatus.from(poll.status))
        }
    }

    private fun String.toEpochMillisOrNull(): Long? = runCatching {
        java.time.OffsetDateTime.parse(this).toInstant().toEpochMilli()
    }.getOrNull()

    private companion object {
        /**
         * The publishable (anon) key the app ships with for api.nuvio.tv. Public by definition; it
         * is in ServerConfiguration.kt too, and is what any Nuvio client sends unauthenticated.
         */
        const val OFFICIAL_TEST_KEY =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiIsImlzcyI6InN1cGFiYXNlIiwiaWF0IjoxNzgxNTIxMzQ2LCJleHAiOjE5MzkyMDEzNDZ9.tmQaj682pwzehpqlgCDMnySOqiUvpgRbrE43T4VJpDI"
    }
}
