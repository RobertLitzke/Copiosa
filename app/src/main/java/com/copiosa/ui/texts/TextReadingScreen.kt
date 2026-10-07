package com.copiosa.ui.texts

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.ui.components.ConfirmDeleteDialog
import com.copiosa.ui.components.Prompt
import com.copiosa.ui.components.ReaderTranslationCard
import com.copiosa.ui.components.SavedNotice
import com.copiosa.ui.components.SavedWordsSheet
import com.copiosa.ui.components.rememberAudioRequestGate
import com.copiosa.ui.components.rememberReadModeState
import com.copiosa.ui.components.rememberScrollRestore
import kotlinx.coroutines.flow.distinctUntilChanged
import com.copiosa.R
import com.copiosa.data.article.ArticleExtractor
import com.copiosa.data.local.ConversationEntity
import com.copiosa.domain.reading.BilingualBlocks
import com.copiosa.domain.reading.ReadMode
import com.copiosa.domain.reading.canStep
import com.copiosa.domain.reading.stepped
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.audio.TtsDownloadPrompt
import com.copiosa.ui.components.BilingualBlockRow
import com.copiosa.ui.components.CefrBadge
import com.copiosa.ui.components.HighlightWordsMenuItem
import com.copiosa.ui.components.ReadingProgressRule
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.components.SubordinateGloss
import com.copiosa.ui.components.TrackFocusTime
import com.copiosa.ui.components.TranslationCard
import com.copiosa.ui.components.WordColorizer
import com.copiosa.ui.components.correspondenceTint
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.components.readFraction
import com.copiosa.ui.components.readingModeSwipe
import com.copiosa.ui.components.rememberCorrespondenceTint
import com.copiosa.ui.components.rememberReadingSelectionSpan
import com.copiosa.ui.theme.SerifTitle
import kotlinx.coroutines.delay

/**
 * The article reader, built on the same bilingual row model as the book reader: one LazyColumn over
 * paragraph rows that each carry both languages, so a Translation/Original/Both switch swaps text on
 * the *same* items and the paragraph being read keeps its place. Rows are anchored on the source
 * paragraphs (see [BilingualBlocks.align]) — that side is complete from import, so the list doesn't
 * shift under the reader as translation chunks land, and the untranslated tail reads as pending
 * rather than as an article that stops early.
 *
 * On a switch, rows near the viewport pick up the correspondence tint, cycled by row index, so the
 * matching paragraph is easy to find in the other language; in Both mode it stays lit, since there
 * it is what pairs the two.
 */
