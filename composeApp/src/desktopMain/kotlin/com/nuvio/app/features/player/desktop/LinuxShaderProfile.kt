package com.nuvio.app.features.player.desktop

import com.nuvio.app.core.i18n.localizedString
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.player.DesktopAnimeMode
import com.nuvio.app.features.player.DesktopAnimeShaderSkipReason
import com.nuvio.app.features.player.DesktopVideoSourceSize
import com.nuvio.app.features.player.PlayerSettingsUiState
import com.nuvio.app.features.player.desktopActiveAnimeMode
import com.nuvio.app.features.player.planDesktopAnimeShaders
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * config=no leaves mpv with no cache location, so without this every launch recompiles the whole
 * gpu-next pipeline, Anime4K and custom chains included. Null when the folder cannot be made: mpv
 * then runs without a cache, as it did before.
 */
internal fun linuxShaderCacheOption(root: Path = DesktopStorage.rootDir): String? = runCatching {
    "gpu-shader-cache-dir=" + Files.createDirectories(root.toAbsolutePath().resolve("shader-cache"))
}.getOrNull()

// mpv's CLI-only glsl-shader/list-action aliases are not libmpv options. Use the canonical
// glsl-shaders property, also accepted by the existing custom mpv options path.
internal fun linuxShaderPathList(paths: List<String>): String =
    paths.joinToString(":") { it.replace(":", "\\:") }

/** [shaderOpts] is the `glsl-shader-opts` value the chain needs (the Sharpen menu's CAS strength). */
internal data class LinuxShaderProfile(val chain: String, val label: String?, val shaderOpts: String = "")

/** Shader selection only: no grading, scaling, vf, hwdec, HDR, RTX or SVP mutations. */
internal fun linuxShaderProfile(
    settings: PlayerSettingsUiState,
    isAnime: Boolean,
    sourceSize: DesktopVideoSourceSize?,
    confirmedSdr: Boolean = false,
): LinuxShaderProfile? {
    if (!shouldApplyNuvioRuntimeMpvProperty(settings.desktopMpvConfigMode,
            desktopCustomMpvOptionNames(settings.desktopCustomMpvOptions),
            "glsl-shaders")) return null
    // The Sharpen menu's CAS pass hooks LUMA, so it rides on any chain, including none, and needs
    // no media eligibility: unlike Anime4K it neither upscales nor assumes SDR.
    val casSharpness = desktopCasSharpness(settings.desktopMpvPropertyOverrides[DESKTOP_SHARPEN_OVERRIDE_KEY])
    val casPath = casSharpness?.let { DesktopAnimeShaders.casSharpenPath() }
    val shaderOpts = if (casPath != null) "nuvio_cas_sharpness=$casSharpness" else ""
    // Ownership is checked first: explicit Replace/Full options belong to the user.
    // Session forces and the UHD preference only affect selection AFTER media eligibility.
    if (!confirmedSdr || sourceSize == null || sourceSize.width <= 0 || sourceSize.height <= 0)
        return LinuxShaderProfile(linuxShaderPathList(listOfNotNull(casPath)), null, shaderOpts)
    val active = desktopActiveAnimeMode(settings.desktopAnimeMode, settings.desktopAnimeModeAutoEnabled,
        settings.desktopAnimeSessionOverride, isAnime)
    val custom = if (active == DesktopAnimeMode.CustomShader) {
        DesktopCustomShaders.shaderChain(settings.desktopCustomShaderPaths,
            settings.desktopAnimeSessionOverride?.customShaderPath ?: settings.desktopCustomShaderSelectedPath)
            // Revalidate even a cached catalog entry: deletion/permission changes cannot leave a stale path.
            .takeIf { it.isNotEmpty() && File(it).isFile && File(it).canRead() }.orEmpty()
    } else ""
    val plan = planDesktopAnimeShaders(
        requestedPreset = active.takeUnless { it == DesktopAnimeMode.Off || it == DesktopAnimeMode.CustomShader },
        requestedCustomShaderChain = custom,
        isSessionForced = settings.desktopAnimeSessionOverride != null,
        skipUltraHdSources = settings.desktopAnimeSkipUltraHdEnabled,
        sourceSize = sourceSize,
    )
    val paths = plan.preset?.let(DesktopAnimeShaders::shaderPaths)
        ?: listOfNotNull(plan.customShaderChain.takeIf(String::isNotEmpty))
    val label = when {
        plan.skipReason == DesktopAnimeShaderSkipReason.UltraHdSource ->
            if (active == DesktopAnimeMode.CustomShader) "Shader off — 4K source" else "Anime4K off — 4K source"
        paths.isEmpty() -> null
        plan.preset != null -> "Anime4K ${localizedString(plan.preset.labelRes)}"
        else -> File(custom).nameWithoutExtension
    }
    return LinuxShaderProfile(linuxShaderPathList(paths + listOfNotNull(casPath)), label, shaderOpts)
}

internal fun NativePlayerController.applyLinuxShaderProfile(
    settings: PlayerSettingsUiState,
    isAnime: Boolean,
    sourceSize: DesktopVideoSourceSize?,
    confirmedSdr: Boolean = false,
): String? {
    val profile = linuxShaderProfile(settings, isAnime, sourceSize, confirmedSdr) ?: return null
    withVideoProfile {
        // Opts before shaders, so the chain never compiles once with a stale strength.
        setMpvProperty("glsl-shader-opts", profile.shaderOpts)
        setMpvProperty("glsl-shaders", profile.chain)
    }
    return profile.label
}
