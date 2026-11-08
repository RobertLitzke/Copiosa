package com.copiosa.ui.vocab

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.cardjson.CardJsonImportPlan
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.vocab.FrequencySeeder
import com.copiosa.data.vocab.SeedError
import com.copiosa.domain.model.Cloze
import com.copiosa.domain.vocab.freq.FrequencyBucket
import com.copiosa.ui.components.EmptyState
import com.copiosa.ui.components.HeaderSearch
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.components.stripInlineMarkdown
import com.copiosa.ui.theme.SourceSerif
import java.time.LocalDate

/**
 * The Léxico landing screen: a browsable, searchable list of saved vocabulary. Review is a
 * first-class action (with a size picker); the bulk add, the batched "fill explanations", and the
 * Anki and JSON import/export live in the overflow. Tapping a word opens its detail; long-pressing
 * offers freeze/delete.
 */
@Composable
fun VocabScreen(
    onOpenDetail: (Long) -> Unit,
    onOpenReview: (Int) -> Unit,
    onAddImages: () -> Unit,
    viewModel: VocabViewModel = hiltViewModel(),
    bulkAddViewModel: BulkAddViewModel = hiltViewModel(),
) {
    val all by viewModel.all.collectAsStateWithLifecycle()
    val filtered by viewModel.filtered.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val partsOfSpeech by viewModel.partsOfSpeech.collectAsStateWithLifecycle()
    val dueCount by viewModel.dueCount.collectAsStateWithLifecycle()
    val fill by viewModel.fill.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val importState by viewModel.import.collectAsStateWithLifecycle()
    val exportState by viewModel.export.collectAsStateWithLifecycle()
    val jsonExportState by viewModel.jsonExport.collectAsStateWithLifecycle()
    val jsonImportState by viewModel.jsonImport.collectAsStateWithLifecycle()
    val bulkAdded by bulkAddViewModel.added.collectAsStateWithLifecycle()
    val seedState by viewModel.seed.collectAsStateWithLifecycle()
    val ranks by viewModel.ranks.collectAsStateWithLifecycle()
    val hasFrequencyList by viewModel.hasFrequencyList.collectAsStateWithLifecycle()
    val autoAddedCount by viewModel.autoAddedCount.collectAsStateWithLifecycle()
    val clozeConversion by viewModel.clozeConversion.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val clozeConverted by viewModel.clozeConverted.collectAsStateWithLifecycle()

    var searching by remember { mutableStateOf(false) }
    var sizeDialog by remember { mutableStateOf(false) }
    var sortFilterOpen by remember { mutableStateOf(false) }
    var seedDialog by remember { mutableStateOf(false) }
    var bulkAddOpen by remember { mutableStateOf(false) }
    var confirmRemoveAuto by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<FlashcardEntity?>(null) }

    // The picker accepts any file — .apkg has no reliable registered MIME type on Android.
    val pickApkg = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importAnki)
    }
    // SAF "create document" lets the user pick where the exported deck is saved.
    val createApkg = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri -> uri?.let(viewModel::exportAnki) }
    val createJson = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportJson) }
    // Any file again: a .json a file manager or cloud drive hands over is as often text/plain or
    // octet-stream as application/json.
    val pickJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::previewJsonImport)
    }

    AnkiImportDialog(
        state = importState,
        onCancel = viewModel::cancelImport,
        onDismiss = viewModel::consumeImport,
    )
    AnkiExportDialog(
        state = exportState,
        onCancel = viewModel::cancelExport,
        onDismiss = viewModel::consumeExport,
    )
    JsonExportDialog(
        state = jsonExportState,
        onCancel = viewModel::cancelJsonExport,
        onDismiss = viewModel::consumeJsonExport,
    )
    JsonImportDialog(
        state = jsonImportState,
        onConfirm = viewModel::applyJsonImport,
        onCancel = viewModel::cancelJsonImport,
        onDismiss = viewModel::consumeJsonImport,
    )

    // Announced from here rather than from inside the sheet, so a save that finishes after the
    // sheet was dragged away still says what it did instead of waiting for the next time it opens.
    bulkAdded?.let { added ->
        val message = pluralStringResource(R.plurals.bulk_add_done, added, added)
        LaunchedEffect(added) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            bulkAddViewModel.consumeAdded()
            bulkAddOpen = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        VocabHeader(
            count = all.size,
            shown = filtered.size.takeIf { filter.active },
            dueCount = dueCount,
            fill = fill,
            hasPending = all.any { it.explanation.isNullOrBlank() },
            importing = importState.running || jsonImportState.running,
            exporting = exportState.running || jsonExportState.running,
            hasCards = all.isNotEmpty(),
            hasFrequencyList = hasFrequencyList,
            autoAddedCount = autoAddedCount,
            seeding = seedState.running,
            searching = searching,
            query = filter.query,
            markedOnly = filter.markedOnly,
            dueWindow = filter.dueWindow,
            sortFilterActive = sort != VocabSort.NEWEST || filter.sheetFiltersActive,
            onOpenSortFilter = { sortFilterOpen = true },
            onMarkedOnlyChange = viewModel::setMarkedOnly,
            onDueWindowChange = viewModel::setDueWindow,
            onQueryChange = viewModel::setQuery,
            onToggleSearch = {
                searching = !searching
                if (!searching) viewModel.setQuery("")
            },
            onReview = { sizeDialog = true },
            onFill = viewModel::fillExplanations,
            onBulkAdd = { bulkAddOpen = true },
            onAddFrequent = { seedDialog = true },
            onRemoveAuto = { confirmRemoveAuto = true },
            onConvertCloze = viewModel::previewClozeConversion,
            onAddImages = onAddImages,
            onImport = { pickApkg.launch(arrayOf("*/*")) },
            onExport = { createApkg.launch("copiosa.apkg") },
            // Dated, so successive exports sit side by side instead of overwriting each other.
            onExportJson = { createJson.launch("copiosa-cards-${LocalDate.now()}.json") },
            onImportJson = { pickJson.launch(arrayOf("*/*")) },
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
        } else if (filtered.isEmpty()) {
            // A deck with words and a filter that matches none of them: say so, and offer the way out.
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(R.string.vocab_no_matches),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { viewModel.clearFilters(); searching = false }) {
                    Text(stringResource(R.string.filter_reset))
                }
            }
        } else {
            // One clock for the whole list so every row's due label/dot reads against the same moment.
            val now = remember(filtered) { System.currentTimeMillis() }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.id }) { card ->
                    VocabRow(
                        card = card,
                        now = now,
                        rank = ranks[card.id],
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

    if (sortFilterOpen) {
        SortFilterSheet(
            sort = sort,
            filter = filter,
            partsOfSpeech = partsOfSpeech,
            hasFrequencyList = hasFrequencyList,
            onSort = viewModel::setSort,
            onStatus = viewModel::setStatus,
            onPartOfSpeech = viewModel::setPartOfSpeech,
            onExplained = viewModel::setExplained,
            onLeechesOnly = viewModel::setLeechesOnly,
            onReset = viewModel::clearFilters,
            onDismiss = { sortFilterOpen = false },
        )
    }

    if (sizeDialog) {
        ReviewSizeDialog(
            dueCount = dueCount,
            onPick = { size -> sizeDialog = false; onOpenReview(size) },
            onDismiss = { sizeDialog = false },
        )
    }

    if (bulkAddOpen) {
        BulkAddSheet(onDismiss = { bulkAddOpen = false }, viewModel = bulkAddViewModel)
    }

    if (seedDialog) {
        SeedFrequentDialog(
            missingCount = viewModel::missingCount,
            onConfirm = { maxRank, perDay ->
                seedDialog = false
                viewModel.seedFrequentWords(maxRank, perDay)
            },
            onDismiss = { seedDialog = false },
        )
    }

    if (confirmRemoveAuto) {
        AlertDialog(
            onDismissRequest = { confirmRemoveAuto = false },
            title = { Text(stringResource(R.string.freq_remove_title)) },
            text = { Text(stringResource(R.string.freq_remove_message, autoAddedCount)) },
            confirmButton = {
                TextButton(onClick = { confirmRemoveAuto = false; viewModel.removeAutoAdded() }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveAuto = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    clozeConversion?.let { plan ->
        AlertDialog(
            onDismissRequest = viewModel::dismissClozeConversion,
            title = { Text(stringResource(R.string.cloze_convert_title)) },
            text = {
                Text(
                    if (plan.isEmpty) {
                        stringResource(R.string.cloze_convert_none)
                    } else {
                        // The counts are the point: the learner is agreeing to delete cards, and the
                        // skipped tallies are what stop that reading as "some of them just vanished".
                        buildString {
                            append(
                                context.getString(
                                    R.string.cloze_convert_message,
                                    plan.cards.size,
                                    plan.rows.size,
                                    plan.linked,
                                ),
                            )
                            if (plan.skippedMedia > 0) {
                                append("\n\n")
                                append(context.getString(R.string.cloze_convert_skipped_media, plan.skippedMedia))
                            }
                            if (plan.skippedTooManyDeletions > 0) {
                                append("\n\n")
                                append(
                                    context.getString(
                                        R.string.cloze_convert_skipped_many,
                                        plan.skippedTooManyDeletions,
                                    ),
                                )
                            }
                        }
                    },
                )
            },
            confirmButton = {
                if (!plan.isEmpty) {
                    TextButton(onClick = viewModel::convertClozeCards) {
                        Text(stringResource(R.string.cloze_convert_confirm))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClozeConversion) {
                    Text(stringResource(if (plan.isEmpty) R.string.action_ok else R.string.action_cancel))
                }
            },
        )
    }

    clozeConverted?.let { created ->
        AlertDialog(
            onDismissRequest = viewModel::consumeClozeConverted,
            title = { Text(stringResource(R.string.cloze_convert_title)) },
            text = { Text(stringResource(R.string.cloze_convert_done, created)) },
            confirmButton = {
                TextButton(onClick = viewModel::consumeClozeConverted) {
                    Text(stringResource(R.string.action_ok))
                }
            },
        )
    }

    SeedProgressDialog(state = seedState, onStop = viewModel::stopSeed, onDismiss = viewModel::consumeSeed)

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
    shown: Int?,
    dueCount: Int,
    fill: FillState,
    hasPending: Boolean,
    importing: Boolean,
    exporting: Boolean,
    hasCards: Boolean,
    hasFrequencyList: Boolean,
    autoAddedCount: Int,
    seeding: Boolean,
    searching: Boolean,
    query: String,
    markedOnly: Boolean,
    dueWindow: DueWindow,
    sortFilterActive: Boolean,
    onOpenSortFilter: () -> Unit,
    onMarkedOnlyChange: (Boolean) -> Unit,
    onDueWindowChange: (DueWindow) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onReview: () -> Unit,
    onFill: () -> Unit,
    onBulkAdd: () -> Unit,
    onAddFrequent: () -> Unit,
    onRemoveAuto: () -> Unit,
    onConvertCloze: () -> Unit,
    onAddImages: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onExportJson: () -> Unit,
    onImportJson: () -> Unit,
) {
    ScreenHeader(
        title = stringResource(R.string.vocab_title),
        search = HeaderSearch(
            active = searching,
            query = query,
            onQueryChange = onQueryChange,
            onToggle = onToggleSearch,
            hintRes = R.string.vocab_search_hint,
        ),
    ) {
        // Sort order and the finer filters. Up here beside search rather than in the filter row, where
        // it squeezed the due-window toggle until its last label wrapped. Tinted while any of them
        // departs from the default, so a list that looks short is never a mystery.
        if (hasCards) {
            IconButton(onClick = onOpenSortFilter) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = stringResource(R.string.cd_sort_filter),
                    tint = if (sortFilterActive) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                )
            }
        }
        var menuOpen by remember { mutableStateOf(false) }
        // The menu anchors to its *parent layout node*, so it has to share a Box with the button it
        // drops from — as a plain sibling in the header Row it would open from the row's left edge.
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.action_more))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_bulk_add)) },
                    onClick = { menuOpen = false; onBulkAdd() },
                )
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
                // Frequency actions only exist for a language that ships a list; for the rest the
                // vocabulary screen looks exactly as it did before. Drilling those words is not one
                // of them — that is a routine activity, so it is planned into a session rather than
                // launched from a menu.
                if (hasFrequencyList) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_add_frequent)) },
                        enabled = !seeding,
                        onClick = { menuOpen = false; onAddFrequent() },
                    )
                    // Only offered once there is something to undo, so the menu stays short for the
                    // common case of a deck nobody has seeded.
                    if (autoAddedCount > 0) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.action_remove_auto, autoAddedCount)) },
                            enabled = !seeding,
                            onClick = { menuOpen = false; onRemoveAuto() },
                        )
                    }
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_add_images)) },
                    enabled = hasCards,
                    onClick = { menuOpen = false; onAddImages() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_convert_cloze)) },
                    enabled = hasCards,
                    onClick = { menuOpen = false; onConvertCloze() },
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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_export_json)) },
                    enabled = !exporting && hasCards,
                    onClick = { menuOpen = false; onExportJson() },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_import_json)) },
                    enabled = !importing,
                    onClick = { menuOpen = false; onImportJson() },
                )
            }
        }
    }
    Row(
        modifier = Modifier.padding(start = 20.dp, end = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        CountPill(count, shown)
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

/**
 * Sort order and the filters that don't earn a place in the header: card status, part of speech, and
 * whether the word has an LLM explanation yet. Changes apply at once, so the list behind the sheet
 * shows what each choice does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortFilterSheet(
    sort: VocabSort,
    filter: VocabFilter,
    partsOfSpeech: List<String>,
    hasFrequencyList: Boolean,
    onSort: (VocabSort) -> Unit,
    onStatus: (CardStatus?) -> Unit,
    onPartOfSpeech: (String?) -> Unit,
    onExplained: (ExplainedFilter) -> Unit,
    onLeechesOnly: (Boolean) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SheetLabel(R.string.sort_label)
            ChipRow {
                items(VocabSort.entries.filter { it != VocabSort.MOST_COMMON || hasFrequencyList }) { option ->
                    FilterChip(
                        selected = sort == option,
                        onClick = { onSort(option) },
                        label = { Text(stringResource(option.labelRes())) },
                    )
                }
            }

            SheetLabel(R.string.filter_status)
            ChipRow {
                item { FilterChip(filter.status == null, { onStatus(null) }, { Text(stringResource(R.string.filter_any)) }) }
                items(STATUS_FILTER_ORDER) { status ->
                    FilterChip(
                        selected = filter.status == status,
                        onClick = { onStatus(if (filter.status == status) null else status) },
                        label = { Text(status.label()) },
                    )
                }
            }

            // Not a status: a leech is a word at any of the stages above that keeps falling back.
            SheetLabel(R.string.filter_leeches)
            ChipRow {
                item { FilterChip(!filter.leechesOnly, { onLeechesOnly(false) }, { Text(stringResource(R.string.filter_any)) }) }
                item {
                    FilterChip(
                        selected = filter.leechesOnly,
                        onClick = { onLeechesOnly(!filter.leechesOnly) },
                        label = { Text(stringResource(R.string.filter_leeches_only)) },
                    )
                }
            }

            if (partsOfSpeech.isNotEmpty()) {
                SheetLabel(R.string.filter_pos)
                ChipRow {
                    item { FilterChip(filter.partOfSpeech == null, { onPartOfSpeech(null) }, { Text(stringResource(R.string.filter_any)) }) }
                    items(partsOfSpeech) { pos ->
                        FilterChip(
                            selected = filter.partOfSpeech == pos,
                            onClick = { onPartOfSpeech(if (filter.partOfSpeech == pos) null else pos) },
                            label = { Text(posLabel(pos)) },
                        )
                    }
                }
            }

            SheetLabel(R.string.filter_explained)
            ChipRow {
                items(ExplainedFilter.entries) { option ->
                    FilterChip(
                        selected = filter.explained == option,
                        onClick = { onExplained(option) },
                        label = {
                            Text(
                                stringResource(
                                    when (option) {
                                        ExplainedFilter.ANY -> R.string.filter_any
                                        ExplainedFilter.EXPLAINED -> R.string.filter_explained_yes
                                        ExplainedFilter.UNEXPLAINED -> R.string.filter_explained_no
                                    },
                                ),
                            )
                        },
                    )
                }
            }

            TextButton(
                onClick = onReset,
                enabled = filter.active,
                modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
            ) { Text(stringResource(R.string.filter_reset)) }
        }
    }
}

@Composable
private fun SheetLabel(@StringRes text: Int) {
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 24.dp),
    )
}

/** One scrolling line of chips, so a long set (every part of speech) never wraps the sheet taller. */
@Composable
private fun ChipRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** The status filter's chips in the order a word moves through them, with the set-aside ones last. */
private val STATUS_FILTER_ORDER =
    listOf(CardStatus.NEW, CardStatus.LEARNING, CardStatus.YOUNG, CardStatus.MATURE, CardStatus.FROZEN)

@StringRes
private fun VocabSort.labelRes(): Int = when (this) {
    VocabSort.NEWEST -> R.string.sort_newest
    VocabSort.OLDEST -> R.string.sort_oldest
    VocabSort.ALPHABETICAL -> R.string.sort_alphabetical
    VocabSort.DUE_SOONEST -> R.string.sort_due
    VocabSort.MOST_LAPSED -> R.string.sort_lapsed
    VocabSort.MOST_COMMON -> R.string.sort_common
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

/**
 * Picks how deep into the frequency list to seed from, and how fast the new words should arrive.
 *
 * The missing count is looked up per scope rather than computed for all of them up front: it means a
 * database read on each tap, but the alternative is five reads before the dialog can appear.
 */
@Composable
private fun SeedFrequentDialog(
    missingCount: suspend (Int) -> Int,
    onConfirm: (maxRank: Int, perDay: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var scope by remember { mutableStateOf(FrequencyBucket.TOP_500) }
    var perDay by remember { mutableIntStateOf(FrequencySeeder.DEFAULT_PER_DAY) }
    var missing by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(scope) {
        missing = null
        missing = missingCount(scope.maxRank)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.freq_seed_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.freq_seed_scope), style = MaterialTheme.typography.labelLarge)
                SegmentedToggle(
                    selected = FrequencyBucket.entries.indexOf(scope),
                    labels = FrequencyBucket.entries.map { it.pickerLabel() },
                    onSelect = { scope = FrequencyBucket.entries[it] },
                )
                Text(stringResource(R.string.freq_seed_rate), style = MaterialTheme.typography.labelLarge)
                SegmentedToggle(
                    selected = PER_DAY_OPTIONS.indexOf(perDay).coerceAtLeast(0),
                    labels = PER_DAY_OPTIONS.map { "$it" },
                    onSelect = { perDay = PER_DAY_OPTIONS[it] },
                )
                Text(
                    when (val n = missing) {
                        null -> stringResource(R.string.freq_seed_progress, 0, 0)
                        0 -> stringResource(R.string.freq_seed_none)
                        else -> stringResource(R.string.freq_seed_count, n)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.freq_seed_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = (missing ?: 0) > 0,
                onClick = { onConfirm(scope.maxRank, perDay) },
            ) {
                Text(stringResource(R.string.freq_seed_action, missing ?: 0))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private val PER_DAY_OPTIONS = listOf(10, 20, 50)

/**
 * Seeding progress, then its outcome. Mid-run only the button ends it, and it says Stop rather than
 * Cancel: the cards counted so far are made and stay made.
 */
@Composable
private fun SeedProgressDialog(state: SeedState, onStop: () -> Unit, onDismiss: () -> Unit) {
    when {
        state.running -> AlertDialog(
            onDismissRequest = { /* only Stop ends a run */ },
            confirmButton = { TextButton(onClick = onStop) { Text(stringResource(R.string.action_stop)) } },
            title = { Text(stringResource(R.string.freq_seed_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.freq_seed_progress, state.done, state.total))
                }
            },
        )
        state.removed != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.freq_remove_title)) },
            text = { Text(stringResource(R.string.freq_remove_done, state.removed)) },
        )
        state.summary != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.freq_seed_done_title)) },
            text = {
                Text(
                    if (state.summary.untranslated > 0) {
                        stringResource(
                            R.string.freq_seed_done_untranslated,
                            state.summary.created,
                            state.summary.untranslated,
                        )
                    } else {
                        stringResource(R.string.freq_seed_done, state.summary.created)
                    },
                )
            },
        )
        state.error != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.freq_seed_failed_title)) },
            text = {
                Text(
                    stringResource(
                        when (state.error) {
                            SeedError.Unsupported, SeedError.NoList -> R.string.freq_seed_failed_unsupported
                            SeedError.Unavailable -> R.string.freq_seed_failed_unavailable
                        },
                    ),
                )
            },
        )
    }
}