@Composable
fun TextReadingScreen(
    onBack: () -> Unit,
    onDiscuss: () -> Unit,
    onOpenVocab: (Long) -> Unit,
    viewModel: TextReadingViewModel = hiltViewModel(),
) {
    val conversation by viewModel.conversation.collectAsStateWithLifecycle()
    val translation by viewModel.translation.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val reading = viewModel.readingSelection
    val highlightTargets by reading.highlightTargets.collectAsStateWithLifecycle()
    val translate by reading.translate.collectAsStateWithLifecycle()
    val savedNotice by reading.savedNotice.collectAsStateWithLifecycle()
    val starred by viewModel.starred.collectAsStateWithLifecycle()
    val highlightWords by viewModel.highlightWords.collectAsStateWithLifecycle()
    val savedScroll by viewModel.savedScroll.collectAsStateWithLifecycle()
    val savedMode by viewModel.savedMode.collectAsStateWithLifecycle()
    val savedWords by viewModel.savedWords.collectAsStateWithLifecycle()

    TrackFocusTime(FocusActivityType.READING, conversation?.targetLanguage)

    // One selection across the whole article, so a drag can run from one paragraph into the next.
    val selection = rememberReadingSelectionSpan()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showSavedWords by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val addedToAudio = stringResource(R.string.audio_added)
    val nothingToAdd = stringResource(R.string.audio_nothing_to_add)
    // Says what happened rather than what was asked for: nothing is queued before the translation lands.
    val notifyAudio: (queued: Boolean) -> Unit = { queued ->
        Toast.makeText(context, if (queued) addedToAudio else nothingToAdd, Toast.LENGTH_SHORT).show()
    }
    val audioGate = rememberAudioRequestGate(viewModel::isTtsReady, viewModel::startTtsDownload)

    val complete = state is ReadingState.Ready
    val original = conversation?.originalText.orEmpty()
    val translationBlocks = remember(translation) { BilingualBlocks.split(translation) }
    val originalBlocks = remember(original) { BilingualBlocks.split(original) }
    val rows = remember(originalBlocks, translationBlocks, complete) {
        BilingualBlocks.align(originalBlocks, translationBlocks, complete)
    }

    // The article's own title, in whichever language(s) the current mode calls for. It is the list's
    // one header item, so the tint window and the stored scroll index both have to account for it.
    val targetTitle = conversation?.title.orEmpty()
    val nativeTitle = conversation?.originalTitle.orEmpty()
    val headerItems = if (targetTitle.isNotBlank() || nativeTitle.isNotBlank()) 1 else 0

    val listState = rememberLazyListState()
    val readMode = rememberReadModeState(savedMode, listState, headerItems, onSwitch = { next ->
        // The popover is anchored to a position in the version being left behind.
        reading.dismissTranslate()
        viewModel.saveReadMode(next)
    })
    val mode = readMode.mode
    val tint = readMode.tint
    val onSelectMode = readMode.select
    val savedColor = MaterialTheme.colorScheme.primary
    val colorizer = remember(highlightTargets, savedColor) { WordColorizer(highlightTargets, savedColor) }

    // Restore the saved reading position once there are rows to scroll into, then keep the stored
    // position in step with the reader so backing out resumes here.
    val scrollRestore = rememberScrollRestore(
        listState = listState,
        saved = savedScroll,
        itemCount = rows.size,
        toListIndex = { it.coerceAtMost(rows.size + headerItems - 1) },
        onPosition = viewModel::saveScroll,
    )
    // Mark the text finished once the last row is visible (only when fully translated), which fades
    // its accent styling in the Texts list to the muted "read" look.
    val reachedEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            // Not before the rows are in and the position restored — a header-only list would
            // otherwise report the last item as visible and finish the text on open.
            scrollRestore.restored &&
                info.totalItemsCount > headerItems &&
                info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }
    LaunchedEffect(reachedEnd, state) {
        if (reachedEnd && state is ReadingState.Ready) viewModel.markFinishedReading()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReadingTopBar(
                conversation = conversation,
                starred = starred,
                onBack = onBack,
                onToggleStar = viewModel::toggleStar,
                onSendToAudio = { audioGate.request { notifyAudio(viewModel.sendToAudio()) } },
                highlightWords = highlightWords,
                onToggleHighlightWords = viewModel::toggleHighlightWords,
                savedWordCount = savedWords.size,
                onOpenSavedWords = { showSavedWords = true },
                onDelete = { showDeleteConfirm = true },
            )

            when {
                // Nothing to lay out yet: the import hasn't produced the source text.
                rows.isEmpty() && state is ReadingState.Importing ->
                    Box(Modifier.weight(1f)) { CenteredStatus(stringResource(R.string.text_importing)) }
                rows.isEmpty() -> Box(Modifier.weight(1f)) {
                    CenteredError(
                        message = (state as? ReadingState.Failed)?.error?.let { llmErrorText(it) },
                        onRetry = viewModel::retry,
                    )
                }
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .readingModeSwipe(canStep = mode::canStep) { onSelectMode(mode.stepped(it)) },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (headerItems > 0) {
                        item(key = "title") { ArticleTitle(targetTitle, nativeTitle, mode) }
                    }
                    itemsIndexed(rows, key = { i, _ -> "b$i" }) { i, row ->
                        Box(modifier = Modifier.fillMaxWidth().correspondenceTint(tint, i)) {
                            BilingualBlockRow(
                                target = row.target,
                                native = row.native,
                                mode = mode,
                                colorizer = colorizer,
                                isActive = translate != null,
                                selection = selection,
                                block = i,
                                onSelected = reading::onSelectionTranslate,
                                onCleared = reading::dismissTranslate,
                            )
                        }
                    }
                    // Progress and partial failures ride inside the same scroll list, after the text.
                    when (val current = state) {
                        is ReadingState.Translating ->
                            item(key = "progress") { ProgressRow(current.done, current.total) }
                        is ReadingState.Failed -> item(key = "partial") {
                            PartialErrorRow(
                                reason = current.error?.let { llmErrorText(it) },
                                onContinue = viewModel::retry,
                            )
                        }
                        else -> Unit
                    }
                }
            }

            // How far through the article, immediately below the text it measures.
            ReadingProgressRule(fraction = { listState.readFraction() })

            // Version switch and "Discuss" share the bottom bar, within thumb reach while reading.
            Surface(tonalElevation = 2.dp) {
                Column {
                    SegmentedToggle(
                        selected = mode.ordinal,
                        labels = listOf(
                            stringResource(R.string.text_view_translation),
                            stringResource(R.string.text_view_original),
                            stringResource(R.string.text_view_both),
                        ),
                        onSelect = { onSelectMode(ReadMode.entries[it]) },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Button(
                        onClick = onDiscuss,
                        enabled = state is ReadingState.Ready,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .padding(bottom = 12.dp),
                    ) {
                        Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.text_discuss), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }

        translate?.let { t ->
            ReaderTranslationCard(
                state = t,
                selection = reading,
                onOpenVocab = onOpenVocab,
                onSendToAudio = { audioGate.request { notifyAudio(viewModel.sendSelectionToAudio(t.original)) } },
            )
        }

        SavedNotice(visible = savedNotice, onConsume = reading::consumeSavedNotice)
    }

    if (showSavedWords) {
        SavedWordsSheet(
            words = savedWords,
            onOpen = { id -> showSavedWords = false; onOpenVocab(id) },
            onDismiss = { showSavedWords = false },
        )
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = R.string.delete_text_title,
            message = R.string.delete_text_confirm,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.delete()
                onBack()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }

    audioGate.Prompt()
}

