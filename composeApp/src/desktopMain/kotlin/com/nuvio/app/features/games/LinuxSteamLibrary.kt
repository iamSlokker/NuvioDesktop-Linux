package com.nuvio.app.features.games

import java.io.File

/**
 * A Steam library, as Steam itself describes it: one `appmanifest_<id>.acf` per installed app.
 *
 * Steam has to start its own games on Linux. A Proton game has only a Windows `.exe` in its folder,
 * which no folder scan can run, and a native one needs the Steam runtime and the client's login that
 * a directly started binary does not get. So a game in a library becomes `steam://rungameid/<id>`,
 * whatever its folder holds, and the executable heuristics are not consulted.
 */
internal object LinuxSteamLibrary {
    /** Apps Steam installs beside the games and lists as ordinary manifests. */
    private val toolAppIds = setOf(
        228980L, // Steamworks Common Redistributables
        1007L, // Steamworks SDK Redist
        1070560L, 1391110L, 1628350L, 4183110L, // Steam Linux Runtime 1.0, 2.0, 3.0, 4.0
        1161040L, 1826330L, // Proton BattlEye Runtime, Proton EasyAntiCheat Runtime
    )
    private val toolNames = Regex(
        "^(proton\\b|steam linux runtime\\b|steamworks (common|sdk)\\b)",
        RegexOption.IGNORE_CASE,
    )
    private val toolFolders = Regex("^(proton\\b|steamlinuxruntime\\b)", RegexOption.IGNORE_CASE)
    private val libraryPath = Regex("\"path\"\\s+\"((?:[^\"\\\\]|\\\\.)*)\"", RegexOption.IGNORE_CASE)

    fun isTool(manifest: SteamAppManifest): Boolean =
        manifest.appId in toolAppIds || toolNames.containsMatchIn(manifest.name.orEmpty()) ||
            toolFolders.containsMatchIn(manifest.installDirectory)

    /**
     * What [gamesDirectory] holds if it is a library's `steamapps/common`, or a single game's folder
     * inside one; null when it is not part of a library with manifests, so an ordinary folder scan
     * applies. The main library's `libraryfolders.vdf` also names the other drives' libraries.
     */
    fun candidates(gamesDirectory: File): List<GameFolderCandidate>? {
        val common = when {
            isCommon(gamesDirectory) -> gamesDirectory
            gamesDirectory.parentFile?.let(::isCommon) == true -> gamesDirectory.parentFile
            else -> return null
        }
        val games = gamesIn(common) ?: return null
        if (gamesDirectory != common) return games.filter { it.folder == gamesDirectory }
        val others = otherLibraries(common.parentFile).flatMap { gamesIn(it).orEmpty() }
        return (games + others).distinctBy { it.steamAppId }
    }

    private fun isCommon(directory: File): Boolean =
        directory.name.equals("common", ignoreCase = true) &&
            directory.parentFile?.name.equals("steamapps", ignoreCase = true)

    private fun gamesIn(common: File): List<GameFolderCandidate>? {
        val manifests = LinuxGameDiscovery.children(common.parentFile).filter {
            it.isFile && it.length() <= MaxManifestBytes && it.name.startsWith("appmanifest_") && it.extension == "acf"
        }.mapNotNull { file -> runCatching { parseSteamAppManifest(file.readText()) }.getOrNull() }
        if (manifests.isEmpty()) return null
        return manifests.filterNot(::isTool).mapNotNull { manifest ->
            // A manifest with no folder is a game still queued for download.
            val folder = File(common, manifest.installDirectory).takeIf(LinuxGameDiscovery::directory)
                ?: return@mapNotNull null
            GameFolderCandidate(
                folder = folder,
                executablePath = "steam://rungameid/${manifest.appId}",
                title = manifest.name?.takeIf(String::isNotBlank) ?: GameFolderScanner.folderTitle(folder.name),
                steamAppId = manifest.appId,
            )
        }.sortedBy { it.title.lowercase() }
    }

    private fun otherLibraries(steamApps: File): List<File> {
        val file = File(steamApps, "libraryfolders.vdf").takeIf { it.isFile && it.length() <= MaxManifestBytes }
            ?: return emptyList()
        val text = runCatching { file.readText() }.getOrNull() ?: return emptyList()
        return libraryPath.findAll(text)
            .map { File(it.groupValues[1].replace("\\\\", "\\"), "steamapps/common") }
            .filter { it != File(steamApps, "common") && LinuxGameDiscovery.allowedRoot(it) }
            .distinct().toList()
    }

    private const val MaxManifestBytes = 1024 * 1024L
}