/** The Anki import's progress and outcome. Mid-run only the Cancel button closes it, not a stray tap. */
@Composable
private fun AnkiImportDialog(state: ImportState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when {
        state.running -> AlertDialog(
            onDismissRequest = { /* only Cancel stops an import */ },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.import_running)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.vocab_title))
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
private fun AnkiExportDialog(state: ExportState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when {
        state.running -> AlertDialog(
            onDismissRequest = { /* only Cancel stops an export */ },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.export_running)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.vocab_title))
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

/** The JSON export's progress and outcome, in the same three states as the Anki one. */
@Composable
private fun JsonExportDialog(state: JsonExportState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    when {
        state.running -> AlertDialog(
            onDismissRequest = { /* only Cancel stops an export */ },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.export_running)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.vocab_title))
                }
            },
        )
        state.count != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.export_done_title)) },
            text = { Text(pluralStringResource(R.plurals.json_export_done, state.count, state.count)) },
        )
        state.error != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.export_failed_title)) },
            text = { Text(state.error) },
        )
    }
}

/**
 * The JSON import: a spinner while the file is read or written, then the plan to agree to, then what
 * was done. The plan step is the point of the dialog — the file was edited elsewhere, so the counts
 * are shown before a single card changes.
 */
@Composable
private fun JsonImportDialog(
    state: JsonImportState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    when {
        state.running -> AlertDialog(
            onDismissRequest = { /* only Cancel stops an import */ },
            confirmButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) } },
            title = { Text(stringResource(R.string.json_import_title)) },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.vocab_title))
                }
            },
        )
        state.plan != null -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.json_import_title)) },
            text = { Text(jsonImportPlanText(state.plan)) },
            confirmButton = {
                if (!state.plan.isEmpty) {
                    TextButton(onClick = onConfirm) { Text(stringResource(R.string.json_import_confirm)) }
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(if (state.plan.isEmpty) R.string.action_ok else R.string.action_cancel))
                }
            },
        )
        state.summary != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.import_done_title)) },
            text = { Text(stringResource(R.string.json_import_done, state.summary.updated, state.summary.added)) },
        )
        state.error != null -> AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) } },
            title = { Text(stringResource(R.string.import_failed_title)) },
            text = { Text(state.error) },
        )
    }
}

