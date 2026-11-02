package com.nuvio.app.features.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.nuvio.app.core.ui.NuvioTextField
import com.nuvio.app.features.youtubemusic.YouTubeMusicAuth
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.linux_flatpak_youtube_cookie_label
import nuvio.composeapp.generated.resources.linux_flatpak_youtube_invalid
import nuvio.composeapp.generated.resources.linux_flatpak_youtube_paste_hint
import nuvio.composeapp.generated.resources.linux_flatpak_youtube_save
import org.jetbrains.compose.resources.stringResource

/** Where no browser window can be opened: the same session, pasted. The field hides it like a password. */
@Composable
internal fun YouTubeCookiePaste() {
    var text by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    Text(
        text = stringResource(Res.string.linux_flatpak_youtube_paste_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    NuvioTextField(
        value = text,
        onValueChange = {
            text = it
            invalid = false
        },
        modifier = Modifier.fillMaxWidth(),
        placeholder = stringResource(Res.string.linux_flatpak_youtube_cookie_label),
        secret = true,
        isError = invalid,
        supportingText = if (invalid) stringResource(Res.string.linux_flatpak_youtube_invalid) else null,
    )
    Button(
        enabled = text.isNotBlank(),
        onClick = {
            if (YouTubeMusicAuth.signInWithCookie(text)) text = "" else invalid = true
        },
    ) {
        Text(stringResource(Res.string.linux_flatpak_youtube_save))
    }
}
