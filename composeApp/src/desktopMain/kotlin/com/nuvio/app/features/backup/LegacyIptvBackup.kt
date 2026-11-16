package com.nuvio.app.features.backup

import com.nuvio.app.features.iptv.IptvPlaylist
import com.nuvio.app.features.iptv.IptvPlaylistType
import com.nuvio.app.features.iptv.IptvProxy
import com.nuvio.app.features.iptv.IptvSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The version-1 Live TV backup NuvioPlusIPTV reads and writes:
 *
 *     { "format": 1, "savedAt": <ms>, "deviceId": "<uuid>", "device": "<name>",
 *       "data": { "<key>": { "t": "b|i|l|s|str", "v": <typed value> } } }
 *
 * `data` is the Android app's DataStore, one entry per preference key. Playlists and guides are a
 * JSON *string* under `playlists` / `epg_sources`, kept byte-for-byte: nothing here re-encodes a
 * list it does not own. Unknown keys are copied through untouched, so a backup made by a newer
 * NuvioPlusIPTV build survives a round trip through this app.
 */
internal object LegacyIptvBackup {
    /** The canonical filename from NuvioPlusIPTV's LiveTvDriveSync. */
    const val FILE_NAME = "Nuvio Live TV backup.json"

    /** Desktop-only, lossless copy of this app's IptvSettings (unknown keys still preserved). */
    const val DESKTOP_SETTINGS_KEY = "nuvio_desktop_iptv_settings"

    const val KEY_PLAYLISTS = "playlists"
    const val KEY_EPGS = "epg_sources"
    const val KEY_FAVORITES = "favorites"
    const val KEY_RECENT = "recent"
    const val KEY_REFRESH_HOURS = "playlist_refresh_hours"
    const val KEY_SHOW_IN_SIDEBAR = "show_in_sidebar"

