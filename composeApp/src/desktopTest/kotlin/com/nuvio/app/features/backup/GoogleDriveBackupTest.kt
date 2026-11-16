package com.nuvio.app.features.backup

import com.nuvio.app.features.iptv.IptvPlaylist
import com.nuvio.app.features.iptv.IptvPlaylistType
import com.nuvio.app.features.iptv.IptvSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The version-1 fixture from NuvioPlusIPTV, sanitized: the exact envelope and typed entries its
 * importer reads, plus one key this app does not understand so preservation can be checked.
 */
private const val FIXTURE = """
{
  "format": 1,
  "savedAt": 1700000000000,
  "deviceId": "fixture-device",
  "device": "Fixture TV",
  "data": {
    "playlists": { "t": "str", "v": "[{\"id\":\"p1\",\"name\":\"Demo\",\"url\":\"https://example.invalid/list.m3u\"}]" },
    "epg_sources": { "t": "str", "v": "[]" },
    "favorites": { "t": "str", "v": "[\"p1:ch1\",\"p1:ch2\"]" },
    "recent": { "t": "str", "v": "[\"p1:ch2\"]" },
    "playlist_refresh_hours": { "t": "i", "v": 12 },
    "show_in_sidebar": { "t": "b", "v": true },
    "compact_rows": { "t": "b", "v": false },
    "guide_size": { "t": "str", "v": "LARGE" },
    "locked_groups": { "t": "s", "v": ["news"] },
    "nested_unknown_payload": { "t": "str", "v": "{\"keep\":[1,2,3],\"exact\":\"yes\"}" }
  }
}
"""

class GoogleDriveBackupTest {

    @Test
    fun parsesTheVersionOneFixture() {
        val backup = LegacyIptvBackup.parse(FIXTURE)
        assertEquals(1, backup.format)
        assertEquals(1700000000000L, backup.savedAt)
        assertEquals("fixture-device", backup.deviceId)
        assertEquals("Fixture TV", backup.device)
        assertTrue(backup.data.containsKey("nested_unknown_payload"))
    }

