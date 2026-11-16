package com.nuvio.app.features.backup

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.iptv.IptvRepository
import com.nuvio.app.features.settings.buildSettingsBackupArchive
import com.nuvio.app.features.settings.stageSettingsBackupArchive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.awt.Desktop
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists

/** Google OAuth client configuration. Read from the environment; never baked into source. */
internal data class GoogleDriveConfig(
    val clientId: String,
    val clientSecret: String,
    val scope: String,
) {
    val configured: Boolean get() = clientId.isNotBlank()

    companion object {
        fun fromEnvironment(): GoogleDriveConfig = GoogleDriveConfig(
            clientId = read("nuvio.drive.clientId", "NUVIO_DRIVE_CLIENT_ID"),
            clientSecret = read("nuvio.drive.clientSecret", "NUVIO_DRIVE_CLIENT_SECRET"),
            scope = read("nuvio.drive.scope", "NUVIO_DRIVE_SCOPE")
                .ifBlank { "https://www.googleapis.com/auth/drive" },
        )

        private fun read(property: String, env: String): String =
            System.getProperty(property)?.takeIf { it.isNotBlank() }
                ?: System.getenv(env)?.takeIf { it.isNotBlank() }
                ?: ""
    }
}

/** Refresh token + account metadata. Kept out of the ordinary settings stores. */
internal interface DriveTokenStore {
    fun load(): DriveToken?
    fun save(token: DriveToken)
    fun clear()
}

internal data class DriveToken(
    val refreshToken: String,
    val email: String?,
    val lastBackupMs: Long = 0L,
    val localHashAtBackup: String = "",
    val fileIds: String = "",
)

/**
 * Keyring-backed store, with a 0600 file fallback where no keyring is available. The fallback is
 * still outside every settings store and is excluded from the ordinary settings backup (it is not
 * a `*.properties` store).
 */
