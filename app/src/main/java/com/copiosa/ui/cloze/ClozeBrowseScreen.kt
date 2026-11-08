package com.copiosa.ui.cloze

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.local.ClozeSentenceEntity
import com.copiosa.domain.model.Cloze
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.theme.SourceSerif

/** The cloze bank as a searchable list; see [ClozeBrowseViewModel]. */
@Composable
fun ClozeBrowseScreen(
    onBack: () -> Unit,
    viewModel: ClozeBrowseViewModel = hiltViewModel(),
) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sentences by viewModel.sentences.collectAsStateWithLifecycle()
    val editRefused by viewModel.editRefused.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ClozeSentenceEntity?>(null) }
    var deleting by remember { mutableStateOf<ClozeSentenceEntity?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.cloze_browse_title), onBack = onBack)
        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            singleLine = true,
            label = { Text(stringResource(R.string.cloze_browse_search)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (sentences.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.cloze_browse_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(sentences, key = { it.id }) { row ->
                SentenceRow(
                    row = row,
                    onPin = { viewModel.togglePinned(row) },
                    onEdit = { editing = row },
                    onSetAside = { viewModel.toggleSetAside(row) },
                    onDelete = { deleting = row },
                )
            }
        }
    }

    editing?.let { row ->
        EditDialog(
            row = row,
            onSave = { sentence, translation -> viewModel.edit(row, sentence, translation); editing = null },
            onDismiss = { editing = null },
        )
    }

    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.cloze_flag_delete)) },
            text = { Text(stringResource(R.string.cloze_browse_delete_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(row); deleting = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    if (editRefused) {
        AlertDialog(
            onDismissRequest = viewModel::consumeEditRefused,
            text = { Text(stringResource(R.string.cloze_browse_edit_refused)) },
            confirmButton = {
                TextButton(onClick = viewModel::consumeEditRefused) { Text(stringResource(R.string.action_done)) }
            },
        )
    }
}

@Composable
private fun SentenceRow(
    row: ClozeSentenceEntity,
    onPin: () -> Unit,
    onEdit: () -> Unit,
    onSetAside: () -> Unit,
    onDelete: () -> Unit,
) {
    val plain = Cloze.answer(row.text)
    val accent = MaterialTheme.colorScheme.primary
    // A sentence set aside is still listed — it is the record of having been thrown out — but dimmed.
    OutlinedCard(Modifier.fillMaxWidth().alpha(if (row.suspended) 0.55f else 1f)) {
        Column(Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    buildAnnotatedString {
                        val at = plain.indexOf(row.answer)
                        if (at < 0) {
                            append(plain)
                        } else {
                            append(plain.substring(0, at))
                            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(row.answer) }
                            append(plain.substring(at + row.answer.length))
                        }
                    },
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = SourceSerif),
                    modifier = Modifier.weight(1f).padding(top = 8.dp),
                )
                IconButton(onClick = onPin) {
                    Icon(
                        if (row.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                        contentDescription = stringResource(if (row.pinned) R.string.cloze_browse_unpin else R.string.cloze_browse_pin),
                        tint = if (row.pinned) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            row.translation?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    sourceLabel(row.source),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
                TextButton(onClick = onSetAside) {
                    Text(stringResource(if (row.suspended) R.string.cloze_flag_restore else R.string.cloze_browse_set_aside))
                }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.cloze_flag_delete)) }
            }
        }
    }
}

/** Where a sentence came from, in words: the slug before the colon names the kind of source. */
@Composable
private fun sourceLabel(source: String): String = stringResource(
    when (source.substringBefore(':')) {
        "book" -> R.string.cloze_source_book
        "text" -> R.string.cloze_source_text
        "chat" -> R.string.cloze_source_chat
        "dict" -> R.string.cloze_source_dict
        "llm" -> R.string.cloze_source_llm
        "legacy" -> R.string.cloze_source_legacy
        else -> R.string.cloze_source_card
    },
)

@Composable
private fun EditDialog(row: ClozeSentenceEntity, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var sentence by remember(row.id) { mutableStateOf(Cloze.answer(row.text)) }
    var translation by remember(row.id) { mutableStateOf(row.translation.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = sentence,
                    onValueChange = { sentence = it },
                    label = { Text(stringResource(R.string.cloze_browse_sentence)) },
                    supportingText = { Text(stringResource(R.string.cloze_browse_keep_answer, row.answer)) },
                )
                OutlinedTextField(
                    value = translation,
                    onValueChange = { translation = it },
                    label = { Text(stringResource(R.string.cloze_browse_translation)) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(sentence, translation) }, enabled = sentence.isNotBlank()) {
                Text(stringResource(R.string.action_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
