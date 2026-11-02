package com.nuvio.app.features.games

import java.io.File

/**
 * One game a folder scan found: the folder, the executable chosen for it, and what it should be
 * called. [steamAppId] is set only when the folder sits in a Steam library, where the manifest
 * beside it names both the game and its id outright.
 */
data class GameFolderCandidate(
    val folder: File,
    val executablePath: String,
    val title: String,
    val steamAppId: Long? = null,
)

data class GameFolderScanResult(
    val candidates: List<GameFolderCandidate>,
    /** Folders that were games already in the library, left out rather than duplicated. */
    val skippedExisting: Int,
    /** Sub-folders with nothing runnable in them, so nothing could be made of them. */
    val foldersWithoutExecutable: Int,
)

/**
 * Turns a folder of games into library entries, one per sub-folder.
 *
 * The folder is whatever the user keeps games in: a Steam library (either its root or its
 * `steamapps/common`), any directory of one-game-per-folder installs, or a folder of shortcuts —
 * a Desktop "Games" folder, or the Start Menu's Steam folder of `.url` files. Each sub-folder
 * becomes a candidate when something runnable is found in it, named after the Steam manifest when
 * there is one and after the folder otherwise; each shortcut directly in the folder becomes one
 * named after itself, pointing at the shortcut so the entry follows it if it is re-targeted.
 *
 * Picking the executable is a heuristic, and the confirmation dialog exists because of that: a
 * game folder routinely carries an uninstaller, a crash handler, and a redistributable installer
 * alongside the game, and nothing on disk says which is which. Known helpers are filtered out by
 * name, and of what is left the one named like its folder wins, then the shallowest, then the
 * largest. The result is right far more often than not and is always editable afterwards.
 */
object GameFolderScanner {
    fun scan(root: File, existing: List<GameEntry>): GameFolderScanResult = scan(root, existing, linuxGamesHost())

    internal fun scan(root: File, existing: List<GameEntry>, linux: Boolean): GameFolderScanResult {
        if (linux && !LinuxGameDiscovery.allowedRoot(root)) return GameFolderScanResult(emptyList(), 0, 0)
        val gamesDirectory = gamesDirectoryFor(root)
        if (linux && !LinuxGameDiscovery.allowedRoot(gamesDirectory)) return GameFolderScanResult(emptyList(), 0, 0)
        val manifests = steamManifestsBeside(gamesDirectory, linux)
        fun pathKey(path: String): String = if (linux) runCatching { File(path).canonicalPath }.getOrDefault(path)
            else path.lowercase()
        val existingPaths = existing.mapNotNull { it.executablePath?.let(::pathKey) }.toMutableSet()
        if (linux) existing.mapNotNull { it.executablePath }.filter { it.endsWith(".desktop", true) }.forEach { path ->
            LinuxGameDiscovery.desktopTarget(File(path))?.path?.let { existingPaths += pathKey(it) }
        }
        val existingAppIds = existing.mapNotNull { it.steamAppId }.toMutableSet()
        val existingTitles = existing.map { it.title.normalizedGameTitle() }.toMutableSet()

        val linuxChildren = if (linux) LinuxGameDiscovery.children(gamesDirectory) else emptyList()
        val singleLinuxGame = linux && LinuxGameDiscovery.choose(gamesDirectory)?.let {
            it.parentFile == gamesDirectory || it.relativeTo(gamesDirectory).invariantSeparatorsPath
                .substringBefore('/') in setOf("bin", "linux", "linux64")
        } == true
        val gameFolders = (if (linux) {
            if (singleLinuxGame) listOf(gamesDirectory) else linuxChildren.filter {
                LinuxGameDiscovery.directory(it) && !it.isHidden && it.name !in IGNORED_FOLDERS
            }.take(128)
        } else gamesDirectory
            .listFiles { file: File -> file.isDirectory && !file.isHidden && file.name !in IGNORED_FOLDERS }
            ?.sortedBy { it.name.lowercase() }
            .orEmpty())
            // A folder holding one game directly, with no sub-folders to speak of, is the game.
            .ifEmpty { listOf(gamesDirectory) }

        var skippedExisting = 0
        var withoutExecutable = 0
        fun keepUnlessPresent(candidate: GameFolderCandidate, resolvedPath: String?): GameFolderCandidate? {
            val alreadyPresent = pathKey(candidate.executablePath) in existingPaths ||
                (resolvedPath != null && pathKey(resolvedPath) in existingPaths) ||
                (candidate.steamAppId != null && candidate.steamAppId in existingAppIds) ||
                candidate.title.normalizedGameTitle() in existingTitles
            if (alreadyPresent) skippedExisting += 1
            else if (linux) {
                existingPaths += pathKey(candidate.executablePath)
                if (resolvedPath != null) existingPaths += pathKey(resolvedPath)
                candidate.steamAppId?.let { existingAppIds += it }
                existingTitles += candidate.title.normalizedGameTitle()
            }
            return candidate.takeUnless { alreadyPresent }
        }

        // fork:linux - Steam starts its own games, through Proton when they need it.
        if (linux) LinuxSteamLibrary.candidates(gamesDirectory)?.let { games ->
            return GameFolderScanResult(games.mapNotNull { keepUnlessPresent(it, resolvedPath = null) }, skippedExisting, 0)
        }

        val shortcuts = if (linux) linuxChildren.filter { it.extension.equals("desktop", true) }
            else gamesDirectory.listFiles { file: File -> file.isFile && isShortcutFile(file.name) }
                ?.sortedBy { it.name.lowercase() }.orEmpty()
        val shortcutCandidates = shortcuts
            .mapNotNull { shortcut ->
                val target = if (linux) LinuxGameDiscovery.desktopTarget(shortcut)
                    else resolveGameLaunchTarget(shortcut.absolutePath)
                if (target == null) { withoutExecutable += 1; return@mapNotNull null }
                val candidate = shortcutCandidate(shortcut, target)
                if (candidate == null) {
                    withoutExecutable += 1
                    return@mapNotNull null
                }
                keepUnlessPresent(candidate, resolvedPath = (target as? GameLaunchTarget.Executable)?.path)
            }

        val folderCandidates = gameFolders.mapNotNull { folder ->
            val executable = if (linux) LinuxGameDiscovery.choose(folder) else chooseExecutable(folder)
            if (executable == null) {
                // The root itself, standing in for an absent list of sub-folders, is not "a folder
                // with no executable" when the shortcuts in it were the games.
                if (folder != gamesDirectory || shortcuts.isEmpty()) withoutExecutable += 1
                return@mapNotNull null
            }
            val manifest = manifests[folder.name.lowercase()]
            val candidate = GameFolderCandidate(
                folder = folder,
                executablePath = executable.absolutePath,
                title = manifest?.name?.takeIf(String::isNotBlank) ?: folderTitle(folder.name),
                steamAppId = manifest?.appId,
            )
            keepUnlessPresent(candidate, resolvedPath = null)
        }
        return GameFolderScanResult(shortcutCandidates + folderCandidates, skippedExisting, withoutExecutable)
    }

