package com.nuvio.app.features.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
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

/**
 * The horizontal lockup scaled into whatever box the caller provides, for the sign-in page's fixed
 * height budget. The compact Linux asset is a stacked icon-and-wordmark and does not fit a short
 * centred box, so this uses the horizontal bitmap on every platform.
 */
@Composable
internal actual fun CompactAuthWordmark(modifier: Modifier, widthFraction: Float) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Image(
            bitmap = imageResource(Res.drawable.app_logo_wordmark_htpc),
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(widthFraction).fillMaxHeight(),
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.Low,
        )
    }
}
