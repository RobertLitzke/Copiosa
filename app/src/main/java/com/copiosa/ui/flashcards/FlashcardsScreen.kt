package com.copiosa.ui.flashcards

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.Cloze
import com.copiosa.ui.components.EmptyState
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.theme.SourceSerif

/**
 * The Léxico landing screen: a browsable, searchable list of saved vocabulary. Review is a
 * first-class action (with a size picker); the batched "fill explanations" and Anki import live in
 * the overflow. Tapping a word opens its detail; long-pressing offers freeze/delete.
 */
@Composable
fun FlashcardsScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenReview: (Int) -> Unit,
    onAddImages: () -> Unit,
    viewModel: FlashcardsViewModel = hiltViewModel(),
) {
    val all by viewModel.all.collectAsState()
    val filtered by viewModel.filtered.collectAsState()
    val query by viewModel.query.collectAsState()
    val markedOnly by viewModel.markedOnly.collectAsState()
    val dueWindow by viewModel.dueWindow.collectAsState()
    val due by viewModel.due.collectAsState()
    val fill by viewModel.fill.collectAsState()
    val error by viewModel.error.collectAsState()
    val importState by viewModel.import.collectAsState()
    val exportState by viewModel.export.collectAsState()

    var searching by remember { mutableStateOf(false) }
    var sizeDialog by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FlashcardEntity?>(null) }

    // The picker accepts any file — .apkg has no reliable registered MIME type on Android.
    val pickApkg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importAnki)
    }
    // SAF "create document" lets the user pick where the exported deck is saved.
    val createApkg = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(viewModel::exportAnki) }

    AnkiImportDialog(state = importState, onDismiss = viewModel::consumeImport)
    AnkiExportDialog(state = exportState, onDismiss = viewModel::consumeExport)

    Column(modifier = Modifier.fillMaxSize()) {
        VocabHeader(
            count = all.size,
            dueCount = due.size,
            fill = fill,
            hasPending = all.any { it.explanation.isNullOrBlank() },
            importing = importState.running,
            exporting = exportState.running,
            hasCards = all.isNotEmpty(),
            searching = searching,
            query = query,
            markedOnly = markedOnly,
            dueWindow = dueWindow,
            onMarkedOnlyChange = viewModel::setMarkedOnly,
            onDueWindowChange = viewModel::setDueWindow,
            onQueryChange = viewModel::setQuery,
            onToggleSearch = {
                searching = !searching
                if (!searching) viewModel.setQuery("")
            },
            onReview = { sizeDialog = true },
            onFill = viewModel::fillExplanations,
            onAddImages = onAddImages,
            onImport = { pickApkg.launch(arrayOf("*/*")) },
            onExport = { createApkg.launch("copiosa.apkg") },
        )
        error?.let {
            Text(
                llmErrorText(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }
        if (all.isEmpty()) {
            EmptyState(stringResource(R.string.vocab_empty))
        } else {
            // One clock for the whole list so every row's due label/dot reads against the same moment.
            val now = remember(filtered) { System.currentTimeMillis() }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { card ->
                    VocabRow(
                        card = card,
                        now = now,
                        onClick = { onOpenDetail(card.id) },
                        onToggleFreeze = { viewModel.setSuspended(card, !card.suspended) },
                        onDelete = { pendingDelete = card },
                    )
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 36.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }

    if (sizeDialog) {
        ReviewSizeDialog(
            dueCount = due.size,
            onPick = { size -> sizeDialog = false; onOpenReview(size) },
            onDismiss = { sizeDialog = false },
        )
    }

    pendingDelete?.let { card ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.vocab_delete)) },
            text = { Text(stringResource(R.string.vocab_delete_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(card); pendingDelete = null }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun VocabHeader(
    count: Int,
    dueCount: Int,
    fill: FillState,
    hasPending: Boolean,
    importing: Boolean,
    exporting: Boolean,
    hasCards: Boolean,
    searching: Boolean,
    query: String,
    markedOnly: Boolean,
    dueWindow: DueWindow,
    onMarkedOnlyChange: (Boolean) -> Unit,
    onDueWindowChange: (DueWindow) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onReview: () -> Unit,
    onFill: () -> Unit,
    onAddImages: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = { Text(stringResource(R.string.vocab_search_hint)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleSearch) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_search))
            }
        } else {
            Text(
                stringResource(R.string.flashcards_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onToggleSearch) {
                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.action_search))
            }
            var menuOpen by remember { mutableStateOf(false) }
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = {
                        Text(
                            if (fill.running) stringResource(R.string.fill_progress, fill.done, fill.total)
                            else stringResource(R.string.action_fill_explanations),
                        )
                    },
                    enabled = !fill.running && hasPending,
                    onClick = { menuOpen = false; onFill() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_add_images)) },
                    enabled = hasCards,
                    onClick = { menuOpen = false; onAddImages() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_import_anki)) },
                    enabled = !importing,
                    onClick = { menuOpen = false; onImport() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_export_anki)) },
                    enabled = !exporting && hasCards,
                    onClick = { menuOpen = false; onExport() },
                )
}
        }
    }
    Row(
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CountPill(count)
        if (fill.running) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.fill_progress, fill.done, fill.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        FilledTonalButton(onClick = onReview) {
            Icon(Icons.Filled.School, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (dueCount > 0) stringResource(R.string.tab_review) + " ($dueCount)"
                else stringResource(R.string.tab_review),
            )
        }
    }
    if (hasCards) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Star toggle: show only marked cards.
            IconButton(onClick = { onMarkedOnlyChange(!markedOnly) }) {
                Icon(
                    if (markedOnly) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(R.string.filter_marked),
                    tint = if (markedOnly) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Due-window selector: all / due within a day / due within a week.
            SegmentedToggle(
                selected = dueWindow.ordinal,
                labels = listOf(
                    stringResource(R.string.filter_due_all),
                    stringResource(R.string.filter_due_today),
                    stringResource(R.string.filter_due_week),
                ),
                onSelect = { onDueWindowChange(DueWindow.entries[it]) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Picks how many cards a review session should contain. */
@Composable
private fun ReviewSizeDialog(dueCount: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.review_size_title)) },
        text = {
            Column {
                listOf(20, 50, 100).forEach { size ->
                    DropdownMenuItem(text = { Text("$size") }, onClick = { onPick(size) })
                }
                if (dueCount > 0) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.review_size_all, dueCount)) },
                        onClick = { onPick(dueCount) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun AnkiImportDialog(state: ImportState, onDismiss: () -> Unit) {
    when {
        state.running -> AlertDialog(
            onDismissRequest = { /* not dismissable mid-import */ },
            confirmButton = {},
            title = { Text(stringResource(R.string.import_running)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.flashcards_title))
                }
            },
        )
        state.summary != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.import_done_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.import_done_message,
                        state.summary.newCards,
                        state.summary.updatedCards,
                        state.summary.mediaFiles,
                    ),
                )
            },
        )
        state.error != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.import_failed_title)) },
            text = { Text(state.error) },
        )
    }
}