    /**
     * A shortcut as a candidate, or null for one whose target is gone. The shortcut's own name is
     * the title — Steam and the launchers write the store title there — and a Steam app id comes
     * from the resolved target the same way it does for a typed-in entry: a `steam://rungameid`
     * URL, `-applaunch`, or an install under `steamapps/common`.
     */
    internal fun shortcutCandidate(
        shortcut: File,
        target: GameLaunchTarget = resolveGameLaunchTarget(shortcut.absolutePath),
    ): GameFolderCandidate? {
        val steamAppId = when (target) {
            is GameLaunchTarget.Executable -> {
                if (!File(target.path).isFile) return null
                steamAppIdFromLaunchTarget(target.path, target.arguments)
                    ?: steamAppIdFromInstallLocation(target.path)
            }
            is GameLaunchTarget.Protocol -> steamAppIdFromLaunchTarget(target.url, emptyList())
            is GameLaunchTarget.ShellShortcut -> null
        }
        return GameFolderCandidate(
            folder = shortcut.absoluteFile.parentFile,
            executablePath = shortcut.absolutePath,
            title = folderTitle(shortcut.nameWithoutExtension),
            steamAppId = steamAppId,
        )
    }

    /**
     * The directory whose children are games. A Steam library root and its `steamapps` folder both
     * resolve to `steamapps/common`; anything else is taken as it is.
     */
    internal fun gamesDirectoryFor(root: File): File {
        if (root.name.equals("steamapps", ignoreCase = true)) {
            return File(root, "common").takeIf(File::isDirectory) ?: root
        }
        val common = File(root, "steamapps${File.separator}common")
        return common.takeIf(File::isDirectory) ?: root
    }

