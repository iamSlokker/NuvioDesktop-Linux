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
import nuvio.composeapp.generated.resources.app_logo_wordmark_login_compact
import org.jetbrains.compose.resources.imageResource
import org.jetbrains.compose.resources.painterResource

@Composable
internal actual fun AuthWordmark(modifier: Modifier) =
    DesktopAuthWordmark(DesktopHostOs.current, modifier)

@Composable
internal fun DesktopAuthWordmark(os: DesktopHostOs, modifier: Modifier = Modifier) {
    if (os == DesktopHostOs.LINUX) {
        val wordmark = painterResource(Res.drawable.app_logo_wordmark_login_compact)
        Image(
            painter = wordmark,
            contentDescription = null,
            modifier = modifier.fillMaxWidth(0.6f)
                .aspectRatio(wordmark.intrinsicSize.width / wordmark.intrinsicSize.height),
            contentScale = ContentScale.Fit,
        )
        return
    }
    Image(
        bitmap = imageResource(Res.drawable.app_logo_wordmark_htpc),
        contentDescription = null,
        modifier = modifier.fillMaxWidth(0.6f).height(48.dp),
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Low,
    )
}
