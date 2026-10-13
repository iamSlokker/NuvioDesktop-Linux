package com.nuvio.app.features.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.nuvio.app.features.player.desktop.DesktopHostOs
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.app_logo_wordmark_htpc
import org.jetbrains.compose.resources.imageResource

@Composable
internal actual fun AuthWordmark(modifier: Modifier) =
    DesktopAuthWordmark(DesktopHostOs.current, modifier)

@Composable
internal fun DesktopAuthWordmark(os: DesktopHostOs, modifier: Modifier = Modifier) {
    val bitmap = imageResource(Res.drawable.app_logo_wordmark_htpc)
    val linux = os == DesktopHostOs.LINUX
    Image(
        bitmap = bitmap,
        contentDescription = null,
        modifier = modifier.fillMaxWidth(0.6f).then(
            // A 48dp height reduced the 1085x344 image to only 151x48dp inside a
            // 276dp-wide slot. Let the fine-detail icon use that slot, without stretching it.
            if (linux) Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)
            else Modifier.height(48.dp),
        ),
        contentScale = ContentScale.Fit,
        // Mipmapped minification retains the dark icon's fine stars without point-sampling them.
        filterQuality = if (linux) FilterQuality.Medium else FilterQuality.Low,
    )
}
