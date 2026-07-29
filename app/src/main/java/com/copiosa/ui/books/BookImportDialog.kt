package com.copiosa.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.epub.ImportOptions
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.Cefr
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.SegmentedToggle
import java.util.Locale

/**
 * Asked once, before an EPUB is parsed: which language the book is already in, and — for a book
 * that needs translating *into* the target language — at what level.
 *
 * It runs before parsing rather than after because neither question needs the book's metadata, and
 * doing it this way keeps [com.copiosa.data.epub.EpubImportService] a single straight-through
 * operation with nothing staged across a dialog.
 */
@Composable
fun BookImportDialog(
    defaults: BooksEntryViewModel.ImportDefaults,
    onConfirm: (ImportOptions) -> Unit,
    onDismiss: () -> Unit,
) {
    var direction by remember { mutableStateOf(BookDirection.FORWARD) }
    var level by remember { mutableStateOf(defaults.level) }
    var translateAll by remember { mutableStateOf(false) }

    val nativeName = languageDisplayName(defaults.nativeLanguage)
    val targetName = languageDisplayName(defaults.targetLanguage)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_import_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel(stringResource(R.string.book_import_language_q))
                SegmentedToggle(
                    // Forward first: it is both the historical behaviour and the safer default —
                    // reading a book you cannot yet read is the harder mistake to recover from.
                    selected = if (direction == BookDirection.FORWARD) 0 else 1,
                    labels = listOf(nativeName, targetName),
                    onSelect = {
                        direction = if (it == 0) BookDirection.FORWARD else BookDirection.REVERSE
                    },
                )
                when (direction) {
                    BookDirection.FORWARD -> {
                        SectionLabel(stringResource(R.string.book_import_level, targetName))
                        CefrPills(selected = level, onSelect = { level = it })
                    }
                    // Nothing to grade — the book is already the text being studied. The only
                    // choice left is whether to pay for a native gloss beside it.
                    BookDirection.REVERSE -> {
                        Text(
                            stringResource(R.string.book_import_reverse_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Checkbox(checked = translateAll, onCheckedChange = { translateAll = it })
                            Text(
                                stringResource(R.string.book_import_translate_all, nativeName),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        ImportOptions(
                            direction = direction,
                            level = level,
                            translateAll = direction == BookDirection.REVERSE && translateAll,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.book_import_action)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/** Tappable CEFR level pills (A1–C2); the selected one is filled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CefrPills(selected: Cefr, onSelect: (Cefr) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Cefr.entries.forEach { lvl ->
            FilterChip(
                selected = lvl == selected,
                onClick = { onSelect(lvl) },
                label = { Text(lvl.name) },
            )
        }
    }
}

private fun languageDisplayName(tag: String): String =
    Locale.forLanguageTag(tag).displayLanguage.replaceFirstChar { it.uppercase() }.ifBlank { tag }
