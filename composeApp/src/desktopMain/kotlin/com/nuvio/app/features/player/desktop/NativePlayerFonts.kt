package com.nuvio.app.features.player.desktop

/** The HUD uses the same licensed font bytes as Compose, without depending on a system font. */
internal data class NativePlayerFontAsset(val filename: String, val cssWeight: String) {
    val resourcePath: String
        get() = "/composeResources/nuvio.composeapp.generated.resources/font/$filename"
}

internal val nativePlayerFontAssets = listOf(
    NativePlayerFontAsset("inter_regular.ttf", "400"),
    NativePlayerFontAsset("inter_semibold.ttf", "600"),
    NativePlayerFontAsset("inter_bold.ttf", "700 900"),
)

internal fun nativePlayerFontFaces(): String = nativePlayerFontAssets.joinToString("\n") { font ->
    """
        @font-face {
          font-family: "Nuvio Inter";
          src: url("fonts/${font.filename}") format("truetype");
          font-weight: ${font.cssWeight};
          font-style: normal;
          font-display: block;
        }
    """.trimIndent()
}