internal class LinuxDriveTokenStore(
    private val file: Path = DesktopStorage.rootDir.resolve("google_drive_auth.properties"),
) : DriveTokenStore {
    override fun load(): DriveToken? {
        val raw = keyringLookup() ?: runCatching {
            if (!Files.isRegularFile(file)) return null
            Files.readString(file)
        }.getOrNull() ?: return null
        val map = raw.lineSequence().mapNotNull { line ->
            val i = line.indexOf('=')
            if (i <= 0) null else line.substring(0, i) to line.substring(i + 1)
        }.toMap()
        val refresh = map["refresh_token"]?.takeIf { it.isNotBlank() } ?: return null
        return DriveToken(
            refreshToken = refresh,
            email = map["email"],
            lastBackupMs = map["last_backup_ms"]?.toLongOrNull() ?: 0L,
            localHashAtBackup = map["local_hash"].orEmpty(),
            fileIds = map["file_ids"].orEmpty(),
        )
    }

    override fun save(token: DriveToken) {
        val text = buildString {
            appendLine("refresh_token=${token.refreshToken}")
            token.email?.let { appendLine("email=$it") }
            appendLine("last_backup_ms=${token.lastBackupMs}")
            appendLine("local_hash=${token.localHashAtBackup}")
            appendLine("file_ids=${token.fileIds}")
        }
        if (keyringStore(text)) return
        runCatching {
            Files.createDirectories(file.parent)
            Files.write(
                file,
                text.toByteArray(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            )
            runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
        }
    }

    override fun clear() {
        keyringClear()
        runCatching { Files.deleteIfExists(file) }
    }

    private fun keyringLookup(): String? = runCatching {
        val p = ProcessBuilder("secret-tool", "lookup", "service", "nuvio-drive", "account", "default")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (!p.waitFor(3, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isBlank()) null else out
    }.getOrNull()

    private fun keyringStore(text: String): Boolean = runCatching {
        val p = ProcessBuilder(
            "secret-tool", "store", "--label=Nuvio Google Drive",
            "service", "nuvio-drive", "account", "default",
        ).redirectErrorStream(true).start()
        p.outputStream.use { it.write(text.toByteArray()) }
        p.waitFor(3, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private fun keyringClear() {
        runCatching {
            ProcessBuilder("secret-tool", "clear", "service", "nuvio-drive", "account", "default")
                .redirectErrorStream(true).start().waitFor(3, TimeUnit.SECONDS)
        }
    }
}

/** The minimum Drive surface the backup needs, so tests can fake it. */
internal interface DriveApi {
    suspend fun findFile(name: String): DriveFile?
    suspend fun uploadNew(name: String, mimeType: String, bytes: ByteArray): String
    suspend fun update(fileId: String, mimeType: String, bytes: ByteArray)
    suspend fun download(fileId: String): ByteArray
}

internal data class DriveFile(val id: String, val modifiedMs: Long)

/** The Live TV settings the backup reads and writes, so tests can supply a fake. */
internal interface IptvBackupSource {
    fun current(): com.nuvio.app.features.iptv.IptvSettings
    fun replace(settings: com.nuvio.app.features.iptv.IptvSettings)
}

internal object RepositoryIptvBackupSource : IptvBackupSource {
    override fun current(): com.nuvio.app.features.iptv.IptvSettings {
        IptvRepository.ensureLoaded()
        return IptvRepository.settings.value
    }

    override fun replace(settings: com.nuvio.app.features.iptv.IptvSettings) {
        IptvRepository.replaceFromBackup(settings)
    }
}

internal class DriveAuthException(message: String) : Exception(message)

/** Real Drive REST calls, using the access token refreshed by [GoogleTokenProvider]. */
internal class HttpDriveApi(private val tokens: GoogleTokenProvider) : DriveApi {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun findFile(name: String): DriveFile? = withContext(Dispatchers.IO) {
        val q = "name='${name.replace("'", "\\'")}' and trashed=false and 'root' in parents"
        val url = "https://www.googleapis.com/drive/v3/files?spaces=drive&fields=files(id,modifiedTime)" +
            "&orderBy=modifiedTime%20desc&q=" + URLEncoder.encode(q, "UTF-8")
        val body = request("GET", url) ?: return@withContext null
        val files = runCatching { json.parseToJsonElement(body).jsonObject["files"] }.getOrNull()
            ?: return@withContext null
        val first = runCatching { files.jsonArray.first().jsonObject }.getOrNull() ?: return@withContext null
        DriveFile(
            id = first["id"].toString().trim('"'),
            modifiedMs = runCatching {
                java.time.Instant.parse(first["modifiedTime"].toString().trim('"')).toEpochMilli()
            }.getOrDefault(0L),
        )
    }

    override suspend fun uploadNew(name: String, mimeType: String, bytes: ByteArray): String =
        withContext(Dispatchers.IO) {
            val boundary = "nuvio-" + UUID.randomUUID()
            val metadata = """{"name":"${name.replace("\"", "\\\"")}","parents":["root"]}"""
            val out = ByteArrayOutputStream()
            fun part(header: String, body: ByteArray) {
                out.write("--$boundary\r\n".toByteArray())
                out.write(header.toByteArray())
                out.write("\r\n\r\n".toByteArray())
                out.write(body)
                out.write("\r\n".toByteArray())
            }
            part("Content-Type: application/json; charset=UTF-8", metadata.toByteArray())
            part("Content-Type: $mimeType", bytes)
            out.write("--$boundary--\r\n".toByteArray())
            val body = request(
                "POST",
                "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id",
                out.toByteArray(),
                "multipart/related; boundary=$boundary",
            ) ?: error("Drive did not return the new file")
            json.parseToJsonElement(body).jsonObject["id"].toString().trim('"')
        }

    override suspend fun update(fileId: String, mimeType: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        request(
            "PATCH",
            "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media",
            bytes,
            mimeType,
        ) ?: error("Drive did not accept the update")
        Unit
    }

    override suspend fun download(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val token = tokens.accessToken() ?: throw DriveAuthException("no token")
        val conn = URL("https://www.googleapis.com/drive/v3/files/$fileId?alt=media").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        val code = conn.responseCode
        if (code == 401) { conn.disconnect(); throw DriveAuthException("Google sign-in expired.") }
        if (code !in 200..299) { conn.disconnect(); error("Google Drive HTTP $code") }
        conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
    }

    private fun request(method: String, url: String, body: ByteArray? = null, contentType: String? = null): String? {
        val token = runCatching {
            kotlinx.coroutines.runBlocking { tokens.accessToken() }
        }.getOrNull() ?: throw DriveAuthException("no token")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
        if (body != null) {
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        conn.disconnect()
        if (code == 401) throw DriveAuthException("Google sign-in expired.")
        if (code == 404) return null
        if (code !in 200..299) error("Google Drive HTTP $code")
        return text
    }
}

/** Refreshes the access token from the stored refresh token. */
internal class GoogleTokenProvider(
    private val store: DriveTokenStore,
    private val config: GoogleDriveConfig,
) {
    @Volatile private var access: String? = null
    @Volatile private var expiresAt = 0L
    private val mutex = Mutex()

    suspend fun accessToken(): String? = mutex.withLock {
        access?.let { if (System.currentTimeMillis() < expiresAt - 60_000) return@withLock it }
        val refresh = store.load()?.refreshToken ?: return@withLock null
        val fields = linkedMapOf(
            "client_id" to config.clientId,
            "refresh_token" to refresh,
            "grant_type" to "refresh_token",
        )
        if (config.clientSecret.isNotBlank()) fields["client_secret"] = config.clientSecret
        val body = postForm("https://oauth2.googleapis.com/token", fields)
        val token = body["access_token"]?.toString()?.trim('"') ?: return@withLock null
        access = token
        expiresAt = System.currentTimeMillis() +
            ((body["expires_in"]?.toString()?.trim('"')?.toLongOrNull() ?: 3600L) * 1000)
        token
    }

    fun invalidate() {
        access = null
        expiresAt = 0L
    }
}

private fun postForm(url: String, fields: Map<String, String>): JsonObject {
    val encoded = fields.entries.joinToString("&") {
        URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
    }
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 20_000
    conn.readTimeout = 30_000
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
    conn.outputStream.use { it.write(encoded.toByteArray()) }
    val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
        ?.bufferedReader()?.use { it.readText() }.orEmpty()
    conn.disconnect()
    return runCatching { Json.parseToJsonElement(text).jsonObject }.getOrDefault(JsonObject(emptyMap()))
}

/**
 * Where the two backup files live: the Drive **root**, so the Live TV one stays importable by
 * NuvioPlusIPTV. `drive.file` cannot see files this app did not create, which is why the default
 * scope is Drive-wide; the configuration can narrow it explicitly.
 */
internal class GoogleDriveBackupController(
    private val config: GoogleDriveConfig,
    private val tokenStore: DriveTokenStore,
    private val apiFactory: (GoogleTokenProvider) -> DriveApi = { HttpDriveApi(it) },
    private val iptv: IptvBackupSource = RepositoryIptvBackupSource,
    /** Overridden by tests so a mock Drive never needs a real Google token. */
    private val testAccessToken: (suspend () -> String?)? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val tokens = GoogleTokenProvider(tokenStore, config)
    private suspend fun accessToken(): String? = testAccessToken?.invoke() ?: tokens.accessToken()
    private val api: DriveApi by lazy { apiFactory(tokens) }
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<GoogleDriveBackupState> = _state.asStateFlow()
    private var signInJob: Job? = null

    private fun initialState(): GoogleDriveBackupState {
        val token = tokenStore.load()
        return GoogleDriveBackupState(
            configured = config.configured,
            connected = token != null,
            email = token?.email,
            lastBackupMs = token?.lastBackupMs ?: 0L,
        )
    }

    fun refreshStatus() {
        scope.launch {
            val token = tokenStore.load()
            if (token == null) {
                _state.value = _state.value.copy(connected = false, email = null, remoteExists = false)
                return@launch
            }
            _state.value = _state.value.copy(connected = true, email = token.email)
            runCatching {
                accessToken() ?: throw DriveAuthException("no token")
                val file = api.findFile(LegacyIptvBackup.FILE_NAME)
                val savedAt = file?.let { readSavedAt(it.id) } ?: 0L
                _state.value = _state.value.copy(
                    remoteExists = file != null,
                    remoteSavedAtMs = savedAt,
                    remoteOlderThanLocal = file != null && localIptvHash() != token.localHashAtBackup,
                    needsReauth = false,
                )
            }.onFailure { e ->
                if (e is DriveAuthException) {
                    _state.value = _state.value.copy(needsReauth = true)
                } else {
                    _state.value = _state.value.copy(message = "Could not check Google Drive: ${e.message}")
                }
            }
        }
    }

    fun connect() {
        if (!config.configured) {
            _state.value = _state.value.copy(
                message = "Google Drive is not configured in this build. Set NUVIO_DRIVE_CLIENT_ID.",
            )
            return
        }
        signInJob?.cancel()
        signInJob = scope.launch {
            _state.value = _state.value.copy(busy = true, message = null, needsReauth = false)
            runCatching { signIn() }
                .onSuccess { token ->
                    tokenStore.save(token)
                    _state.value = _state.value.copy(
                        connected = true, email = token.email, busy = false, signInUrl = null,
                        message = "Google Drive connected.",
                    )
                    refreshStatus()
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(
                        busy = false, signInUrl = null,
                        message = "Google sign-in failed: ${e.message ?: e::class.simpleName}",
                    )
                }
        }
    }

    fun cancelSignIn() {
        signInJob?.cancel()
        _state.value = _state.value.copy(busy = false, signInUrl = null)
    }

    fun disconnect() {
        val token = tokenStore.load()
        tokenStore.clear()
        tokens.invalidate()
        _state.value = GoogleDriveBackupState(configured = config.configured)
        if (token != null) scope.launch {
            runCatching {
                postForm("https://oauth2.googleapis.com/revoke", mapOf("token" to token.refreshToken))
            }
        }
    }

    fun backupNow() {
        scope.launch {
            mutex.withLock {
                if (!begin("Backing up…")) return@withLock
                runCatching { push() }
                    .onSuccess { message -> _state.value = _state.value.copy(busy = false, message = message) }
                    .onFailure(::fail)
            }
        }
    }

    fun restoreNow(liveTv: Boolean, appSettings: Boolean) {
        scope.launch {
            mutex.withLock {
                if (!begin("Restoring…")) return@withLock
                runCatching { pull(liveTv = liveTv, appSettings = appSettings) }
                    .onSuccess { message -> _state.value = _state.value.copy(busy = false, message = message) }
                    .onFailure(::fail)
            }
        }
    }

    private fun begin(message: String): Boolean {
        if (tokenStore.load() == null) {
            _state.value = _state.value.copy(message = "Connect Google Drive first.")
            return false
        }
        _state.value = _state.value.copy(busy = true, message = message)
        return true
    }

    private fun fail(error: Throwable) {
        val message = if (error is DriveAuthException) {
            _state.value = _state.value.copy(needsReauth = true)
            "Google sign-in expired. Connect Google Drive again."
        } else {
            "Google Drive backup failed: ${error.message ?: error::class.simpleName}"
        }
        _state.value = _state.value.copy(busy = false, message = message)
    }

    // ------------------------------------------------------------------ upload

    private suspend fun push(): String {
        val token = tokenStore.load() ?: throw IllegalStateException("Not connected")
        accessToken() ?: throw DriveAuthException("no token")
        val settings = iptv.current()

        val liveFile = api.findFile(LegacyIptvBackup.FILE_NAME)
        val existingData = liveFile?.let { readData(it.id) }
        val data = LegacyIptvBackup.dataForBackup(settings, existingData)
        if (!LegacyIptvBackup.hasSources(data)) {
            throw IllegalStateException(
                "Nothing to back up yet: add a Live TV playlist first. The Google Drive copy was left as it was.",
            )
        }
        val envelope = LegacyIptvBackup.build(
            savedAt = System.currentTimeMillis(),
            deviceId = deviceId(),
            device = "Nuvio Desktop (${System.getProperty("os.name") ?: "Linux"})",
            data = data,
        )
        val liveId = if (liveFile != null) {
            api.update(liveFile.id, "application/json; charset=UTF-8", envelope.toByteArray())
            liveFile.id
        } else {
            api.uploadNew(LegacyIptvBackup.FILE_NAME, "application/json; charset=UTF-8", envelope.toByteArray())
        }

        val archive = buildSettingsBackupArchive(includeCredentials = false)
        val companionFile = api.findFile(COMPANION_FILE_NAME)
        val companionId = if (companionFile != null) {
            api.update(companionFile.id, "application/zip", archive)
            companionFile.id
        } else {
            api.uploadNew(COMPANION_FILE_NAME, "application/zip", archive)
        }

        val now = System.currentTimeMillis()
        tokenStore.save(
            token.copy(lastBackupMs = now, localHashAtBackup = localIptvHash(), fileIds = "$liveId,$companionId"),
        )
        _state.value = _state.value.copy(
            lastBackupMs = now, remoteExists = true, remoteSavedAtMs = now,
            remoteOlderThanLocal = false, connected = true,
        )
        return "Backed up to Google Drive."
    }

    private suspend fun readData(fileId: String): JsonObject? = runCatching {
        LegacyIptvBackup.parse(api.download(fileId).decodeToString()).data
    }.getOrNull()

    private suspend fun readSavedAt(fileId: String): Long = runCatching {
        LegacyIptvBackup.parse(api.download(fileId).decodeToString()).savedAt
    }.getOrDefault(0L)

    // ------------------------------------------------------------------ restore

    private suspend fun pull(liveTv: Boolean, appSettings: Boolean): String {
        accessToken() ?: throw DriveAuthException("no token")
        if (!liveTv && !appSettings) return "Choose what to restore."

        if (liveTv) {
            val file = api.findFile(LegacyIptvBackup.FILE_NAME)
                ?: error("No Live TV backup found in Google Drive.")
            val backup = LegacyIptvBackup.parse(api.download(file.id).decodeToString())
            if (!LegacyIptvBackup.hasSources(backup.data)) {
                error("The Live TV backup has no playlists; refusing to empty your setup.")
            }
            iptv.replace(LegacyIptvBackup.apply(backup.data, iptv.current()))
        }

        if (appSettings) {
            val file = api.findFile(COMPANION_FILE_NAME)
                ?: error("No app settings backup found in Google Drive.")
            val bytes = api.download(file.id)
            require(bytes.size > 32) { "The app settings backup is empty." }
            require(isSettingsArchive(bytes)) { "The Google Drive file is not a Nuvio settings backup." }
            stageSettingsBackupArchive(bytes)
        }

        val token = tokenStore.load()
        if (token != null) tokenStore.save(token.copy(localHashAtBackup = localIptvHash()))
        _state.value = _state.value.copy(remoteOlderThanLocal = false)
        return when {
            appSettings && liveTv ->
                "Restored Live TV and app settings. Restart Nuvio to apply the app settings."
            appSettings -> "App settings restored. Restart Nuvio to apply them."
            else -> "Live TV settings restored."
        }
    }

    private fun isSettingsArchive(bytes: ByteArray): Boolean = runCatching {
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            var found = false
            var count = 0
            while (count++ < 10_000) {
                val entry = zip.nextEntry ?: break
                if (entry.name.startsWith("preferences/")) found = true
            }
            found
        }
    }.getOrDefault(false)

    // ------------------------------------------------------------------ OAuth (PKCE, loopback)

    private suspend fun signIn(): DriveToken {
        val verifier = randomVerifier()
        val challenge = base64Url(shaBytes(verifier))
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val port = server.localPort
        val redirect = "http://127.0.0.1:$port"
        val state = UUID.randomUUID().toString()
        val authUrl = "https://accounts.google.com/o/oauth2/v2/auth?" + listOf(
            "client_id" to config.clientId,
            "redirect_uri" to redirect,
            "response_type" to "code",
            "scope" to config.scope,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "access_type" to "offline",
            "prompt" to "consent",
            "state" to state,
        ).joinToString("&") { URLEncoder.encode(it.first, "UTF-8") + "=" + URLEncoder.encode(it.second, "UTF-8") }
        _state.value = _state.value.copy(signInUrl = authUrl)
        runCatching { Desktop.getDesktop().browse(URI(authUrl)) }
            .recoverCatching { ProcessBuilder("xdg-open", authUrl).start() }

        val code = withContext(Dispatchers.IO) {
            server.use {
                it.soTimeout = 180_000
                val socket = it.accept()
                socket.use { s ->
                    val reader = s.getInputStream().bufferedReader()
                    val requestLine = reader.readLine().orEmpty()
                    val params = requestLine.substringAfter("?", "").substringBefore(" ")
                        .split("&").mapNotNull { pair ->
                            val i = pair.indexOf('=')
                            if (i <= 0) null else pair.substring(0, i) to
                                java.net.URLDecoder.decode(pair.substring(i + 1), "UTF-8")
                        }.toMap()
                    val ok = params["code"] != null
                    val body = "<html><body style='font-family:sans-serif'><h2>Nuvio</h2>" +
                        if (ok) "<p>Google Drive connected. You can close this tab.</p>"
                        else "<p>Sign-in was cancelled. You can close this tab.</p>" +
                            "</body></html>"
                    s.getOutputStream().write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\n" +
                            "Content-Length: ${body.toByteArray().size}\r\nConnection: close\r\n\r\n$body").toByteArray(),
                    )
                    s.getOutputStream().flush()
                    if (params["state"] != state) throw IllegalStateException("Sign-in state did not match.")
                    params["code"] ?: throw IllegalStateException("Google sign-in was cancelled.")
                }
            }
        }

        val fields = linkedMapOf(
            "client_id" to config.clientId,
            "code" to code,
            "code_verifier" to verifier,
            "grant_type" to "authorization_code",
            "redirect_uri" to redirect,
        )
        if (config.clientSecret.isNotBlank()) fields["client_secret"] = config.clientSecret
        val body = postForm("https://oauth2.googleapis.com/token", fields)
        val refresh = body["refresh_token"]?.toString()?.trim('"')?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(
                "Google did not return a refresh token. Remove the app's access at myaccount.google.com and retry.",
            )
        return DriveToken(refreshToken = refresh, email = emailFromIdToken(body["id_token"]?.toString()?.trim('"')))
    }

    // ------------------------------------------------------------------ helpers

    private fun localIptvHash(): String = sha256Hex(iptv.current().toString())

    private fun deviceId(): String {
        val file = DesktopStorage.rootDir.resolve("google_drive_device_id")
        return runCatching {
            if (file.exists()) Files.readString(file).trim().takeIf { it.isNotBlank() } else null
        }.getOrNull() ?: UUID.randomUUID().toString().also {
            runCatching {
                Files.createDirectories(file.parent)
                Files.writeString(file, it)
            }
        }
    }

    private fun randomVerifier(): String {
        val bytes = ByteArray(48)
        SecureRandom().nextBytes(bytes)
        return base64Url(bytes)
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun shaBytes(value: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())

    private fun sha256Hex(value: String): String =
        shaBytes(value).joinToString("") { "%02x".format(it) }

    private fun emailFromIdToken(idToken: String?): String? = runCatching {
        if (idToken.isNullOrBlank()) return null
        val payload = idToken.split('.')[1]
        val decoded = Base64.getUrlDecoder().decode(payload)
        Json.parseToJsonElement(decoded.decodeToString()).jsonObject["email"]?.toString()?.trim('"')
    }.getOrNull()

    companion object {
        const val COMPANION_FILE_NAME = "Nuvio Desktop Settings backup v1.zip"
    }
}

internal actual object GoogleDriveBackup {
    private val controller by lazy {
        GoogleDriveBackupController(GoogleDriveConfig.fromEnvironment(), LinuxDriveTokenStore())
    }

    actual val state: StateFlow<GoogleDriveBackupState> get() = controller.state
    actual fun connect() = controller.connect()
    actual fun cancelSignIn() = controller.cancelSignIn()
    actual fun disconnect() = controller.disconnect()
    actual fun backupNow() = controller.backupNow()
    actual fun restoreNow(liveTv: Boolean, appSettings: Boolean) = controller.restoreNow(liveTv, appSettings)
    actual fun refreshStatus() = controller.refreshStatus()
}