/** A plan in words: what will change first, then each kind of entry that is being passed over. */
@Composable
private fun jsonImportPlanText(plan: CardJsonImportPlan): String {
    val lines = ArrayList<String>()
    if (plan.isEmpty) lines += stringResource(R.string.json_import_nothing)
    if (plan.updates.isNotEmpty()) {
        lines += pluralStringResource(R.plurals.json_import_updates, plan.updates.size, plan.updates.size)
    }
    if (plan.additions.isNotEmpty()) {
        lines += pluralStringResource(R.plurals.json_import_additions, plan.additions.size, plan.additions.size)
    }
    if (plan.unchanged > 0) {
        lines += pluralStringResource(R.plurals.json_import_unchanged, plan.unchanged, plan.unchanged)
    }
    if (plan.unmatched > 0) {
        lines += pluralStringResource(R.plurals.json_import_unmatched, plan.unmatched, plan.unmatched)
    }
    if (plan.duplicates > 0) {
        lines += pluralStringResource(R.plurals.json_import_duplicates, plan.duplicates, plan.duplicates)
    }
    if (plan.invalid > 0) {
        lines += pluralStringResource(R.plurals.json_import_invalid, plan.invalid, plan.invalid)
    }
    if (!plan.isEmpty) lines += stringResource(R.string.json_import_keeps)
    return lines.joinToString("\n\n")
}