    /** Steam's manifests keyed by install directory (lower-cased), when [gamesDirectory] is `common`. */
    private fun steamManifestsBeside(gamesDirectory: File, linux: Boolean): Map<String, SteamAppManifest> {
        if (!gamesDirectory.name.equals("common", ignoreCase = true)) return emptyMap()
        val steamApps = gamesDirectory.parentFile ?: return emptyMap()
        if (!steamApps.name.equals("steamapps", ignoreCase = true)) return emptyMap()
        val files = if (linux) LinuxGameDiscovery.children(steamApps).filter {
            it.isFile && it.length() <= 1024 * 1024 && it.name.startsWith("appmanifest_") && it.extension == "acf"
        } else steamApps.listFiles { file: File -> file.isFile && file.name.startsWith("appmanifest_") && file.extension == "acf" }.orEmpty().toList()
        return files
            .mapNotNull { manifest -> runCatching { parseSteamAppManifest(manifest.readText()) }.getOrNull() }
            .associateBy { it.installDirectory.lowercase() }
    }

    /** The executable most likely to be the game itself, or null when the folder has none. */
    internal fun chooseExecutable(folder: File): File? {
        val found = mutableListOf<ExecutableFind>()
        collectExecutables(folder, depth = 0, into = found)
        if (found.isEmpty()) return null
        val folderTitle = folder.name.normalizedGameTitle()
        return found
            .sortedWith(
                compareByDescending<ExecutableFind> { nameAffinity(it.file.nameWithoutExtension, folderTitle) }
                    .thenBy { it.depth }
                    .thenByDescending { it.file.length() },
            )
            .first()
            .file
    }

    private fun collectExecutables(directory: File, depth: Int, into: MutableList<ExecutableFind>) {
        val children = directory.listFiles() ?: return
        children.forEach { child ->
            when {
                child.isFile -> {
                    if (child.extension.equals("exe", ignoreCase = true) && !isHelperExecutable(child.name)) {
                        into += ExecutableFind(child, depth)
                    }
                }
                child.isDirectory && depth < MAX_SCAN_DEPTH && !child.isHidden &&
                    child.name.lowercase() !in IGNORED_SUBFOLDERS -> {
                    collectExecutables(child, depth + 1, into)
                }
            }
        }
    }

    /**
     * How much an executable's name looks like its folder's: an exact match beats one containing
     * the other, which beats nothing in common. `ELDEN RING/Game/eldenring.exe` and
     * `Hades/Hades.exe` both resolve on this alone; `Witcher 3/bin/x64/witcher3.exe` does too.
     */
    internal fun nameAffinity(executableName: String, folderTitle: String): Int {
        val name = executableName.normalizedGameTitle()
        if (name.isEmpty() || folderTitle.isEmpty()) return 0
        return when {
            name == folderTitle -> 3
            name.length >= 4 && folderTitle.contains(name) -> 2
            folderTitle.length >= 4 && name.contains(folderTitle) -> 2
            else -> 0
        }
    }

    /**
     * Executables that are never the game. Matched as substrings of the lower-cased file name, so
     * `UnityCrashHandler64.exe`, `unins000.exe`, `vc_redist.x64.exe` and `EasyAntiCheat_Setup.exe`
     * all fall out without listing each spelling.
     */
    internal fun isHelperExecutable(fileName: String): Boolean {
        val name = fileName.lowercase()
        return HELPER_NAME_FRAGMENTS.any { fragment -> name.contains(fragment) }
    }

    /** Folder names as titles: underscores and dots are separators, case is left alone. */
    internal fun folderTitle(folderName: String): String =
        folderName
            .replace(Regex("[_.]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private data class ExecutableFind(val file: File, val depth: Int)

    private const val MAX_SCAN_DEPTH = 3

    private val IGNORED_FOLDERS = setOf("_CommonRedist", "Steamworks Shared")

    private val IGNORED_SUBFOLDERS = setOf(
        "_commonredist",
        "redist",
        "redistributables",
        "redistributable",
        "_redist",
        "directx",
        "dotnet",
        "dotnetfx",
        "vcredist",
        "vc_redist",
        "support",
        "easyanticheat",
        "battleye",
        "installers",
        "installer",
        "prerequisites",
        "tools",
        "sdk",
        "engine",
        "__installer",
        "commonredist",
        "thirdparty",
    )

    private val HELPER_NAME_FRAGMENTS = listOf(
        "unins",
        "uninstall",
        "crash",
        "report",
        "setup",
        "install",
        "redist",
        "dxsetup",
        "dxwebsetup",
        "easyanticheat",
        "battleye",
        "_be",
        "helper",
        "updater",
        "update",
        "patcher",
        "config",
        "cleanup",
        "activation",
        "diagnostic",
        "register",
        "benchmark",
        "readme",
        "dotnet",
        "oalinst",
        "physx",
        "steamerrorreporter",
    )
}
