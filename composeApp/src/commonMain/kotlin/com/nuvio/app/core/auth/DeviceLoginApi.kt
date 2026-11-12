package com.nuvio.app.core.auth

import com.nuvio.app.core.network.ServerConfiguration
import com.nuvio.app.core.network.SupabaseProvider
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.rpc
import io.ktor.client.statement.bodyAsText
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Device pairing ("scan a QR code with your phone") against the Nuvio backend.
 *
 * The flow is the same one Nuvio TV uses, and is deliberately server-agnostic: a device asks the
 * backend for a session, shows the pairing URL as a QR code, polls until the account owner approves
 * it on their phone, then exchanges the approved code for real tokens. Only the two RPCs and one
 * edge function below are involved, so nothing here has to be re-derived if the UI changes.
 */
@Serializable
data class DeviceLoginSession(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3,
)

@Serializable
data class DeviceLoginPoll(
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int? = null,
) {
    val normalizedStatus: String
        get() = status.trim().lowercase()
}

@Serializable
data class DeviceLoginTokens(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
)

/** Poll statuses the backend may return, normalised. Anything unknown keeps the device waiting. */
enum class DeviceLoginStatus {
    Pending,
    Approved,
    Expired,
    Used,
    Cancelled,
    Unknown,
    ;

    companion object {
        fun from(raw: String): DeviceLoginStatus = when (raw.trim().lowercase()) {
            "pending" -> Pending
            "approved" -> Approved
            "expired" -> Expired
            "used" -> Used
            "cancelled", "canceled" -> Cancelled
            else -> Unknown
        }
    }
}

interface DeviceLoginClient {
    suspend fun start(session: DeviceLoginRequest): DeviceLoginSession
    suspend fun poll(deviceCode: String, deviceNonce: String): DeviceLoginPoll
    suspend fun exchange(deviceCode: String, deviceNonce: String): DeviceLoginTokens
}

data class DeviceLoginRequest(
    val deviceNonce: String,
    val deviceName: String?,
    val redirectBaseUrl: String,
    val deviceType: String = "desktop",
)

/**
 * Talks to the active backend through the app's Supabase client, so the RPCs use the same
 * publishable key, proxy and user agent as the rest of the app.
 */
object SupabaseDeviceLoginClient : DeviceLoginClient {
    private const val START_RPC = "start_device_login_session"
    private const val POLL_RPC = "poll_tv_login_session"
    private const val EXCHANGE_FUNCTION = "tv-logins-exchange"

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun start(session: DeviceLoginRequest): DeviceLoginSession {
        val body = buildJsonObject {
            put("p_device_nonce", session.deviceNonce)
            put("p_redirect_base_url", session.redirectBaseUrl)
            put("p_device_type", session.deviceType)
            session.deviceName?.takeIf { it.isNotBlank() }?.let { put("p_device_name", it) }
        }
        return SupabaseProvider.client.postgrest
            .rpc(START_RPC, body)
            .decodeList<DeviceLoginSession>()
            .firstOrNull()
            ?: error("$START_RPC returned no session")
    }

    override suspend fun poll(deviceCode: String, deviceNonce: String): DeviceLoginPoll {
        val body = buildJsonObject {
            put("p_code", deviceCode)
            put("p_device_nonce", deviceNonce)
        }
        return SupabaseProvider.client.postgrest
            .rpc(POLL_RPC, body)
            .decodeList<DeviceLoginPoll>()
            .firstOrNull()
            ?: error("$POLL_RPC returned no status")
    }

    override suspend fun exchange(deviceCode: String, deviceNonce: String): DeviceLoginTokens {
        val body = buildJsonObject {
            put("code", deviceCode)
            put("device_nonce", deviceNonce)
        }
        val response = SupabaseProvider.client.functions
            .invoke(EXCHANGE_FUNCTION, body)
        return json.decodeFromString(response.bodyAsText())
    }
}

/**
 * Where the phone lands after scanning. The official backend serves the sign-in page on the web
 * host; a self-hosted one serves it under `/link` on its own origin, which is what Nuvio TV passes.
 */
internal const val OFFICIAL_DEVICE_LOGIN_WEB_URL = "https://nuvio.tv/link"

internal fun deviceLoginWebBaseUrl(server: ServerConfiguration): String =
    if (server.isCustom) "${server.backendUrl.trimEnd('/')}/link" else OFFICIAL_DEVICE_LOGIN_WEB_URL

/** 192 bits of URL-safe randomness, matching what the TV app sends as `p_device_nonce`. */
internal object DeviceLoginNonce {
    private val random = SecureRandom()

    fun generate(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