    @Test
    fun roundTripsWithoutDroppingUnknownKeysOrNestedStrings() {
        val parsed = LegacyIptvBackup.parse(FIXTURE)
        val rebuilt = LegacyIptvBackup.parse(
            LegacyIptvBackup.build(parsed.savedAt, parsed.deviceId, parsed.device, parsed.data),
        )
        assertEquals(parsed.data, rebuilt.data)
        // The unknown nested JSON string is byte-for-byte what the fixture had.
        assertEquals(
            "{\"keep\":[1,2,3],\"exact\":\"yes\"}",
            rebuilt.data["nested_unknown_payload"]!!.jsonObject["v"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun backupKeepsUnknownKeysAndWritesTypedEntries() {
        val existing = LegacyIptvBackup.parse(FIXTURE).data
        val settings = IptvSettings(
            playlists = listOf(IptvPlaylist("p9", IptvPlaylistType.M3u, "New", url = "https://example.invalid/new.m3u")),
            favourites = listOf("p9:a"),
            refreshHours = 6,
            tabEnabled = false,
        )
        val data = LegacyIptvBackup.dataForBackup(settings, existing)
        assertTrue(data.containsKey("nested_unknown_payload"), "unknown keys survive a backup")
        assertEquals("i", data["playlist_refresh_hours"]!!.jsonObject["t"].toString().trim('"'))
        assertEquals("6", data["playlist_refresh_hours"]!!.jsonObject["v"].toString().trim('"'))
        assertEquals("b", data["show_in_sidebar"]!!.jsonObject["t"].toString().trim('"'))
        assertTrue(data.containsKey(LegacyIptvBackup.DESKTOP_SETTINGS_KEY))
    }

    @Test
    fun backupWithNoSourcesPreservesTheRemotePlaylists() {
        val existing = LegacyIptvBackup.parse(FIXTURE).data
        val data = LegacyIptvBackup.dataForBackup(IptvSettings(), existing)
        assertEquals(existing["playlists"], data["playlists"], "an empty install must not wipe the saved playlists")
    }

    @Test
    fun restorePrefersTheDesktopEntryAndFallsBackToLegacyKeys() {
        val data = LegacyIptvBackup.parse(FIXTURE).data
        val fromLegacy = LegacyIptvBackup.apply(data, IptvSettings())
        assertEquals(12, fromLegacy.refreshHours)
        assertEquals(true, fromLegacy.tabEnabled)
        assertEquals(listOf("p1:ch1", "p1:ch2"), fromLegacy.favourites)
        assertEquals("Demo", fromLegacy.playlists.single().name)

        val settings = IptvSettings(refreshHours = 48, tabEnabled = false, playlists = listOf(IptvPlaylist("x", IptvPlaylistType.M3u, "Exact")))
        val withDesktop = Json.encodeToString(IptvSettings.serializer(), settings)
        val data2 = kotlinx.serialization.json.buildJsonObject {
            put(LegacyIptvBackup.DESKTOP_SETTINGS_KEY, LegacyIptvBackup.strEntry(withDesktop))
        }
        assertEquals(settings, LegacyIptvBackup.apply(data2, IptvSettings()))
    }

    @Test
    fun rejectsMalformedAndSourceLessBackups() {
        assertFailsWith<IllegalArgumentException> { LegacyIptvBackup.parse("not json") }
        assertFailsWith<IllegalArgumentException> { LegacyIptvBackup.parse("{\"format\":1}") }
        assertFalse(LegacyIptvBackup.hasSources(kotlinx.serialization.json.JsonObject(emptyMap())))
        assertFalse(
            LegacyIptvBackup.hasSources(LegacyIptvBackup.parse("{\"data\":{\"playlists\":{\"t\":\"str\",\"v\":\"[]\"}}}").data),
        )
        assertTrue(LegacyIptvBackup.hasSources(LegacyIptvBackup.parse(FIXTURE).data))
    }

    @Test
    fun controllerBacksUpAndRestoresThroughAMockDrive() = runBlocking {
        val tokenStore = MemoryTokenStore()
        val api = FakeDriveApi()
        val source = FakeIptvSource(
            IptvSettings(
                playlists = listOf(IptvPlaylist("p1", IptvPlaylistType.M3u, "Demo", url = "https://example.invalid/a.m3u")),
                refreshHours = 9,
            ),
        )
        val controller = GoogleDriveBackupController(
            config = GoogleDriveConfig("client", "", "https://www.googleapis.com/auth/drive"),
            tokenStore = tokenStore,
            apiFactory = { api },
            iptv = source,
            testAccessToken = { "test-token" },
        )
        tokenStore.save(DriveToken("refresh", "me@example.com"))

        controller.backupNow()
        awaitIdle(controller)
        assertNotNull(api.files[LegacyIptvBackup.FILE_NAME], "Live TV file uploaded")
        assertNotNull(api.files[GoogleDriveBackupController.COMPANION_FILE_NAME], "companion uploaded")
        assertEquals(2, api.createCount, "one create per file on the first backup")

        // Change local settings, then restore the uploaded backup.
        source.settings = IptvSettings()
        controller.restoreNow(liveTv = true, appSettings = false)
        awaitIdle(controller)
        assertEquals(9, source.settings.refreshHours)
        assertEquals("Demo", source.settings.playlists.single().name)

        controller.backupNow()
        awaitIdle(controller)
        assertEquals(2, api.createCount, "backup updates by file id, never duplicates")
    }

    @Test
    fun controllerRefusesToUploadAnEmptyLiveTvSetup() = runBlocking {
        val tokenStore = MemoryTokenStore()
        val api = FakeDriveApi()
        val controller = GoogleDriveBackupController(
            config = GoogleDriveConfig("client", "", "scope"),
            tokenStore = tokenStore,
            apiFactory = { api },
            iptv = FakeIptvSource(IptvSettings()),
            testAccessToken = { "test-token" },
        )
        tokenStore.save(DriveToken("refresh", null))
        controller.backupNow()
        awaitIdle(controller)
        assertTrue(api.files.isEmpty(), "an empty setup must not replace a valid backup")
        assertTrue(controller.state.value.message.orEmpty().contains("Nothing to back up"))
    }

    private suspend fun awaitIdle(controller: GoogleDriveBackupController) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (!controller.state.value.busy) {
                delay(50)
                if (!controller.state.value.busy) return
            }
            delay(25)
        }
    }

    private class MemoryTokenStore : DriveTokenStore {
        private var token: DriveToken? = null
        override fun load(): DriveToken? = token
        override fun save(token: DriveToken) { this.token = token }
        override fun clear() { token = null }
    }

    private class FakeIptvSource(var settings: IptvSettings) : IptvBackupSource {
        override fun current(): IptvSettings = settings
        override fun replace(settings: IptvSettings) { this.settings = settings }
    }

    private class FakeDriveApi : DriveApi {
        val files = linkedMapOf<String, ByteArray>()
        private val ids = mutableMapOf<String, String>()
        var createCount = 0

        override suspend fun findFile(name: String): DriveFile? =
            ids[name]?.let { DriveFile(it, 0L) }

        override suspend fun uploadNew(name: String, mimeType: String, bytes: ByteArray): String {
            createCount++
            val id = "id-$name-${createCount}"
            ids[name] = id
            files[name] = bytes
            return id
        }

        override suspend fun update(fileId: String, mimeType: String, bytes: ByteArray) {
            val name = ids.entries.firstOrNull { it.value == fileId }?.key
                ?: error("unknown file $fileId")
            files[name] = bytes
        }

        override suspend fun download(fileId: String): ByteArray {
            val name = ids.entries.firstOrNull { it.value == fileId }?.key
                ?: error("unknown file $fileId")
            return files.getValue(name)
        }
    }
}
