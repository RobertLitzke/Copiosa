package com.copiosa.ui.audio

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R

/**
 * Shown when the user taps "Send to audio" before the on-device voice has been downloaded. Confirming
 * starts the (one-time, ~100 MB) download and queues the item; it renders once the voice is ready.
 */
@Composable
fun TtsDownloadPrompt(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_tts_prompt_title)) },
        text = { Text(stringResource(R.string.audio_tts_prompt_body)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.audio_download_tts)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.audio_not_now)) }
        },
    )
}
