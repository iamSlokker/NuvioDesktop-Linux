package com.nuvio.app.features.games

import java.io.File
import java.nio.file.Files
import org.junit.Before
import org.junit.Assume.assumeTrue
import kotlin.test.*

class LinuxGameFolderScannerTest {
    @Before fun linuxFixtureOnly() {
        assumeTrue("Linux filesystem/Unix-socket fixture", System.getProperty("os.name").equals("Linux", true))
    }

    private fun withRoot(test: (File) -> Unit) {
        val root = Files.createTempDirectory("nuvio-linux-import-").toFile()
        try { test(root) } finally { root.deleteRecursively() }
    }
    private fun File.executable(path: String, script: Boolean = false): File = resolve(path).apply {
        parentFile.mkdirs()
        if (script) writeText("#!/bin/sh\nexit 99\n") else writeBytes(byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0))
        assertTrue(setExecutable(true, true))
    }
    private fun scan(root: File, existing: List<GameEntry> = emptyList()) = GameFolderScanner.scan(root, existing)
    private fun desktop(root: File, target: File, exec: String = "\"${target.absolutePath}\" --name \"two words\" %U"): File =
        root.resolve("A Game.desktop").apply { writeText("[Desktop Entry]\nType=Application\nCategories=Game;\nExec=$exec\nPath=${target.parent}\n") }

    @Test fun discoversElfScriptsAndAppImagesWithUnicodeAndSpaces() = withRoot { root ->
        val targets = listOf(root.executable("Hades/Hades"), root.executable("日本 ゲーム/start.sh", true), root.executable("Moon/Moon.AppImage"))
        assertEquals(targets.map { it.absolutePath }.toSet(), scan(root).candidates.map { it.executablePath }.toSet())
    }
    @Test fun ignoresPermissionsFakeExtensionsHelpersAndUnrelatedPrograms() = withRoot { root ->
        root.executable("Closed/Closed").setExecutable(false, false)
        root.resolve("Fake/Fake.AppImage").apply { parentFile.mkdirs(); writeText("not elf"); setExecutable(true) }
        root.executable("Tools/crash-handler")
        root.executable("Tools/tools/start.sh", true)
        root.executable("Other/curl")
        assertTrue(scan(root).candidates.isEmpty())
    }
    @Test fun scriptIsNeverExecuted() = withRoot { root ->
        val marker = root.resolve("executed")
        root.executable("Safe/start.sh", true).writeText("#!/bin/sh\ntouch '${marker.absolutePath}'\n")
        assertEquals(1, scan(root).candidates.size)
        assertFalse(marker.exists())
    }
    @Test fun selectedSingleGameAndBinLauncherWork() = withRoot { root ->
        val game = root.resolve("Single").apply { mkdir() }
        val launcher = game.executable("bin/start.sh", true)
        assertEquals(launcher.absolutePath, scan(game).candidates.single().executablePath)
    }
    @Test fun usesExistingSteamManifestMetadata() = withRoot { root ->
        val common = root.resolve("steamapps/common").apply { mkdirs() }
        common.executable("Portal/portal")
        root.resolve("steamapps/appmanifest_620.acf").writeText("\"AppState\" { \"appid\" \"620\" \"name\" \"Portal Official\" \"installdir\" \"Portal\" }")
        val found = scan(root).candidates.single()
        assertEquals("Portal Official", found.title)
        assertEquals(620L, found.steamAppId)
    }
    private fun File.steamManifest(id: Long, name: String, folder: String, installed: Boolean = true) {
        resolve("steamapps/appmanifest_$id.acf").apply { parentFile.mkdirs() }
            .writeText("\"AppState\" {\n\t\"appid\"\t\t\"$id\"\n\t\"name\"\t\t\"$name\"\n\t\"installdir\"\t\t\"$folder\"\n}\n")
        if (installed) resolve("steamapps/common/$folder").mkdirs()
    }
    @Test fun steamGamesRunThroughSteamWhateverTheirFolderHolds() = withRoot { root ->
        root.steamManifest(620, "Portal 2", "Portal 2")
        root.executable("steamapps/common/Portal 2/portal2.sh", true)
        root.steamManifest(1245620, "ELDEN RING", "ELDEN RING")
        root.resolve("steamapps/common/ELDEN RING/Game/eldenring.exe").apply { parentFile.mkdirs(); writeText("MZ") }
        val found = scan(root).candidates
        assertEquals(listOf("steam://rungameid/1245620", "steam://rungameid/620"), found.map { it.executablePath })
        assertEquals(listOf(1245620L, 620L), found.map { it.steamAppId })
        assertEquals(GameLaunchTarget.Protocol("steam://rungameid/620"), resolveGameLaunchTarget(found[1].executablePath))
    }
    @Test fun protonAndRuntimeToolsAreNotGames() = withRoot { root ->
        root.steamManifest(1493710, "Proton Experimental", "Proton - Experimental")
        root.steamManifest(2805730, "Proton 9.0", "Proton 9.0 (Beta)")
        root.steamManifest(1628350, "Steam Linux Runtime 3.0 (sniper)", "SteamLinuxRuntime_sniper")
        root.steamManifest(228980, "Steamworks Common Redistributables", "Steamworks Shared")
        root.steamManifest(1826330, "Proton EasyAntiCheat Runtime", "Proton EasyAntiCheat Runtime")
        root.steamManifest(400, "Portal", "Portal")
        assertEquals(listOf("Portal"), scan(root).candidates.map { it.title })
    }
    @Test fun aGameNamedLikeAToolIsStillAGame() = withRoot { root ->
        root.steamManifest(1, "Protonaut", "Protonaut")
        assertEquals(listOf("Protonaut"), scan(root).candidates.map { it.title })
    }
    @Test fun steamManifestWithoutAFolderIsNotInstalled() = withRoot { root ->
        root.steamManifest(620, "Portal 2", "Portal 2", installed = false)
        root.steamManifest(400, "Portal", "Portal")
        assertEquals(listOf(400L), scan(root).candidates.map { it.steamAppId })
    }
    @Test fun steamScanFollowsTheOtherLibrariesAndSkipsWhatIsAlreadyThere() = withRoot { root ->
        val main = root.resolve("Steam")
        val second = root.resolve("Second Drive/SteamLibrary")
        main.steamManifest(400, "Portal", "Portal")
        second.steamManifest(620, "Portal 2", "Portal 2")
        main.resolve("steamapps/libraryfolders.vdf").writeText(
            "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"${main.absolutePath}\"\n\t}\n" +
                "\t\"1\"\n\t{\n\t\t\"path\"\t\t\"${second.absolutePath}\"\n\t}\n\t\"2\"\n\t{\n\t\t\"path\"\t\t\"/gone\"\n\t}\n}\n",
        )
        assertEquals(listOf(400L, 620L), scan(main).candidates.map { it.steamAppId })
        val existing = GameEntry("old", title = "Old name", executablePath = "/somewhere/portal", steamAppId = 400)
        val rescan = scan(main, listOf(existing))
        assertEquals(listOf(620L), rescan.candidates.map { it.steamAppId })
        assertEquals(1, rescan.skippedExisting)
    }
    @Test fun oneGameFolderInsideALibraryImportsOnlyThatGame() = withRoot { root ->
        root.steamManifest(400, "Portal", "Portal")
        root.steamManifest(620, "Portal 2", "Portal 2")
        assertEquals(listOf(620L), scan(root.resolve("steamapps/common/Portal 2")).candidates.map { it.steamAppId })
    }
    @Test fun steamFolderWithoutManifestsStaysAnOrdinaryScan() = withRoot { root ->
        val game = root.executable("steamapps/common/Hades/Hades")
        assertEquals(game.absolutePath, scan(root).candidates.single().executablePath)
    }
    @Test fun lutrisUrlsAreProtocolTargets() {
        assertEquals(GameLaunchTarget.Protocol("lutris:rungameid/12"), resolveGameLaunchTarget("lutris:rungameid/12"))
        assertEquals(GameLaunchTarget.Protocol("heroic://launch/gog/1"), resolveGameLaunchTarget("heroic://launch/gog/1"))
    }
    @Test fun duplicateExistingAndSameBatchShortcutAreSuppressed() = withRoot { root ->
        val target = root.executable("A Game/start.sh", true)
        desktop(root, target)
        assertEquals(1, scan(root).candidates.size)
        val existing = GameEntry(id = "existing", title = "Already", executablePath = target.absolutePath)
        assertTrue(scan(root, listOf(existing)).candidates.isEmpty())
    }
    @Test fun existingDesktopEntryDeduplicatesALaterNativeFolderScan() = withRoot { root ->
        val target = root.executable("Native/Native")
        val entry = desktop(root, target)
        val existing = GameEntry("imported", title = "Different shortcut title", executablePath = entry.absolutePath)
        assertTrue(scan(target.parentFile, listOf(existing)).candidates.isEmpty())
    }
    @Test fun safeDesktopTargetUsesArgumentsWithoutShell() = withRoot { root ->
        val target = root.executable("日本 Game/Game.AppImage")
        val entry = desktop(root, target)
        val resolved = resolveGameLaunchTarget(entry.absolutePath) as GameLaunchTarget.Executable
        assertEquals(target.absolutePath, resolved.path)
        assertEquals(listOf("--name", "two words"), resolved.arguments)
        assertEquals(target.parent, resolved.workingDirectory)
    }
    @Test fun desktopFieldCodesHaveExplicitSubset() = withRoot { root ->
        val target = root.executable("Game/Game")
        assertEquals(listOf("100%"), LinuxGameDiscovery.desktopTarget(desktop(root, target, "${target.absolutePath} 100%% %f"))?.arguments)
        for (arg in listOf("%c", "%i", "%k", "%x", "x%f", "%f %U", "\"%u\"")) {
            assertNull(LinuxGameDiscovery.desktopTarget(desktop(root, target, "${target.absolutePath} $arg")), arg)
        }
    }
    @Test fun unsafeOrUnsupportedDesktopEntriesAreRejected() = withRoot { root ->
        val target = root.executable("Game/Game")
        for (exec in listOf("game", "${target.absolutePath};touch bad", "${target.absolutePath} \$(bad)", "${target.absolutePath} \"unterminated")) {
            assertNull(LinuxGameDiscovery.desktopTarget(desktop(root, target, exec)))
        }
        val shell = root.executable("sh")
        assertNull(LinuxGameDiscovery.desktopTarget(desktop(root, shell, "${shell.absolutePath} -c command")))
        for (extra in listOf("Type=Link", "Terminal=true", "Hidden=true", "NoDisplay=true", "DBusActivatable=true", "Categories=Utility;", "TryExec=missing")) {
            val entry = desktop(root, target)
            when {
                extra.startsWith("Type=") -> entry.writeText(entry.readText().replace("Type=Application", extra))
                extra.startsWith("Categories=") -> entry.writeText(entry.readText().replace("Categories=Game;", extra))
                else -> entry.appendText("$extra\n")
            }
            assertNull(LinuxGameDiscovery.desktopTarget(entry), extra)
        }
    }
    @Test fun missingTargetAndMissingRootAreGraceful() = withRoot { root ->
        desktop(root, root.resolve("absent"))
        assertTrue(scan(root).candidates.isEmpty())
        assertTrue(scan(root.resolve("gone")).candidates.isEmpty())
        assertTrue(scan(File("/usr")).candidates.isEmpty())
        assertTrue(scan(File("/")).candidates.isEmpty())
    }
    @Test fun doesNotFollowDirectoryOrExecutableSymlinks() = withRoot { root ->
        val outside = Files.createTempDirectory("nuvio-outside-").toFile()
        try {
            outside.executable("Alias")
            Files.createSymbolicLink(root.resolve("Alias").toPath(), outside.toPath())
            assertTrue(scan(root).candidates.isEmpty())
            val game = root.resolve("Game").apply { mkdir() }
            Files.createSymbolicLink(game.resolve("Game").toPath(), outside.resolve("Alias").toPath())
            assertTrue(scan(root).candidates.isEmpty())
        } finally { outside.deleteRecursively() }
    }
    @Test fun depthLimitPreventsUnrelatedDeepScan() = withRoot { root ->
        root.executable("Deep/a/b/c/d/Deep")
        assertTrue(scan(root).candidates.isEmpty())
    }
    @Test fun unreadableLaunchTargetIsSkipped() = withRoot { root ->
        val target = root.executable("Locked/Locked")
        val original = Files.getPosixFilePermissions(target.toPath())
        try {
            Files.setPosixFilePermissions(target.toPath(), emptySet())
            assertFalse(target.canRead())
            assertTrue(scan(root).candidates.isEmpty())
        } finally { Files.setPosixFilePermissions(target.toPath(), original) }
    }
    @Test fun selectedLibraryHasBoundedGameFolderCount() = withRoot { root ->
        repeat(129) { index -> root.executable("Title$index/Title$index") }
        assertEquals(128, scan(root).candidates.size)
    }

    @Test
    fun `a second drive under run media is not a system folder`() {
        assertFalse(LinuxGameDiscovery.isSystemPath("/run/media/user/Games"))
        assertFalse(LinuxGameDiscovery.isSystemPath("/home/user/Games"))
        assertTrue(LinuxGameDiscovery.isSystemPath("/run/user/1000"))
        assertTrue(LinuxGameDiscovery.isSystemPath("/run/mediafoo"))
        assertTrue(LinuxGameDiscovery.isSystemPath("/run"))
        assertTrue(LinuxGameDiscovery.isSystemPath("/usr/games"))
        assertTrue(LinuxGameDiscovery.isSystemPath("/"))
    }
}
