package com.nuvio.app.features.collection

import com.nuvio.app.testing.runHeadlessProbe
import com.nuvio.app.testing.withHeadlessFixture
import kotlinx.serialization.json.*
import kotlin.test.*

class LinuxCollectionRepositoryTest {
    private fun probe(case: String) = withHeadlessFixture { runHeadlessProbe(it, LinuxCollectionRepositoryProbe::class.java, case) }
    @Test fun emptyStorageHasNoCollections() = probe("empty")
    @Test fun createEditAndRemoveCollection() = probe("metadata")
    @Test fun foldersAndCatalogSourcesCanBeAddedReorderedAndRemoved() = probe("folders")
    @Test fun collectionOrderAndInvalidMoves() = probe("order")
    @Test fun collectionsSurviveFreshProcessReload() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxCollectionRepositoryProbe::class.java, "write")
        runHeadlessProbe(it, LinuxCollectionRepositoryProbe::class.java, "read")
    }
    @Test fun exportImportRoundTrip() = probe("roundTrip")
    @Test fun unknownFieldsFollowIdentitiesThroughEditsAndReordering() = probe("unknown")
    @Test fun legacyCatalogSourcesRemainCompatible() = probe("legacy")
    @Test fun corruptPersistedInputCanRecover() = probe("corrupt")
    @Test fun invalidImportDoesNotReplaceCollections() = probe("invalidImport")
    @Test fun failedImportPreservesUnknownFieldsOfExistingCollections() = probe("failedImportUnknown")
    @Test fun repeatedRejectedImportsLeaveModelsExportAndStorageUnchanged() = probe("repeatedRejected")
    @Test fun saveAfterRejectedImportPreservesUnknownFieldsAcrossRestart() = withHeadlessFixture {
        runHeadlessProbe(it, LinuxCollectionRepositoryProbe::class.java, "saveAfterRejected")
        runHeadlessProbe(it, LinuxCollectionRepositoryProbe::class.java, "readAfterRejected")
    }
    @Test fun validImportAfterRejectionReplacesModelsAndPreservationState() = probe("replaceAfterRejected")
    @Test fun successfulImportKeepsMobileDecorationAndReturnSemantics() = probe("decoratedImport")
    @Test fun validationReportsMalformedAndInvalidSources() = probe("validation")
}

internal object LinuxCollectionRepositoryProbe {
    private val preservedPayload = """[{"id":"a","title":"Original","oldExtension":{"value":"keep"},"folders":[
        {"id":"f","title":"Folder","folderExtension":[1,2],"sources":[
            {"addonId":"fixture","type":"movie","catalogId":"one","sourceExtension":true}]}]}]"""

    private fun assertPreservedExtensions(payload: String) {
        val collection = Json.parseToJsonElement(payload).jsonArray.single().jsonObject
        assertEquals(Json.parseToJsonElement("""{"value":"keep"}"""), collection["oldExtension"])
        val folder = collection["folders"]!!.jsonArray.single().jsonObject
        assertEquals(Json.parseToJsonElement("[1,2]"), folder["folderExtension"])
        assertEquals(JsonPrimitive(true), folder["sources"]!!.jsonArray.single().jsonObject["sourceExtension"])
    }

    private fun collection(id: String) = Collection(id, "Collection $id", folders = listOf(
        CollectionFolder("folder-$id", "Folder $id", coverImageUrl = "https://example.invalid/cover.jpg",
            sources = listOf(CollectionSource(addonId = "fixture", type = "movie", catalogId = id)),
            // Exports intentionally include the legacy catalog representation for compatibility.
            catalogSources = listOf(CollectionCatalogSource(addonId = "fixture", type = "movie", catalogId = id))),
    ))

