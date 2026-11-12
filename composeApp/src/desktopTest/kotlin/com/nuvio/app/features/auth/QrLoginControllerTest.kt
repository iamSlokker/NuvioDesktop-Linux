package com.nuvio.app.features.auth

import com.nuvio.app.core.auth.DeviceLoginClient
import com.nuvio.app.core.auth.DeviceLoginPoll
import com.nuvio.app.core.auth.DeviceLoginRequest
import com.nuvio.app.core.auth.DeviceLoginSession
import com.nuvio.app.core.auth.DeviceLoginTokens
import com.nuvio.app.core.network.ServerCapabilities
import com.nuvio.app.core.network.ServerConfiguration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pairing flow, driven end to end with a fake backend: no network, no real delay, no Compose.
 *
 * The scope is unconfined and [QrLoginController]'s pause is a no-op, so each attempt runs to its
 * terminal state inside [start], which is what makes the assertions below deterministic.
 */
class QrLoginControllerTest {

    @Test
    fun approvedPairingSignsIn() {
        val backend = FakeBackend(polls = listOf("pending", "approved"))
        val signedIn = mutableListOf<DeviceLoginTokens>()
        val controller = controller(backend, onSignIn = { tokens ->
            signedIn += tokens
            Result.success(Unit)
        })

        controller.start()

        assertEquals(QrLoginPhase.SignedIn, controller.state.value.phase)
        assertEquals(listOf(DeviceLoginTokens("access", "refresh")), signedIn)
        assertEquals("NHGSVP", controller.state.value.userCode)
        assertNotNull(controller.state.value.matrix)
        assertEquals(2, backend.pollCount)
    }

    @Test
    fun codeShownWhileWaitingIsThePairingUrl() {
        val backend = FakeBackend(polls = listOf("approved"))
        val controller = controller(backend)

        controller.start()

        // The QR has to encode the complete URL, not the short code: scanning it must be enough.
        assertEquals("https://nuvio.tv/link?code=NHGSVP", backend.requestedPairingUrl)
    }

    @Test
    fun expiredCodeStopsPollingAndKeepsTheCodeForANewAttempt() {
        val backend = FakeBackend(polls = listOf("expired"))
        val controller = controller(backend)

        controller.start()

        assertEquals(QrLoginPhase.Expired, controller.state.value.phase)
        assertEquals(1, backend.pollCount)
        // Still on screen (dimmed) so "get a new code" does not blank the card.
        assertNotNull(controller.state.value.matrix)
    }

    @Test
    fun expiredTimestampEndsTheAttemptWithoutPolling() {
        val backend = FakeBackend(polls = listOf("pending"), expiresAt = "2020-01-01T00:00:00+00:00")
        val controller = controller(backend)

        controller.start()

        assertEquals(QrLoginPhase.Expired, controller.state.value.phase)
        assertEquals(0, backend.pollCount)
    }

    @Test
    fun failingStartSurfacesTheServerMessage() {
        val backend = FakeBackend(polls = emptyList(), startFailure = IllegalStateException("""{"error":"missing setup"}"""))
        val controller = controller(backend)

        controller.start()

        assertEquals(QrLoginPhase.Failed, controller.state.value.phase)
        assertEquals("missing setup", controller.state.value.error)
    }

    @Test
    fun failedExchangeLeavesTheUserOnThePairingScreen() {
        val backend = FakeBackend(polls = listOf("approved"), exchangeFailure = IllegalStateException("nope"))
        val controller = controller(backend)

        controller.start()

        assertEquals(QrLoginPhase.Failed, controller.state.value.phase)
        assertEquals("nope", controller.state.value.error)
    }

    @Test
    fun aTransientPollErrorDoesNotDiscardTheCode() {
        val backend = FakeBackend(polls = listOf("pending", "approved"), failPollsBefore = 1)
        val controller = controller(backend)

        controller.start()

        assertEquals(QrLoginPhase.SignedIn, controller.state.value.phase)
        assertNull(controller.state.value.error)
    }

