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

/**
 * [shaderOpts] is the `glsl-shader-opts` value the chain needs (the Sharpen menu's CAS strength).
 * [animeTuned] says an Anime4K or custom chain actually runs, which selects the scaling and deband
 * of [linuxAnimeScalingProperties].
 */
internal data class LinuxShaderProfile(
    val chain: String,
    val label: String?,
    val shaderOpts: String = "",
    val animeTuned: Boolean = false,
)

/**
 * The scaler and deband half of applyDesktopAnimeProfile: Stremio-Kai's anime base with a chain
 * running, the bridge's live-action baseline otherwise (with the Advanced menu's Upscaling choice).
 * Not hqdn3d: it is a GPL filter and the bundled FFmpeg is an LGPL build, so mpv cannot load it.
 */
internal fun linuxAnimeScalingProperties(settings: PlayerSettingsUiState, animeTuned: Boolean): List<Pair<String, String>> {
    val overrides = settings.desktopMpvPropertyOverrides
    val filmGrain = overrides[DESKTOP_FILM_GRAIN_OVERRIDE_KEY]
    return if (animeTuned) {
        listOf("scale" to "ewa_lanczos", "cscale" to "ewa_lanczos", "scale-blur" to "1.05", "deband-threshold" to "45",
            "deband-grain" to desktopDebandGrain(filmGrain, "20"))
    } else {
        listOf("scale" to (overrides["scale"] ?: "spline36"), "cscale" to "lanczos", "scale-blur" to "0.0",
            "deband-threshold" to "35", "deband-grain" to desktopDebandGrain(filmGrain, "0"))
    }
}

internal fun NativePlayerController.applyLinuxAnimeScaling(settings: PlayerSettingsUiState, animeTuned: Boolean) {
    val customNames = desktopCustomMpvOptionNames(settings.desktopCustomMpvOptions)
    withVideoProfile {
        for ((key, value) in linuxAnimeScalingProperties(settings, animeTuned)) {
            if (shouldApplyNuvioRuntimeMpvProperty(settings.desktopMpvConfigMode, customNames, key)) setMpvProperty(key, value)
        }
    }
}

/** Shader selection only: no grading, scaling, vf, hwdec, HDR, RTX or SVP mutations. */
internal fun linuxShaderProfile(
    settings: PlayerSettingsUiState,
    isAnime: Boolean,
    sourceSize: DesktopVideoSourceSize?,
): LinuxShaderProfile? {
    if (!shouldApplyNuvioRuntimeMpvProperty(settings.desktopMpvConfigMode,
            desktopCustomMpvOptionNames(settings.desktopCustomMpvOptions),
            "glsl-shaders")) return null
    // The Sharpen menu's CAS pass hooks LUMA, so it rides on any chain, including none, and needs
    // no media eligibility: unlike Anime4K it neither upscales nor assumes SDR.
    val casSharpness = desktopCasSharpness(settings.desktopMpvPropertyOverrides[DESKTOP_SHARPEN_OVERRIDE_KEY])
    val casPath = casSharpness?.let { DesktopAnimeShaders.casSharpenPath() }
    val shaderOpts = if (casPath != null) "nuvio_cas_sharpness=$casSharpness" else ""
    // Ownership is checked first: explicit Replace/Full options belong to the user. As on Windows the
    // chains also run on HDR sources; the wait is for the size the UHD preference needs, which arrives
    // with the first video-params.
    if (sourceSize == null || sourceSize.width <= 0 || sourceSize.height <= 0)
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
    return LinuxShaderProfile(linuxShaderPathList(paths + listOfNotNull(casPath)), label, shaderOpts,
        animeTuned = paths.isNotEmpty())
}

internal fun NativePlayerController.applyLinuxShaderProfile(
    settings: PlayerSettingsUiState,
    isAnime: Boolean,
    sourceSize: DesktopVideoSourceSize?,
): String? {
    val profile = linuxShaderProfile(settings, isAnime, sourceSize) ?: return null
    withVideoProfile {
        // Opts before shaders, so the chain never compiles once with a stale strength.
        setMpvProperty("glsl-shader-opts", profile.shaderOpts)
        setMpvProperty("glsl-shaders", profile.chain)
    }
    return profile.label
}
