package com.nuvio.app.features.auth

import co.touchlab.kermit.Logger
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.DeviceLoginClient
import com.nuvio.app.core.auth.DeviceLoginNonce
import com.nuvio.app.core.auth.DeviceLoginRequest
import com.nuvio.app.core.auth.DeviceLoginStatus
import com.nuvio.app.core.auth.DeviceLoginTokens
import com.nuvio.app.core.auth.SupabaseDeviceLoginClient
import com.nuvio.app.core.auth.deviceLoginWebBaseUrl
import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.ServerConfigurationRepository
import com.nuvio.app.core.qr.QrCodeEncoder
import com.nuvio.app.core.qr.QrCodeMatrix
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.compose_auth_qr_exchange_failed
import nuvio.composeapp.generated.resources.compose_auth_qr_start_failed
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

enum class QrLoginPhase {
    Idle,

    /** Asking the backend for a pairing session; nothing to show yet. */
    Starting,

    /** Code is on screen; the phone has not approved it yet. Polling runs in the background. */
    Waiting,

    /** Approved on the phone; tokens are being exchanged and imported. */
    SigningIn,

    SignedIn,

    /** The code can no longer be used — show a new one. */
    Expired,

    Failed,
}

data class QrLoginUiState(
    val phase: QrLoginPhase = QrLoginPhase.Idle,
    val matrix: QrCodeMatrix? = null,
    val userCode: String? = null,
    val expiresAtMillis: Long? = null,
    val error: String? = null,
) {
    val isBusy: Boolean
        get() = phase == QrLoginPhase.Starting || phase == QrLoginPhase.SigningIn

    val isWaiting: Boolean
        get() = phase == QrLoginPhase.Waiting
}

/**
 * Drives one QR pairing attempt: request a session, encode it, poll until the phone approves it,
 * then hand the tokens to [AuthRepository].
 *
 * The network, clock and wait are injected so the whole flow can be exercised without a backend, a
 * coroutine test dispatcher or a running Compose scene.
 */
