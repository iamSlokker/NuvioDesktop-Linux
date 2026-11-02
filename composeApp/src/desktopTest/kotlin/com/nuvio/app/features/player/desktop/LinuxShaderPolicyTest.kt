package com.nuvio.app.features.player.desktop

import com.nuvio.app.features.player.*
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.*

class LinuxShaderPolicyTest {
    private val size = DesktopVideoSourceSize(1920, 1080)
    private val settings = PlayerSettingsUiState(desktopAnimeMode = DesktopAnimeMode.ModeAFast,
        desktopAnimeModeAutoEnabled = true, desktopMpvConfigMode = DesktopMpvConfigMode.Off)

    private fun sdrProfile(settings: PlayerSettingsUiState, anime: Boolean, size: DesktopVideoSourceSize?) =
        linuxShaderProfile(settings, anime, size)

    @Test fun chainsWaitForTheSizeAndThenRunWhateverTheColours() {
        assertEquals("", linuxShaderProfile(settings, true, null)!!.chain)
        assertEquals("", linuxShaderProfile(settings, true, DesktopVideoSourceSize(0, 1080))!!.chain)
        assertEquals("", linuxShaderProfile(settings, true, DesktopVideoSourceSize(1920, -1))!!.chain)
        // No colour input any more: HDR10, Dolby Vision and HLG run the chain, as on Windows.
        assertTrue(linuxShaderProfile(settings, true, size)!!.chain.isNotEmpty())
        assertEquals("", linuxShaderProfile(settings.copy(desktopAnimeSessionOverride =
            DesktopAnimeSessionOverride(DesktopAnimeMode.Off)), true, size)!!.chain)
    }

    @Test fun theMediaEventCarriesOnlyTheSize() {
        val packed = (1920L * 65536 + 1080).toDouble()
        assertEquals(size, LinuxShaderMedia.fromEvent(packed).size)
        assertNull(LinuxShaderMedia.fromEvent(0.0).size)
    }

    @Test fun theAnimeBaseFollowsARunningChainAndNotDetection() {
        val anime = linuxShaderProfile(settings, true, size)!!
        assertTrue(anime.animeTuned)
        assertFalse(linuxShaderProfile(settings, false, size)!!.animeTuned, "undetected title, no chain")
        assertFalse(linuxShaderProfile(settings, true, DesktopVideoSourceSize(3840, 2160))!!.animeTuned, "4K skip")
        assertEquals(mapOf("scale" to "ewa_lanczos", "cscale" to "ewa_lanczos", "scale-blur" to "1.05",
            "deband-threshold" to "45", "deband-grain" to "20"),
            linuxAnimeScalingProperties(settings, true).toMap())
        assertEquals(mapOf("scale" to "spline36", "cscale" to "lanczos", "scale-blur" to "0.0",
            "deband-threshold" to "35", "deband-grain" to "0"),
            linuxAnimeScalingProperties(settings, false).toMap())
        assertEquals("ewa_hanning", linuxAnimeScalingProperties(settings.copy(
            desktopMpvPropertyOverrides = mapOf("scale" to "ewa_hanning")), false).toMap()["scale"], "Upscaling choice kept")
        assertEquals("32", linuxAnimeScalingProperties(settings.copy(desktopMpvPropertyOverrides =
            mapOf(DESKTOP_FILM_GRAIN_OVERRIDE_KEY to DESKTOP_FILM_GRAIN_HEAVY)), true).toMap()["deband-grain"], "Film grain kept")
    }

    @Test fun explicitUserOwnershipWinsInReplaceAndFull() {
        for (mode in DesktopMpvConfigMode.entries) {
            val result = linuxShaderProfile(settings.copy(desktopMpvConfigMode = mode,
                desktopCustomMpvOptions = "glsl-shaders=user.glsl"), true, size)
            if (mode == DesktopMpvConfigMode.Full || mode == DesktopMpvConfigMode.Replace) assertNull(result)
            else assertTrue(result!!.chain.isNotEmpty(), mode.name)
        }
    }

    @Test fun presetChainIsOrderedAndOffHasNoResources() {
        assertEquals(listOf("Anime4K_Clamp_Highlights.glsl", "Anime4K_Restore_CNN_M.glsl",
            "Anime4K_Upscale_CNN_x2_M.glsl", "Anime4K_AutoDownscalePre_x2.glsl",
            "Anime4K_AutoDownscalePre_x4.glsl", "Anime4K_Upscale_CNN_x2_S.glsl"),
            DesktopAnimeShaders.shaderPaths(DesktopAnimeMode.ModeAFast).map { java.io.File(it).name })
        assertTrue(DesktopAnimeShaders.shaderPaths(DesktopAnimeMode.Off).isEmpty())
        assertEquals(listOf(7, 10, 10, 6, 6, 6, 6, 5, 5), DesktopAnimeMode.entries
            .filter { it != DesktopAnimeMode.Off && it != DesktopAnimeMode.CustomShader }
            .map { DesktopAnimeShaders.shaderPaths(it).size })
    }