@Composable
private fun AnkiExportDialog(state: ExportState, onDismiss: () -> Unit) {
    when {
        state.running -> AlertDialog(
            onDismissRequest = { /* not dismissable mid-export */ },
            confirmButton = {},
            title = { Text(stringResource(R.string.export_running)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.flashcards_title))
                }
            },
        )
        state.summary != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.export_done_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.export_done_message,
                        state.summary.cards,
                        state.summary.reviews,
                        state.summary.mediaFiles,
                    ),
                )
            },
        )
        state.error != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.export_failed_title)) },
            text = { Text(state.error) },
        )
    }
}

@Composable
private fun CountPill(count: Int) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            stringResource(R.string.vocab_count, count),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VocabRow(
    card: FlashcardEntity,
    now: Long,
    onClick: () -> Unit,
    onToggleFreeze: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val frozen = card.suspended
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menuOpen = true })
                .padding(horizontal = 20.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading dot — colored by how soon the card is due (frozen / new / due / upcoming).
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dueColor(card, now)),
            )
            Spacer(Modifier.width(8.dp))
            // Cloze cards store raw `{{c1::…}}` markup; show a readable blanked sentence + the answer.
            val clozeText = Cloze.source(card.front, card.back)
            val titleText: String
            val subtitleText: String
            if (clozeText != null) {
                val activeOrd = card.ankiOrd?.plus(1)
                titleText = Cloze.question(clozeText, activeOrd)
                subtitleText = Cloze.activeAnswers(clozeText, activeOrd).joinToString(", ")
            } else {
                titleText = card.front
                subtitleText = card.back
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    titleText,
                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = SourceSerif),
                    fontWeight = FontWeight.SemiBold,
                    color = if (frozen) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitleText,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SourceSerif),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            card.partOfSpeech?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.width(12.dp))
                PosChip(it)
            }
            dueLabel(card, now)?.let {
                Spacer(Modifier.width(12.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (frozen) R.string.action_unfreeze else R.string.action_freeze)) },
                leadingIcon = { Icon(Icons.Filled.AcUnit, contentDescription = null) },
                onClick = { menuOpen = false; onToggleFreeze() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                onClick = { menuOpen = false; onDelete() },
            )
        }
    }
}
