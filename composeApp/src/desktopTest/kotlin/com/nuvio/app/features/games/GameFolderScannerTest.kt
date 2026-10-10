package com.nuvio.app.features.games

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameFolderScannerTest {
    private fun tempRoot(name: String): File = Files.createTempDirectory("nuvio-scan-$name").toFile()

    private fun File.exe(relative: String, bytes: Int = 1): File {
        val file = File(this, relative)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(bytes))
        return file
    }

    @Test
    fun oneCandidatePerSubFolderNamedAfterTheFolder() {
        val root = tempRoot("plain")
        root.exe("Hades/Hades.exe")
        root.exe("Hollow_Knight/hollow_knight.exe")
        File(root, "Empty Folder").mkdirs()

        val result = GameFolderScanner.scan(root, existing = emptyList(), linux = false)

        assertEquals(listOf("Hades", "Hollow Knight"), result.candidates.map { it.title })
        assertEquals(1, result.foldersWithoutExecutable)
        assertEquals(0, result.skippedExisting)
    }

    @Test
    fun helpersLoseToTheExecutableNamedLikeTheFolder() {
        val root = tempRoot("helpers")
        val game = root.exe("Hades/Hades.exe", bytes = 10)
        root.exe("Hades/UnityCrashHandler64.exe", bytes = 500)
        root.exe("Hades/unins000.exe", bytes = 500)
        root.exe("Hades/_CommonRedist/vcredist/vc_redist.x64.exe", bytes = 900)

        val chosen = GameFolderScanner.chooseExecutable(File(root, "Hades"))

        assertEquals(game.absolutePath, chosen?.absolutePath)
    }

    @Test
    fun deeperExecutableWinsWhenItsNameMatchesAndTheShallowOneDoesNot() {
        val root = tempRoot("deep")
        root.exe("ELDEN RING/start_protected_game.exe", bytes = 900)
        val game = root.exe("ELDEN RING/Game/eldenring.exe", bytes = 10)

        val chosen = GameFolderScanner.chooseExecutable(File(root, "ELDEN RING"))

        assertEquals(game.absolutePath, chosen?.absolutePath)
    }

    @Test
    fun shallowerThenLargerBreaksTiesWithNoNameMatch() {
        val root = tempRoot("ties")
        val launcher = root.exe("Some Game/Launcher.exe", bytes = 10)
        root.exe("Some Game/bin/x64/engine.exe", bytes = 900)

        val chosen = GameFolderScanner.chooseExecutable(File(root, "Some Game"))

        assertEquals(launcher.absolutePath, chosen?.absolutePath)
    }

    @Test
    fun folderWithOnlyHelpersHasNoExecutable() {
        val root = tempRoot("only-helpers")
        root.exe("Redist Pack/setup.exe")
        root.exe("Redist Pack/dxsetup.exe")

        assertNull(GameFolderScanner.chooseExecutable(File(root, "Redist Pack")))
    }

    @Test
    fun steamLibraryRootResolvesToCommonAndReadsManifests() {
        val root = tempRoot("steam")
        val common = File(root, "steamapps/common").apply { mkdirs() }
        File(root, "steamapps/appmanifest_620.acf").writeText(
            """
            "AppState"
            {
                "appid"		"620"
                "name"		"Portal 2"
                "installdir"		"Portal 2"
            }
            """.trimIndent(),
        )
        common.exe("Portal 2/portal2.exe")
        common.exe("Unknown Game/game.exe")

        val result = GameFolderScanner.scan(root, existing = emptyList(), linux = false)

        val portal = result.candidates.first { it.folder.name == "Portal 2" }
        assertEquals("Portal 2", portal.title)
        assertEquals(620L, portal.steamAppId)
        assertNull(result.candidates.first { it.folder.name == "Unknown Game" }.steamAppId)
    }

    @Test
    fun steamAppsFolderItselfResolvesToCommon() {
        val root = tempRoot("steamapps")
        val steamApps = File(root, "steamapps").apply { File(this, "common").mkdirs() }

        assertEquals(File(steamApps, "common"), GameFolderScanner.gamesDirectoryFor(steamApps))
        assertEquals(File(steamApps, "common"), GameFolderScanner.gamesDirectoryFor(root))
    }

    @Test
    fun gamesAlreadyInTheLibraryAreSkippedByPathIdOrTitle() {
        val root = tempRoot("existing")
        val byPath = root.exe("Hades/Hades.exe")
        root.exe("Celeste/Celeste.exe")
        root.exe("Portal 2/portal2.exe")
        root.exe("New Game/newgame.exe")
        val existing = listOf(
            GameEntry(id = "1", title = "Something", executablePath = byPath.absolutePath.uppercase()),
            GameEntry(id = "2", title = "CELESTE", executablePath = null),
            GameEntry(id = "3", title = "Other", steamAppId = 620L),
        )
        // Portal 2 matches on app id only when the scan can resolve one; here it is a plain folder,
        // so only path and title matches apply.
        val result = GameFolderScanner.scan(root, existing, linux = false)

        assertEquals(listOf("New Game", "Portal 2"), result.candidates.map { it.title })
        assertEquals(2, result.skippedExisting)
    }

    @Test
    fun aFolderHoldingOneGameDirectlyIsThatGame() {
        val root = tempRoot("single")
        val game = File(root, "Celeste").apply { mkdirs() }
        game.exe("Celeste.exe")

        val result = GameFolderScanner.scan(game, existing = emptyList(), linux = false)

        assertEquals(1, result.candidates.size)
        assertEquals("Celeste", result.candidates.single().title)
        assertEquals(File(game, "Celeste.exe").absolutePath, result.candidates.single().executablePath)
    }

    @Test
    fun helperNamesAreRecognisedInAnySpelling() {
        assertTrue(GameFolderScanner.isHelperExecutable("UnityCrashHandler64.exe"))
        assertTrue(GameFolderScanner.isHelperExecutable("unins000.exe"))
        assertTrue(GameFolderScanner.isHelperExecutable("vc_redist.x64.exe"))
        assertTrue(GameFolderScanner.isHelperExecutable("EasyAntiCheat_Setup.exe"))
        assertTrue(GameFolderScanner.isHelperExecutable("CrashReportClient.exe"))
        assertTrue(GameFolderScanner.isHelperExecutable("Hades_BE.exe"))
        assertTrue(!GameFolderScanner.isHelperExecutable("Hades.exe"))
        assertTrue(!GameFolderScanner.isHelperExecutable("witcher3.exe"))
    }

    @Test
    fun folderTitlesSwapSeparatorsAndKeepCase() {
        assertEquals("Hollow Knight", GameFolderScanner.folderTitle("Hollow_Knight"))
        assertEquals("ELDEN RING", GameFolderScanner.folderTitle("ELDEN RING"))
        assertEquals("Baldurs Gate 3", GameFolderScanner.folderTitle("Baldurs.Gate.3"))
    }

    @Test
    fun manifestNameIsParsedWhenPresent() {
        val manifest = parseSteamAppManifest(
            """
            "AppState"
            {
                "appid"		"1145360"
                "name"		"Hades"
                "installdir"		"Hades"
            }
            """.trimIndent(),
        )

        assertEquals(SteamAppManifest(1145360L, "Hades", "Hades"), manifest)
    }
}