    @Test fun autoRequiresBothEligibilityAndEnabledMode() {
        assertTrue(sdrProfile(settings, true, size)!!.chain.isNotEmpty())
        assertEquals("", sdrProfile(settings, false, size)!!.chain)
        assertEquals("", sdrProfile(settings.copy(desktopAnimeModeAutoEnabled = false), true, size)!!.chain)
        assertEquals("", sdrProfile(settings.copy(desktopAnimeMode = DesktopAnimeMode.Off), true, size)!!.chain)
    }

    @Test fun unknownDimensionsCannotBeBypassedBySessionForceOrUhdPreference() {
        assertEquals("", sdrProfile(settings.copy(desktopAnimeSkipUltraHdEnabled = false), true, null)!!.chain)
        assertEquals("", sdrProfile(settings.copy(desktopAnimeSessionOverride =
            DesktopAnimeSessionOverride(DesktopAnimeMode.ModeAFast)), false, null)!!.chain)
    }

    @Test fun westernAnimationUsesExistingClassification() {
        val western = classifyAnimeContent(listOf("Animation"), "en", listOf("US"))
        assertEquals("", sdrProfile(settings, western.isAnime(false), size)!!.chain)
        assertTrue(sdrProfile(settings, western.isAnime(true), size)!!.chain.isNotEmpty())
    }

    @Test fun customCatalogSelectsOneFileAndRevalidatesPermissionsAndDeletion() {
        if (DesktopHostOs.current != DesktopHostOs.LINUX) return
        val dir = Files.createTempDirectory("nuvio-shader-policy-")
        try {
            val first = dir.resolve("a 日本語.glsl")
            val second = dir.resolve("b test.hook")
            Files.writeString(first, "malformed GLSL is diagnosed by libplacebo, not parsed by Kotlin")
            Files.writeString(second, "// shader")
            val custom = settings.copy(desktopAnimeMode = DesktopAnimeMode.CustomShader,
                desktopCustomShaderPaths = dir.toString(), desktopCustomShaderSelectedPath = first.toString())
            assertEquals(first.toString(), sdrProfile(custom, true, size)!!.chain)
            assertEquals(second.toString(), sdrProfile(custom.copy(desktopCustomShaderSelectedPath = second.toString()), true, size)!!.chain)
            Files.setPosixFilePermissions(first, PosixFilePermissions.fromString("---------"))
            assertEquals("", sdrProfile(custom, true, size)!!.chain)
            Files.delete(first)
            assertEquals("", sdrProfile(custom, true, size)!!.chain)
            assertEquals("", sdrProfile(custom.copy(desktopCustomShaderPaths = ""), true, size)!!.chain)
        } finally { dir.toFile().deleteRecursively() }
    }

    @Test fun colonSpacesUnicodeAndBackslashArePathListSafe() {
        assertEquals("/tmp/a\\:b 日本語.glsl:/tmp/c\\d.glsl",
            linuxShaderPathList(listOf("/tmp/a:b 日本語.glsl", "/tmp/c\\d.glsl")))
    }

    @Test fun canonicalShaderOptionPreservesReplaceAndFullOwnership() {
        for (mode in DesktopMpvConfigMode.entries) {
            val result = sdrProfile(settings.copy(desktopMpvConfigMode = mode,
                desktopCustomMpvOptions = "glsl-shaders=user.glsl"), true, size)
            if (mode == DesktopMpvConfigMode.Replace || mode == DesktopMpvConfigMode.Full) assertNull(result)
            else assertTrue(result!!.chain.isNotEmpty())
        }
        assertNotNull(sdrProfile(settings.copy(desktopMpvConfigMode = DesktopMpvConfigMode.Replace,
            desktopCustomMpvOptions = "glsl-shader-opts=strength=1"), true, size))
    }

    @Test fun sourceAndSettingsChangesDoNotRetainOldChain() {
        assertTrue(sdrProfile(settings, true, size)!!.chain.isNotEmpty())
        assertEquals("", sdrProfile(settings, true, null)!!.chain)
        assertEquals("", sdrProfile(settings, false, size)!!.chain)
        assertEquals("", sdrProfile(settings.copy(desktopAnimeMode = DesktopAnimeMode.Off), true, size)!!.chain)
    }
}