    /** Keys this app owns; a backup rewrites these and keeps every other entry as it found it. */
    private val managed = setOf(
        DESKTOP_SETTINGS_KEY, KEY_PLAYLISTS, KEY_FAVORITES, KEY_RECENT,
        KEY_REFRESH_HOURS, KEY_SHOW_IN_SIDEBAR,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun parse(text: String): LegacyBackupData {
        val root = json.parseToJsonElement(text).jsonObject
        val data = root["data"]?.jsonObject ?: throw IllegalArgumentException("Not a Live TV backup: no \"data\" object.")
        return LegacyBackupData(
            format = root["format"]?.jsonPrimitive?.intOrNull ?: 1,
            savedAt = root["savedAt"]?.jsonPrimitive?.longOrNull ?: 0L,
            deviceId = root["deviceId"]?.jsonPrimitive?.content ?: "",
            device = root["device"]?.jsonPrimitive?.content ?: "",
            data = data,
        )
    }

    fun build(savedAt: Long, deviceId: String, device: String, data: JsonObject): String = buildJsonObject {
        put("format", 1)
        put("savedAt", savedAt)
        put("deviceId", deviceId)
        put("device", device)
        put("data", data)
    }.toString()

    /** True when a parsed backup carries at least one playlist/guide, so restore never empties a setup. */
    fun hasSources(data: JsonObject): Boolean = runCatching {
        val playlists = data[DESKTOP_SETTINGS_KEY]?.entryString()
            ?.let { json.parseToJsonElement(it).jsonObject[KEY_PLAYLISTS]?.let { p -> listSize(p) } }
            ?: data[KEY_PLAYLISTS]?.entryString()?.let { listSize(json.parseToJsonElement(it)) }
        (playlists ?: 0) > 0
    }.getOrDefault(false)

    private fun listSize(element: JsonElement): Int = when (element) {
        is JsonArray -> element.size
        else -> 0
    }

    /**
     * The `data` map to upload: the remote one (so unknown keys survive) with this app's settings
     * laid over it. Sources are only overwritten when this app actually has some — a fresh install
     * must not replace a real backup with an empty one.
     */
    fun dataForBackup(settings: IptvSettings, existing: JsonObject?): JsonObject {
        val out = LinkedHashMap<String, JsonElement>()
        existing?.forEach { (k, v) -> out[k] = v }
        out[DESKTOP_SETTINGS_KEY] = strEntry(json.encodeToString(IptvSettings.serializer(), settings))
        if (settings.playlists.isNotEmpty()) {
            out[KEY_PLAYLISTS] = strEntry(playlistsJson(settings.playlists))
        }
        if (settings.favourites.isNotEmpty() || existing?.get(KEY_FAVORITES) == null) {
            out[KEY_FAVORITES] = strEntry(stringListJson(settings.favourites))
        }
        if (settings.recents.isNotEmpty() || existing?.get(KEY_RECENT) == null) {
            out[KEY_RECENT] = strEntry(stringListJson(settings.recents))
        }
        out[KEY_REFRESH_HOURS] = intEntry(settings.refreshHours)
        out[KEY_SHOW_IN_SIDEBAR] = boolEntry(settings.tabEnabled ?: true)
        return JsonObject(out)
    }

    /** Applies a restored backup's Live TV entries to the current settings. */
    fun apply(data: JsonObject, current: IptvSettings): IptvSettings {
        // Our own lossless entry wins when present.
        data[DESKTOP_SETTINGS_KEY]?.entryString()?.let { raw ->
            runCatching { json.decodeFromString(IptvSettings.serializer(), raw) }
                .onSuccess { return it }
        }
        var next = current
        data[KEY_PLAYLISTS]?.entryString()?.let { raw ->
            runCatching { playlistsFromJson(raw) }.onSuccess { next = next.copy(playlists = it) }
        }
        data[KEY_FAVORITES]?.entryString()?.let { raw ->
            runCatching { stringListFromJson(raw) }.onSuccess { next = next.copy(favourites = it) }
        }
        data[KEY_RECENT]?.entryString()?.let { raw ->
            runCatching { stringListFromJson(raw) }.onSuccess { next = next.copy(recents = it) }
        }
        data[KEY_REFRESH_HOURS]?.entryValue()?.jsonPrimitive?.intOrNull?.let {
            next = next.copy(refreshHours = it)
        }
        data[KEY_SHOW_IN_SIDEBAR]?.entryValue()?.jsonPrimitive?.booleanOrNull?.let {
            next = next.copy(tabEnabled = it)
        }
        return next
    }

    // ------------------------------------------------------------------ typed entries

    fun boolEntry(value: Boolean) = buildJsonObject { put("t", "b"); put("v", value) }
    fun intEntry(value: Int) = buildJsonObject { put("t", "i"); put("v", value) }
    fun longEntry(value: Long) = buildJsonObject { put("t", "l"); put("v", value) }
    fun strEntry(value: String) = buildJsonObject { put("t", "str"); put("v", value) }
    fun setEntry(value: Set<String>) = buildJsonObject { put("t", "s"); put("v", buildJsonArray { value.forEach { add(JsonPrimitive(it)) } }) }

    /** The typed value of an entry, or null when the entry is not one of the known shapes. */
    private fun JsonElement.entryValue(): JsonElement? = runCatching { jsonObject["v"] }.getOrNull()
    private fun JsonElement.entryString(): String? =
        runCatching { jsonObject["v"]?.jsonPrimitive?.content }.getOrNull()

    // ------------------------------------------------------------------ compatible playlist shape

    /** NuvioPlusIPTV's `PlaylistSource` JSON shape, so its importer understands what we wrote. */
    fun playlistsJson(playlists: List<IptvPlaylist>): String = buildJsonArray {
        playlists.forEach { p ->
            add(
                buildJsonObject {
                    put("id", p.id)
                    put("name", p.name)
                    put("url", p.url.orEmpty())
                    put("enabled", true)
                    put("userAgent", "")
                    put("useEmbeddedEpg", true)
                    put("xtreamServer", p.server.orEmpty())
                    put("xtreamUsername", p.username.orEmpty())
                    put("xtreamPassword", p.password.orEmpty())
                    put("lastUpdatedMs", 0L)
                    put("channelCount", 0)
                    put("importVod", true)
                    put("importLive", true)
                    put("streamFormat", "auto")
                    put("serverTimezone", "")
                    put("logoSource", "playlist")
                    // Kept for this app's own restore: NuvioPlusIPTV ignores unknown fields.
                    put("nuvioType", p.type.name)
                    p.epgUrl?.let { put("nuvioEpgUrl", it) }
                    p.serial?.let { put("nuvioSerial", it) }
                    p.deviceId?.let { put("nuvioDeviceId", it) }
                    p.deviceId2?.let { put("nuvioDeviceId2", it) }
                    p.signature?.let { put("nuvioSignature", it) }
                },
            )
        }
    }.toString()

    fun playlistsFromJson(raw: String): List<IptvPlaylist> = json.parseToJsonElement(raw).jsonArray.map { el ->
        val o = el.jsonObject
        fun s(key: String) = o[key]?.jsonPrimitive?.content?.takeIf { it.isNotEmpty() }
        val type = s("nuvioType")?.let { runCatching { IptvPlaylistType.valueOf(it) }.getOrNull() }
            ?: if (s("xtreamServer") != null) IptvPlaylistType.Xtream else IptvPlaylistType.M3u
        IptvPlaylist(
            id = s("id") ?: throw IllegalArgumentException("Playlist without an id"),
            type = type,
            name = s("name") ?: "Playlist",
            server = s("xtreamServer"),
            username = s("xtreamUsername"),
            password = s("xtreamPassword"),
            url = s("url"),
            epgUrl = s("nuvioEpgUrl"),
            serial = s("nuvioSerial"),
            deviceId = s("nuvioDeviceId"),
            deviceId2 = s("nuvioDeviceId2"),
            signature = s("nuvioSignature"),
        )
    }

    private fun stringListJson(values: List<String>): String =
        buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }.toString()

    private fun stringListFromJson(raw: String): List<String> =
        json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonPrimitive.contentOrNullSafe() }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { if (this is JsonNull) null else content }.getOrNull()
}

internal data class LegacyBackupData(
    val format: Int,
    val savedAt: Long,
    val deviceId: String,
    val device: String,
    val data: JsonObject,
)