    @JvmStatic fun main(args: Array<String>) {
        val scenario = args[1]
        if (scenario == "corrupt") CollectionStorage.savePayload("{broken")
        CollectionRepository.initialize()
        fun current() = assertNotNull(CollectionRepository.getCollection("a"))
        when (scenario) {
            "empty" -> assertTrue(CollectionRepository.collections.value.isEmpty())
            "metadata" -> {
                CollectionRepository.addCollection(collection("a"))
                CollectionRepository.updateCollection(current().copy(title = "Edited", pinToTop = true,
                    backdropImageUrl = "https://example.invalid/backdrop.jpg", viewMode = "ROWS"))
                assertEquals("Edited", current().title)
                assertTrue(current().pinToTop)
                assertEquals(FolderViewMode.ROWS, current().folderViewMode)
                assertEquals("https://example.invalid/backdrop.jpg", current().backdropImageUrl)
                CollectionRepository.removeCollection("a")
                assertNull(CollectionRepository.getCollection("a"))
            }
            "folders" -> {
                CollectionRepository.addCollection(collection("a"))
                val original = current().folders.single()
                val second = CollectionFolder("second", "Second", sources = listOf(
                    CollectionSource(addonId = "fixture", type = "series", catalogId = "one"),
                    CollectionSource(provider = "tmdb", tmdbSourceType = "LIST", tmdbId = 123),
                ))
                CollectionRepository.updateCollection(current().copy(folders = listOf(second, original)))
                assertEquals(listOf("second", "folder-a"), current().folders.map { it.id })
                CollectionRepository.updateCollection(current().copy(folders = listOf(second.copy(
                    title = "Renamed", sources = second.sources.reversed()))))
                assertEquals("Renamed", current().folders.single().title)
                assertEquals(listOf("tmdb", "addon"), current().folders.single().resolvedSources.map { it.provider })
                CollectionRepository.updateCollection(current().copy(folders = listOf(second.copy(sources = second.sources.take(1)))))
                assertEquals(1, current().folders.single().resolvedSources.size)
            }
            "order", "write" -> {
                CollectionRepository.setCollections(listOf(collection("a"), collection("b"), collection("c")))
                CollectionRepository.moveUp(2)
                CollectionRepository.moveDown(0)
                assertEquals(listOf("c", "a", "b"), CollectionRepository.collections.value.map { it.id })
                CollectionRepository.moveByIndex(-1, 0)
                CollectionRepository.moveByIndex(0, 30)
                CollectionRepository.moveByIndex(0, 0)
                assertEquals(listOf("c", "a", "b"), CollectionRepository.collections.value.map { it.id })
            }
            "read" -> {
                assertEquals(listOf("c", "a", "b"), CollectionRepository.collections.value.map { it.id })
                assertEquals(collection("a"), current())
            }
            "roundTrip" -> {
                CollectionRepository.setCollections(listOf(collection("a"), collection("b")))
                val before = CollectionRepository.collections.value
                val exported = CollectionRepository.exportToJson()
                CollectionRepository.setCollections(emptyList())
                assertTrue(CollectionRepository.importFromJson(exported).isSuccess)
                assertEquals(before, CollectionRepository.collections.value)
                assertEquals(Json.parseToJsonElement(exported), Json.parseToJsonElement(CollectionRepository.exportToJson()))
            }
            "unknown" -> {
                val payload = """[{"id":"a","title":"A","futureCollection":{"enabled":true},"folders":[
                    {"id":"first","title":"First","futureFolder":17,"sources":[
                        {"addonId":"fixture","type":"movie","catalogId":"one","futureSource":"keep"}]},
                    {"id":"second","title":"Second","futureFolder":29}]}]"""
                assertTrue(CollectionRepository.importFromJson(payload).isSuccess)
                val folders = current().folders
                CollectionRepository.updateCollection(current().copy(title = "Edited", folders = listOf(folders[1], folders[0])))
                val exported = Json.parseToJsonElement(CollectionRepository.exportToJson()).jsonArray.single().jsonObject
                assertEquals(true, exported["futureCollection"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean)
                val savedFolders = exported["folders"]!!.jsonArray.map { it.jsonObject }
                assertEquals(listOf(29, 17), savedFolders.map { it["futureFolder"]!!.jsonPrimitive.int })
                assertEquals("keep", savedFolders[1]["sources"]!!.jsonArray.single().jsonObject["futureSource"]!!.jsonPrimitive.content)
                CollectionRepository.clearLocalState()
                CollectionRepository.initialize()
                assertEquals(exported, Json.parseToJsonElement(CollectionRepository.exportToJson()).jsonArray.single())
            }
            "legacy" -> {
                CollectionStorage.savePayload("""[{"id":"a","title":"Old","folders":[{"id":"old","title":"Old folder",
                    "catalogSources":[{"addonId":"fixture","type":"movie","catalogId":"legacy","genre":" none ","futureLegacy":42}]}]}]""")
                CollectionRepository.onProfileChanged()
                CollectionRepository.initialize()
                val source = current().folders.single().resolvedSources.single()
                assertEquals("legacy", source.catalogId)
                assertNull(source.genre)
                CollectionRepository.updateCollection(current().copy(title = "New"))
                val folder = Json.parseToJsonElement(CollectionRepository.exportToJson()).jsonArray.single().jsonObject["folders"]!!.jsonArray.single().jsonObject
                assertEquals(42, folder["catalogSources"]!!.jsonArray.single().jsonObject["futureLegacy"]!!.jsonPrimitive.int)
                assertEquals("addon", folder["sources"]!!.jsonArray.single().jsonObject["provider"]!!.jsonPrimitive.content)
            }
            "corrupt" -> {
                assertTrue(CollectionRepository.collections.value.isEmpty())
                CollectionRepository.addCollection(collection("a"))
                CollectionRepository.clearLocalState()
                CollectionRepository.initialize()
                assertEquals(collection("a"), current())
            }
            "invalidImport" -> {
                CollectionRepository.addCollection(collection("a"))
                val before = CollectionRepository.collections.value
                assertTrue(CollectionRepository.importFromJson("{broken").isFailure)
                assertTrue(CollectionRepository.importFromJson("[{\"title\":\"Missing id\"}]").isFailure)
                assertEquals(before, CollectionRepository.collections.value)
            }
            "failedImportUnknown" -> {
                assertTrue(CollectionRepository.importFromJson("""[{"id":"a","title":"A","futureCollection":"keep"}]""").isSuccess)
                assertTrue(CollectionRepository.importFromJson("""[{"title":"Missing id"}]""").isFailure)
                assertEquals("A", current().title)
                // A failed import must not replace the raw JSON used by later exports/edits.
                val exported = Json.parseToJsonElement(CollectionRepository.exportToJson()).jsonArray.single().jsonObject
                assertEquals(JsonPrimitive("keep"), exported["futureCollection"])
            }
            "repeatedRejected" -> {
                assertTrue(CollectionRepository.importFromJson(preservedPayload).isSuccess)
                val beforeModels = CollectionRepository.collections.value
                val beforeExport = Json.parseToJsonElement(CollectionRepository.exportToJson())
                val beforeStorage = CollectionStorage.loadPayload()
                val rejected = listOf("{broken", "null", """[{"title":"Missing id"}]""",
                    """[{"id":"a","title":"Replacement"},{"id":"b","title":"Bad folders","folders":42}]""")
                repeat(2) {
                    rejected.forEach { candidate ->
                        assertTrue(CollectionRepository.importFromJson(candidate).isFailure)
                        assertSame(beforeModels, CollectionRepository.collections.value)
                        assertEquals(beforeStorage, CollectionStorage.loadPayload())
                        assertEquals(beforeExport, Json.parseToJsonElement(CollectionRepository.exportToJson()))
                    }
                }
            }
            "saveAfterRejected", "readAfterRejected" -> {
                if (scenario == "saveAfterRejected") {
                    assertTrue(CollectionRepository.importFromJson(preservedPayload).isSuccess)
                    assertTrue(CollectionRepository.importFromJson("""[{"title":"Missing id"}]""").isFailure)
                    // Save directly, before any export can refresh preservation state.
                    CollectionRepository.updateCollection(current().copy(title = "Edited after rejection"))
                }
                assertEquals("Edited after rejection", current().title)
                assertPreservedExtensions(assertNotNull(CollectionStorage.loadPayload()))
                assertPreservedExtensions(CollectionRepository.exportToJson())
            }
            "replaceAfterRejected" -> {
                assertTrue(CollectionRepository.importFromJson(preservedPayload).isSuccess)
                assertTrue(CollectionRepository.importFromJson("""[{"title":"Missing id"}]""").isFailure)
                val replacement = """[{"id":"a","title":"Replacement","newExtension":{"nested":[null,"new"]},
                    "folders":[{"id":"f","title":"New folder","newFolderExtension":7}]}]"""
                val imported = CollectionRepository.importFromJson(replacement).getOrThrow()
                assertEquals(imported, CollectionRepository.collections.value)
                assertEquals("Replacement", current().title)
                fun assertReplacement(payload: String) {
                    val exported = Json.parseToJsonElement(payload).jsonArray.single().jsonObject
                    assertEquals(Json.parseToJsonElement("""{"nested":[null,"new"]}"""), exported["newExtension"])
                    assertNull(exported["oldExtension"])
                    val folder = exported["folders"]!!.jsonArray.single().jsonObject
                    assertEquals(JsonPrimitive(7), folder["newFolderExtension"])
                    assertNull(folder["folderExtension"])
                    assertTrue(folder["sources"]!!.jsonArray.isEmpty())
                }
                assertReplacement(CollectionRepository.exportToJson())
                CollectionRepository.updateCollection(current().copy(title = "Saved replacement"))
                assertReplacement(assertNotNull(CollectionStorage.loadPayload()))
                CollectionRepository.clearLocalState()
                CollectionRepository.initialize()
                assertEquals("Saved replacement", current().title)
                assertReplacement(CollectionRepository.exportToJson())
            }
            "decoratedImport" -> {
                CollectionMobileSettingsRepository.replaceCollectionFolderGifSettings("a",
                    listOf(CollectionFolder("f", "Folder", mobileFocusGifEnabled = false)))
                val imported = CollectionRepository.importFromJson(preservedPayload).getOrThrow()
                assertTrue(imported.single().folders.single().mobileFocusGifEnabled)
                assertFalse(current().folders.single().mobileFocusGifEnabled)
                assertPreservedExtensions(CollectionRepository.exportToJson())
                assertPreservedExtensions(assertNotNull(CollectionStorage.loadPayload()))
            }
            "validation" -> {
                CollectionRepository.addCollection(collection("a"))
                val good = CollectionRepository.validateJson(CollectionRepository.exportToJson())
                assertTrue(good.valid)
                assertEquals(1, good.collectionCount)
                assertEquals(1, good.folderCount)
                listOf("", "{broken", """[{"id":"","title":"A"}]""",
                    """[{"id":"a","title":"A","folders":[{"id":"f","title":"F","sources":[{"provider":"trakt","traktListId":0}]}]}]""")
                    .forEach { assertFalse(CollectionRepository.validateJson(it).valid) }
            }
            else -> error(scenario)
        }
    }
}