/**
 * The article's title, following the reading mode like the rows below it do — the translated title
 * in Translation and Both (with the source title as a gloss beneath), the source title in Original.
 * A missing side falls back to the other rather than leaving the article looking untitled.
 */
@Composable
private fun ArticleTitle(target: String, native: String, mode: ReadMode) {
    val primary = when {
        mode == ReadMode.NATIVE -> native.ifBlank { target }
        else -> target.ifBlank { native }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (primary.isNotBlank()) Text(primary, style = SerifTitle)
        if (mode == ReadMode.BOTH && native.isNotBlank() && native != primary) SubordinateGloss(native)
    }
}

@Composable
private fun ReadingTopBar(
    conversation: ConversationEntity?,
    starred: Boolean,
    onBack: () -> Unit,
    onToggleStar: () -> Unit,
    onSendToAudio: () -> Unit,
    highlightWords: Boolean,
    onToggleHighlightWords: () -> Unit,
    savedWordCount: Int,
    onOpenSavedWords: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    val url = conversation?.sourceUrl
    val source = url?.let { ArticleExtractor.hostOf(it) } ?: stringResource(R.string.text_source_pasted)
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Row(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (url != null) {
                        Modifier.clickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                        }
                    } else {
                        Modifier
                    },
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Filled.Language,
                contentDescription = if (url != null) stringResource(R.string.text_open_source) else null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                source,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        conversation?.let { CefrBadge(it.level.name) }
        Box {
            IconButton(onClick = { overflowOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (starred) R.string.action_unstar else R.string.action_star)) },
                    leadingIcon = {
                        Icon(if (starred) Icons.Outlined.StarBorder else Icons.Filled.Star, contentDescription = null)
                    },
                    onClick = { onToggleStar(); overflowOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_send_to)) },
                    leadingIcon = { Icon(Icons.Filled.Headphones, contentDescription = null) },
                    onClick = { onSendToAudio(); overflowOpen = false },
                )
                HighlightWordsMenuItem(
                    checked = highlightWords,
                    onToggle = onToggleHighlightWords,
                    onDismiss = { overflowOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.saved_words_menu, savedWordCount)) },
                    leadingIcon = { Icon(Icons.Filled.Bookmarks, contentDescription = null) },
                    onClick = { onOpenSavedWords(); overflowOpen = false },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_delete)) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { onDelete(); overflowOpen = false },
                )
            }
        }
    }
}

@Composable
private fun ProgressRow(done: Int, total: Int) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 8.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            stringResource(R.string.text_translating_progress, done, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PartialErrorRow(reason: String?, onContinue: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            stringResource(R.string.text_partial_error),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        if (reason != null) {
            Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onContinue) { Text(stringResource(R.string.text_continue)) }
    }
}

@Composable
private fun CenteredStatus(label: String) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun CenteredError(message: String?, onRetry: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                message ?: stringResource(R.string.text_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Button(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
    }
}