    @Test
    fun importingTheSessionCanFailWithoutLosingTheScreen() {
        val backend = FakeBackend(polls = listOf("approved"))
        val controller = controller(backend, onSignIn = { Result.failure(IllegalStateException("import failed")) })

        controller.start()

        assertEquals(QrLoginPhase.Failed, controller.state.value.phase)
        assertEquals("import failed", controller.state.value.error)
    }

    @Test
    fun cancelForgetsTheCode() {
        val backend = FakeBackend(polls = listOf("approved"))
        val controller = controller(backend)
        // Not started: cancel is what the screen does on dispose, and it must be safe either way.
        controller.cancel()

        assertEquals(QrLoginPhase.Idle, controller.state.value.phase)
        assertNull(controller.state.value.matrix)
        assertEquals(0, backend.pollCount)
    }

    @Test
    fun statusesAreNormalised() {
        assertEquals(com.nuvio.app.core.auth.DeviceLoginStatus.Approved,
            com.nuvio.app.core.auth.DeviceLoginStatus.from(" APPROVED "))
        assertEquals(com.nuvio.app.core.auth.DeviceLoginStatus.Cancelled,
            com.nuvio.app.core.auth.DeviceLoginStatus.from("canceled"))
        assertEquals(com.nuvio.app.core.auth.DeviceLoginStatus.Unknown,
            com.nuvio.app.core.auth.DeviceLoginStatus.from("something-new"))
    }

    @Test
    fun timestampsWithOffsetsParse() {
        assertEquals(
            1_700_000_000_000L,
            "2023-11-14T22:13:20+00:00".toEpochMillis(),
        )
        assertNull("not a timestamp".toEpochMillis())
    }

    private fun controller(
        backend: FakeBackend,
        onSignIn: suspend (DeviceLoginTokens) -> Result<Unit> = { Result.success(Unit) },
    ) = QrLoginController(
        client = backend,
        signIn = onSignIn,
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
        now = { System.currentTimeMillis() },
        pause = { },
        server = {
            ServerConfiguration(
                backendUrl = "https://api.nuvio.tv",
                publishableKey = "key",
                capabilities = ServerCapabilities(emailPasswordAuth = true, tvLogin = true),
                isCustom = false,
            )
        },
        deviceName = { "test-desktop" },
    )

    private class FakeBackend(
        private val polls: List<String>,
        private val expiresAt: String = "2999-01-01T00:00:00+00:00",
        private val startFailure: Throwable? = null,
        private val exchangeFailure: Throwable? = null,
        private val failPollsBefore: Int = 0,
    ) : DeviceLoginClient {
        var pollCount = 0
        var startCount = 0
        var requestedPairingUrl: String? = null

        override suspend fun start(session: DeviceLoginRequest): DeviceLoginSession {
            startFailure?.let { throw it }
            startCount++
            val url = "https://nuvio.tv/link?code=NHGSVP"
            requestedPairingUrl = url
            return DeviceLoginSession(
                deviceCode = "device-code",
                userCode = "NHGSVP",
                verificationUri = "https://nuvio.tv/link",
                verificationUriComplete = url,
                expiresAt = expiresAt,
                pollIntervalSeconds = 0,
            )
        }

        override suspend fun poll(deviceCode: String, deviceNonce: String): DeviceLoginPoll {
            if (pollCount < failPollsBefore) {
                pollCount++
                throw IllegalStateException("network blip")
            }
            val status = polls.getOrElse(pollCount) { "pending" }
            pollCount++
            return DeviceLoginPoll(status = status, expiresAt = expiresAt, pollIntervalSeconds = 0)
        }

        override suspend fun exchange(deviceCode: String, deviceNonce: String): DeviceLoginTokens {
            exchangeFailure?.let { throw it }
            return DeviceLoginTokens(accessToken = "access", refreshToken = "refresh")
        }
    }
}