class QrLoginController(
    private val client: DeviceLoginClient = SupabaseDeviceLoginClient,
    private val signIn: suspend (DeviceLoginTokens) -> Result<Unit> = { AuthRepository.completeDeviceLogin(it) },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (millis: Long) -> Unit = { delay(it) },
    private val server: () -> ServerConfiguration = { ServerConfigurationRepository.active.value },
    private val deviceName: () -> String = ::defaultDeviceName,
) {
    private val log = Logger.withTag("QrLogin")
    private val _state = MutableStateFlow(QrLoginUiState())
    val state: StateFlow<QrLoginUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var deviceCode: String? = null
    private var deviceNonce: String? = null
    private var pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS

    /**
     * Starts a session, or starts over after expiry/failure.
     *
     * A live code is left alone so opening the page twice cannot rotate the code someone is
     * halfway through scanning — but the "get a new code" button is exactly the case where a live
     * code *is* the thing to replace (wrong phone, unscannable code, someone else's account), so
     * [refresh] overrides that.
     */
    fun start() = begin(force = false)

    /** Replaces the code on screen whatever state it is in. The button's action. */
    fun refresh() = begin(force = true)

    private fun begin(force: Boolean) {
        when (_state.value.phase) {
            QrLoginPhase.Starting, QrLoginPhase.Waiting, QrLoginPhase.SigningIn -> if (!force) return
            else -> Unit
        }
        job?.cancel()
        deviceCode = null
        deviceNonce = null
        pollIntervalSeconds = DEFAULT_POLL_INTERVAL_SECONDS
        _state.value = QrLoginUiState(phase = QrLoginPhase.Starting)
        job = scope.launch {
            val request = DeviceLoginRequest(
                deviceNonce = DeviceLoginNonce.generate(),
                deviceName = deviceName(),
                redirectBaseUrl = deviceLoginWebBaseUrl(server()),
            )
            deviceNonce = request.deviceNonce
            val session = try {
                client.start(request)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                log.e(error) { "Device login session could not be started" }
                fail(error, Res.string.compose_auth_qr_start_failed)
                return@launch
            }
            deviceCode = session.deviceCode
            pollIntervalSeconds = session.pollIntervalSeconds.coerceAtLeast(1)
            val matrix = QrCodeEncoder.encode(session.verificationUriComplete).getOrElse { error ->
                // A backend that hands back an unusable pairing URL is a server problem, not a
                // camera problem: say so instead of drawing a blank square.
                log.e(error) { "Pairing URL could not be encoded as a QR code" }
                fail(error, Res.string.compose_auth_qr_start_failed)
                return@launch
            }
            _state.value = QrLoginUiState(
                phase = QrLoginPhase.Waiting,
                matrix = matrix,
                userCode = session.userCode,
                expiresAtMillis = session.expiresAt.toEpochMillis(),
            )
            pollUntilTerminal()
        }
    }

    /** Drops the current code and forgets the session (leaving the QR tab, or closing the screen). */
    fun cancel() {
        job?.cancel()
        job = null
        deviceCode = null
        deviceNonce = null
        _state.value = QrLoginUiState()
    }

    private suspend fun pollUntilTerminal() {
        while (true) {
            val expiresAt = _state.value.expiresAtMillis
            if (expiresAt != null && now() >= expiresAt) {
                log.i { "Pairing code expired before approval" }
                _state.value = _state.value.copy(phase = QrLoginPhase.Expired)
                return
            }
            pause(pollIntervalSeconds * 1000L)

            val code = deviceCode ?: return
            val nonce = deviceNonce ?: return
            val poll = try {
                client.poll(code, nonce)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Exception) {
                // A failed poll is usually a blip (phone hotspot, VPN flap). The code itself is
                // still valid, so keep it on screen and try again at the next interval rather than
                // throwing away a code the user may be halfway through approving.
                log.w(error) { "Pairing poll failed; retrying in ${pollIntervalSeconds}s" }
                continue
            }
            poll.expiresAt?.toEpochMillis()?.let { expires ->
                _state.value = _state.value.copy(expiresAtMillis = expires)
            }
            poll.pollIntervalSeconds?.let { pollIntervalSeconds = it.coerceAtLeast(1) }

            when (DeviceLoginStatus.from(poll.status)) {
                DeviceLoginStatus.Pending, DeviceLoginStatus.Unknown -> Unit
                DeviceLoginStatus.Approved -> {
                    exchange()
                    return
                }
                DeviceLoginStatus.Expired,
                DeviceLoginStatus.Used,
                DeviceLoginStatus.Cancelled,
                -> {
                    log.i { "Pairing code closed with status ${poll.status}" }
                    _state.value = _state.value.copy(phase = QrLoginPhase.Expired)
                    return
                }
            }
        }
    }

    private suspend fun exchange() {
        val code = deviceCode ?: return
        val nonce = deviceNonce ?: return
        _state.value = _state.value.copy(phase = QrLoginPhase.SigningIn, error = null)
        val tokens = try {
            client.exchange(code, nonce)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            log.e(error) { "Approved pairing could not be exchanged for a session" }
            fail(error, Res.string.compose_auth_qr_exchange_failed)
            return
        }
        signIn(tokens).fold(
            onSuccess = {
                log.i { "Signed in from a paired device" }
                _state.value = _state.value.copy(phase = QrLoginPhase.SignedIn)
            },
            onFailure = { error ->
                log.e(error) { "Paired session could not be imported" }
                fail(error, Res.string.compose_auth_qr_exchange_failed)
            },
        )
    }

    private suspend fun fail(error: Throwable, fallback: StringResource) {
        val message = error.readableMessage() ?: getString(fallback)
        _state.value = _state.value.copy(phase = QrLoginPhase.Failed, error = message)
    }

    private companion object {
        const val DEFAULT_POLL_INTERVAL_SECONDS = 3
    }
}

/** Host name is enough for the linked-devices list; a full build string adds nothing actionable. */
internal fun defaultDeviceName(): String =
    runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "Nuvio Desktop"

/** Backend timestamps are ISO-8601 with an offset; be liberal about the trailing form. */
internal fun String.toEpochMillis(): Long? = runCatching {
    OffsetDateTime.parse(trim()).toInstant().toEpochMilli()
}.recoverCatching {
    Instant.parse(trim()).toEpochMilli()
}.recoverCatching {
    LocalDateTime.parse(trim()).toInstant(ZoneOffset.UTC).toEpochMilli()
}.getOrNull()

/** The backend's `{"error": "..."}` body reads better than the wrapping HTTP exception. */
private fun Throwable.readableMessage(): String? {
    val raw = message?.trim().orEmpty()
    if (raw.isBlank()) return null
    val serverError = Regex("\"error\"\\s*:\\s*\"([^\"]{1,160})\"")
        .find(raw)
        ?.groupValues
        ?.get(1)
    val candidate = (serverError ?: raw)
        .replace(Regex("<[^>]+>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    return candidate.takeIf { it.isNotBlank() && !it.contains("<html", ignoreCase = true) }
}
