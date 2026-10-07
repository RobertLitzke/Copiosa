package com.copiosa.ui.books

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.generation.BookAudioPlan
import com.copiosa.ui.audio.listeningTime

/**
 * The "Generate audio for this book?" confirmation.
 *
 * A whole novel is hours of on-device synthesis and hundreds of megabytes, so the numbers are the
 * point of the dialog — this is not a decision to make blind. When there is nothing to generate it
 * degrades to a one-button explanation rather than offering a confirm that would do nothing.
 */
@Composable
fun BookAudioDialog(
    plan: BookAudioPlan,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val actionable = plan.newTracks > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_audio_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    when {
                        actionable -> stringResource(
                            R.string.book_audio_body,
                            plan.newTracks,
                            listeningTime(plan.estimatedMs),
                            Formatter.formatShortFileSize(context, plan.estimatedBytes),
                            plan.tag,
                        )
                        plan.readyChapters == 0 -> stringResource(R.string.book_audio_nothing_ready)
                        else -> stringResource(R.string.book_audio_nothing_new)
                    },
                )
                // Said whenever the frontier is short of the end, including in the "nothing new"
                // case — that is exactly when the learner needs to know more is still coming.
                if (plan.partial && plan.readyChapters > 0) {
                    Text(
                        stringResource(R.string.book_audio_partial, plan.readyChapters, plan.totalChapters),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (actionable) {
                TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_generate_audio)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) }
            }
        },
        dismissButton = {
            if (actionable) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
}
