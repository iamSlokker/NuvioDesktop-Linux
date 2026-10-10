package com.nuvio.app.features.games

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameLaunchTargetTest {
    private fun tempRoot(name: String): File = Files.createTempDirectory("nuvio-lnk-$name").toFile()

    private fun File.file(relative: String): File {
        val file = File(this, relative)
        file.parentFile.mkdirs()
        file.writeBytes(ByteArray(1))
        return file
    }

    @Test
    fun shellLinkResolvesToItsTargetWithArgumentsAndWorkingDirectory() {
        val root = tempRoot("plain")
        val game = root.file("Hades/Hades.exe")
        val link = File(root, "Hades.lnk")
        link.writeBytes(
            shellLink(
                target = game.absolutePath,
                arguments = "-windowed \"--profile=My Save\"",
                workingDirectory = game.parentFile.absolutePath,
            ),
        )

        val target = resolveGameLaunchTarget(link.absolutePath)

        assertIs<GameLaunchTarget.Executable>(target)
        assertEquals(game.absolutePath, target.path)
        assertEquals(listOf("-windowed", "--profile=My Save"), target.arguments)
        assertEquals(game.parentFile.absolutePath, target.workingDirectory)
        assertEquals(game.parentFile.absolutePath, defaultWorkingDirectory(link.absolutePath))
    }

    @Test
    fun environmentVariableTargetWinsWhenTheLiteralPathIsStale() {
        val root = tempRoot("env")
        val game = root.file("Game/game.exe")
        val link = File(root, "Game.lnk")
        link.writeBytes(
            shellLink(
                target = "D:\\Old Drive\\Game\\game.exe",
                environmentTarget = "%NUVIO_TEST_GAMES%\\Game\\game.exe",
            ),
        )

        val lookup = { name: String -> root.absolutePath.takeIf { name == "NUVIO_TEST_GAMES" } }

        assertEquals(game.absolutePath, parseShellLink(link.readBytes(), root, lookup)?.targetPath)
        // Variable unset: the unexpanded form is no file, and the literal path is what is reported.
        assertEquals("D:\\Old Drive\\Game\\game.exe", parseShellLink(link.readBytes(), root) { null }?.targetPath)
    }

    @Test
    fun relativePathResolvesAgainstTheShortcutsFolderWhenThereIsNoLinkInfo() {
        val root = tempRoot("relative")
        val game = root.file("bin/game.exe")
        val link = File(root, "Game.lnk")
        link.writeBytes(shellLink(target = null, relativePath = ".\\bin\\game.exe"))

        val target = resolveGameLaunchTarget(link.absolutePath)

        assertIs<GameLaunchTarget.Executable>(target)
        assertEquals(game.absolutePath, File(target.path).absolutePath)
    }

    @Test
    fun itemIdListNamesTheTargetWhenThereIsNoLinkInfoAndTheRelativePathIsWrong() {
        // Riot's shortcuts: no LinkInfo, a relative path that does not lead to the game from
        // where the shortcut sits, and the real target only in the id list.
        val root = tempRoot("idlist")
        val game = root.file("Riot Games/Riot Client/RiotClientServices.exe")
        val desktop = File(root, "Users/Public/Desktop").apply { mkdirs() }
        val link = File(desktop, "League of Legends.lnk")
        link.writeBytes(
            shellLink(
                target = null,
                relativePath = "..\\Riot Games\\Riot Client\\RiotClientServices.exe",
                idListPath = game.absolutePath,
                arguments = "--launch-product=league_of_legends",
            ),
        )

        val target = resolveGameLaunchTarget(link.absolutePath)

        assertIs<GameLaunchTarget.Executable>(target)
        assertEquals(game.absolutePath, target.path)
        assertEquals(listOf("--launch-product=league_of_legends"), target.arguments)
    }

    @Test
    fun storeAppShortcutWithOnlyAnItemListIsLeftToTheShell() {
        val root = tempRoot("store")
        val link = File(root, "Notepad.lnk")
        link.writeBytes(shellLink(target = null, idList = true))

        val target = resolveGameLaunchTarget(link.absolutePath)

        assertEquals(GameLaunchTarget.ShellShortcut(link.absolutePath), target)
        assertNull(defaultWorkingDirectory(link.absolutePath))
    }

    @Test
    fun somethingThatIsNotAShellLinkIsLeftToTheShellToo() {
        val root = tempRoot("garbage")
        val link = File(root, "Broken.lnk")
        link.writeText("not a shortcut")

        assertNull(parseShellLink(link.readBytes(), root))
        assertEquals(GameLaunchTarget.ShellShortcut(link.absolutePath), resolveGameLaunchTarget(link.absolutePath))
    }

    @Test
    fun steamInternetShortcutIsAProtocolLaunch() {
        val root = tempRoot("url")
        val link = File(root, "Terraria.url")
        link.writeText(
            """
            [{000214A0-0000-0000-C000-000000000046}]
            Prop3=19,0
            [InternetShortcut]
            IDList=
            IconIndex=0
            URL=steam://rungameid/105600
            IconFile=C:\Program Files (x86)\Steam\steam\games\a4ce.ico
            """.trimIndent(),
        )

        assertEquals(GameLaunchTarget.Protocol("steam://rungameid/105600"), resolveGameLaunchTarget(link.absolutePath))
        assertEquals(105600L, steamGameReference("Terraria", link.absolutePath, emptyList()).let {
            steamAppIdFromLaunchTarget(it.executablePath, it.arguments)
        })
    }

    @Test
    fun internetShortcutWithoutAUrlIsLeftToTheShell() {
        assertNull(parseInternetShortcut("[InternetShortcut]\nIconIndex=0\n"))
        assertNull(parseInternetShortcut("[Other]\nURL=steam://rungameid/1\n"))
    }

    @Test
    fun plainPathsAndProtocolUrlsPassThroughUntouched() {
        assertEquals(GameLaunchTarget.Executable("C:\\Games\\Hades\\Hades.exe"), resolveGameLaunchTarget("C:\\Games\\Hades\\Hades.exe"))
        assertEquals(GameLaunchTarget.Protocol("steam://rungameid/620"), resolveGameLaunchTarget(" steam://rungameid/620 "))
        assertTrue(isShortcutFile("C:\\Users\\me\\Desktop\\Hades.LNK"))
        assertTrue(isShortcutFile("Terraria.url"))
        assertTrue(!isShortcutFile("C:\\Games\\lnk\\game.exe"))
    }

    @Test
    fun steamReferenceFollowsAShortcutIntoTheSteamLibrary() {
        val root = tempRoot("steamlib")
        val game = root.file("steamapps/common/Hades/Hades.exe")
        File(root, "steamapps/appmanifest_1145360.acf").writeText(
            "\"AppState\"\n{\n\t\"appid\"\t\t\"1145360\"\n\t\"name\"\t\t\"Hades\"\n\t\"installdir\"\t\t\"Hades\"\n}\n",
        )
        val link = File(root, "Hades.lnk")
        link.writeBytes(shellLink(target = game.absolutePath, arguments = "-applaunch 1145360"))

        val reference = steamGameReference("Hades", link.absolutePath, listOf("-windowed"))

        assertEquals(game.absolutePath, reference.executablePath)
        assertEquals(listOf("-applaunch", "1145360", "-windowed"), reference.arguments)
        assertEquals(1145360L, steamAppIdFromInstallLocation(reference.executablePath))
    }

    @Test
    fun folderScanImportsEachShortcutInTheFolderByItsOwnName() {
        val root = tempRoot("shortcuts")
        val installed = root.file("Installed/Hades/Hades.exe")
        val shortcuts = File(root, "Games").apply { mkdirs() }
        File(shortcuts, "Hades.lnk").writeBytes(shellLink(target = installed.absolutePath))
        File(shortcuts, "Digimon Story Time Stranger.url").writeText("[InternetShortcut]\nURL=steam://rungameid/2160960\n")
        File(shortcuts, "Gone.lnk").writeBytes(shellLink(target = File(root, "Missing/gone.exe").absolutePath))
        File(shortcuts, "Notepad.lnk").writeBytes(shellLink(target = null, idList = true))

        val result = GameFolderScanner.scan(shortcuts, existing = emptyList(), linux = false)

        assertEquals(listOf("Digimon Story Time Stranger", "Hades", "Notepad"), result.candidates.map { it.title })
        assertEquals(listOf(2160960L, null, null), result.candidates.map { it.steamAppId })
        assertTrue(result.candidates.all { isShortcutFile(it.executablePath) })
        assertEquals(1, result.foldersWithoutExecutable)
        assertEquals(0, result.skippedExisting)
    }

    @Test
    fun folderScanSkipsShortcutsToGamesAlreadyInTheLibraryByTargetOrAppId() {
        val root = tempRoot("dupes")
        val installed = root.file("Installed/Hades/Hades.exe")
        val shortcuts = File(root, "Games").apply { mkdirs() }
        File(shortcuts, "Hades (shortcut).lnk").writeBytes(shellLink(target = installed.absolutePath))
        File(shortcuts, "Terraria.url").writeText("[InternetShortcut]\nURL=steam://rungameid/105600\n")
        val existing = listOf(
            GameEntry(id = "1", title = "Hades", executablePath = installed.absolutePath),
            GameEntry(id = "2", title = "Terraria: Journey's End", steamAppId = 105600),
        )

        val result = GameFolderScanner.scan(shortcuts, existing, linux = false)

        assertTrue(result.candidates.isEmpty())
        assertEquals(2, result.skippedExisting)
        assertEquals(0, result.foldersWithoutExecutable)
    }

    /**
     * A Shell Link per [MS-SHLLINK], with just the pieces the parser reads: the header, an item id
     * list when asked, a LinkInfo carrying [target] as its local base path, the Unicode string data,
     * and an EnvironmentVariableDataBlock when [environmentTarget] is given.
     */
    private fun shellLink(
        target: String?,
        arguments: String? = null,
        workingDirectory: String? = null,
        relativePath: String? = null,
        environmentTarget: String? = null,
        idList: Boolean = false,
        idListPath: String? = null,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(value: Int) = out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        fun u32(value: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        fun stringData(value: String) {
            u16(value.length)
            out.write(value.toByteArray(Charsets.UTF_16LE))
        }

        var flags = 1 shl 7 // IsUnicode
        if (idList || idListPath != null) flags = flags or (1 shl 0)
        if (target != null) flags = flags or (1 shl 1)
        if (relativePath != null) flags = flags or (1 shl 3)
        if (workingDirectory != null) flags = flags or (1 shl 4)
        if (arguments != null) flags = flags or (1 shl 5)
        if (environmentTarget != null) flags = flags or (1 shl 9)

        u32(0x4C)
        out.write(byteArrayOf(0x01, 0x14, 0x02, 0, 0, 0, 0, 0, 0xC0.toByte(), 0, 0, 0, 0, 0, 0, 0x46))
        u32(flags)
        out.write(ByteArray(0x4C - 24))

        if (idListPath != null) {
            out.write(fileSystemIdList(idListPath))
        } else if (idList) {
            u16(6)
            u16(4)
            out.write(byteArrayOf(0x1F, 0x00))
            u16(0)
        }

        if (target != null) {
            val localBase = target.toByteArray(Charsets.ISO_8859_1)
            val volumeIdSize = 0x11
            val localBasePathOffset = 0x1C + volumeIdSize
            val commonPathSuffixOffset = localBasePathOffset + localBase.size + 1
            u32(commonPathSuffixOffset + 1)
            u32(0x1C)
            u32(1)
            u32(0x1C)
            u32(localBasePathOffset)
            u32(0)
            u32(commonPathSuffixOffset)
            u32(volumeIdSize)
            u32(3)
            u32(0)
            u32(0x10)
            out.write(0)
            out.write(localBase)
            out.write(0)
            out.write(0)
        }

        relativePath?.let(::stringData)
        workingDirectory?.let(::stringData)
        arguments?.let(::stringData)

        if (environmentTarget != null) {
            u32(0x314)
            u32(0xA0000001.toInt())
            out.write(environmentTarget.toByteArray(Charsets.ISO_8859_1).copyOf(260))
            out.write(environmentTarget.toByteArray(Charsets.UTF_16LE).copyOf(520))
        }
        u32(0)
        return out.toByteArray()
    }

    /**
     * An item id list walking `C:\` then each path segment as a file-system item: 8.3-ish short
     * name in the item, long name in a version-9 `0xBEEF0004` extension block, as Windows 10
     * writes them.
     */
    private fun fileSystemIdList(path: String): ByteArray {
        val items = ByteArrayOutputStream()
        fun u16(value: Int) = items.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        fun u32(value: Int) = items.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())

        val segments = path.split('\\').filter(String::isNotEmpty)
        val drive = segments.first() + "\\"
        u16(2 + 1 + drive.length + 1 + 20)
        items.write(0x2F)
        items.write(drive.toByteArray(Charsets.ISO_8859_1))
        items.write(0)
        items.write(ByteArray(20))

        segments.drop(1).forEachIndexed { index, name ->
            val isFile = index == segments.size - 2
            val shortName = name.uppercase().take(6).replace(" ", "") + "~1"
            val shortBytes = shortName.toByteArray(Charsets.ISO_8859_1) + 0
            val padded = if (shortBytes.size % 2 == 1) shortBytes + 0 else shortBytes
            val longBytes = name.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
            val extensionSize = 46 + longBytes.size + 2
            u16(2 + 12 + padded.size + extensionSize)
            items.write(if (isFile) 0x32 else 0x31)
            items.write(0)
            u32(if (isFile) 1 else 0)
            u32(0)
            u16(if (isFile) 0x20 else 0x10)
            items.write(padded)
            u16(extensionSize)
            u16(9)
            u32(0xBEEF0004.toInt())
            items.write(ByteArray(46 - 8))
            items.write(longBytes)
            u16(14)
        }
        u16(0)
        val body = items.toByteArray()
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(body.size.toShort()).array() + body
    }
}