@Composable
private fun CountPill(count: Int, shown: Int?) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            if (shown == null) stringResource(R.string.vocab_count, count)
            else stringResource(R.string.vocab_count_filtered, shown, count),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/**
 * The two lines a list row shows for [card]. A cloze card stores raw `{{c1::…}}` markup, so it shows
 * a readable blanked sentence and the answer; any other card's headword and translation are plain
 * words, so stored Markdown is stripped to keep the rows clean.
 */
private fun rowText(card: FlashcardEntity): Pair<String, String> {
    val clozeText = Cloze.source(card.front, card.back)
    return if (clozeText != null) {
        val activeOrd = card.ankiOrd?.plus(1)
        Cloze.question(clozeText, activeOrd) to Cloze.activeAnswers(clozeText, activeOrd).joinToString(", ")
    } else {
        stripInlineMarkdown(card.front) to stripInlineMarkdown(card.back)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VocabRow(
    card: FlashcardEntity,
    now: Long,
    rank: Int?,
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
            // What the row says is a function of the card's text alone, and a list of a few thousand
            // rows recomposes on every scroll and filter change, so it is worked out once per text.
            val (titleText, subtitleText) = remember(card.front, card.back, card.ankiOrd) { rowText(card) }
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
            if (rank != null) {
                Spacer(Modifier.width(8.dp))
                FreqChip(rank)
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
