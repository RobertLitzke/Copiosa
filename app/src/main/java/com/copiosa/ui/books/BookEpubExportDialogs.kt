package com.copiosa.ui.books

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.epub.BookExportRun
import com.copiosa.data.epub.BookExportState
import com.copiosa.data.epub.EpubExportVariant
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.SegmentedToggle

/**
 * The whole export flow's UI: pick a variant, pick a destination, watch it run.
 *
 * One composable rather than one per screen, because the reader's overflow menu and the library
 * row's long-press menu both open it — so the `CreateDocument` contract, the MIME type and the
 * variant-to-filename mapping exist in exactly one place. Each screen supplies its own state flows;
 * only the ~20 lines of ViewModel plumbing differ, exactly as they already do for the download-mode
 * dialog.
 */
@Composable
fun BookEpubExportDialogs(
    /** Non-null while the variant picker is open. */
    state: BookExportState?,
    run: BookExportRun,
    onExport: (android.net.Uri, EpubExportVariant) -> Unit,
    onDismissPicker: () -> Unit,
    onCancelRun: () -> Unit,
    onConsumeRun: () -> Unit,
) {
    // Held across the trip out to the system file picker, which can only carry a filename.
    var pending by remember { mutableStateOf(EpubExportVariant.TARGET_ONLY) }
    val createEpub = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip"),
    ) { uri -> uri?.let { onExport(it, pending) } }

    if (state != null) {
        ExportVariantDialog(
            state = state,
            onConfirm = { variant ->
                pending = variant
                onDismissPicker()
                createEpub.launch(state.suggestedFileName(variant))
            },
            onDismiss = onDismissPicker,
        )
    }

    ExportProgressDialog(run = run, onCancel = onCancelRun, onDismiss = onConsumeRun)
}

@Composable
private fun ExportVariantDialog(
    state: BookExportState,
    onConfirm: (EpubExportVariant) -> Unit,
    onDismiss: () -> Unit,
) {
    val targetName = languageDisplayName(state.targetLanguage)
    val nativeName = languageDisplayName(state.nativeLanguage)
    var variant by remember { mutableStateOf(EpubExportVariant.TARGET_ONLY) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_export_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel(stringResource(R.string.book_export_q))
                // A book without both languages end to end has nothing to choose between, so the
                // toggle is left out rather than shown with a dead half.
                if (state.bilingualAvailable) {
                    SegmentedToggle(
                        selected = if (variant == EpubExportVariant.TARGET_ONLY) 0 else 1,
                        labels = listOf(
                            stringResource(R.string.book_export_variant_target, targetName),
                            stringResource(R.string.book_export_variant_bilingual, targetName, nativeName),
                        ),
                        onSelect = {
                            variant = if (it == 0) {
                                EpubExportVariant.TARGET_ONLY
                            } else {
                                EpubExportVariant.BILINGUAL
                            }
                        },
                    )
                }
                Text(
                    when (variant) {
                        EpubExportVariant.TARGET_ONLY ->
                            stringResource(R.string.book_export_variant_target_note)
                        EpubExportVariant.BILINGUAL ->
                            stringResource(R.string.book_export_variant_bilingual_note, nativeName)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(variant) }) {
                Text(stringResource(R.string.book_export_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Running / done / failed, the same three states the Anki deck export reports. */
@Composable
private fun ExportProgressDialog(run: BookExportRun, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when {
        run.running -> AlertDialog(
            onDismissRequest = { /* only Cancel stops an export */ },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.book_export_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.book_export_running))
                }
            },
        )
        run.summary != null -> {
            val summary = run.summary
            AlertDialog(
                onDismissRequest = onDismiss,
                confirmButton = {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
                },
                title = { Text(stringResource(R.string.book_export_done_title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(
                                R.string.book_export_done,
                                summary.chapters,
                                summary.paragraphs,
                            ),
                        )
                        // Say so rather than let the file quietly not be all target-language.
                        if (summary.untranslated > 0) {
                            Text(
                                pluralStringResource(
                                    R.plurals.book_export_untranslated,
                                    summary.untranslated,
                                    summary.untranslated,
                                    languageDisplayName(summary.nativeLanguage),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            )
        }
        run.error != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
            },
            title = { Text(stringResource(R.string.book_export_failed_title)) },
            text = { Text(stringResource(R.string.book_export_failed, run.error)) },
        )
    }
}
