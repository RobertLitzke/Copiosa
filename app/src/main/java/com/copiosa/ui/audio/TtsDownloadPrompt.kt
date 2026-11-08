package com.copiosa.ui.audio

import androidx.annotation.StringRes
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R

/**
 * Shown when the user taps "Send to audio" before the on-device voice has been downloaded. Confirming
 * starts the (one-time, ~100 MB) download and queues the item; it renders once the voice is ready.
 *
 * [body] is what confirming will do, which is not the same everywhere: a play button queues nothing,
 * so it says to tap again instead.
 */
@Composable
fun TtsDownloadPrompt(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    @StringRes body: Int = R.string.audio_tts_prompt_body,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_tts_prompt_title)) },
        text = { Text(stringResource(body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.audio_download_tts)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.audio_not_now)) }
        },
    )
}
