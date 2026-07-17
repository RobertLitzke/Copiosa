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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.flow.distinctUntilChanged
import com.copiosa.R
import com.copiosa.data.article.ArticleExtractor
import com.copiosa.data.local.ConversationEntity
import com.copiosa.domain.highlight.HighlightTarget
import com.copiosa.ui.audio.TtsDownloadPrompt
import com.copiosa.ui.components.CefrBadge
import com.copiosa.ui.components.MarkdownText
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.components.SelectableReader
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.conversation.TranslateState
import com.copiosa.ui.theme.SerifTitle
import kotlinx.coroutines.delay

@Composable
fun TextReadingScreen(
    onBack: () -> Unit,
    onDiscuss: () -> Unit,
    onOpenVocab: (Long) -> Unit,
    viewModel: TextReadingViewModel = hiltViewModel(),
) {
    val conversation by viewModel.conversation.collectAsState()
    val translation by viewModel.translation.collectAsState()
    val state by viewModel.state.collectAsState()
    val highlightTargets by viewModel.highlightTargets.collectAsState()
    val translate by viewModel.translate.collectAsState()
    val savedNotice by viewModel.savedNotice.collectAsState()
    val starred by viewModel.starred.collectAsState()
    val savedScroll by viewModel.savedScroll.collectAsState()

    var showOriginal by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showTtsPrompt by remember { mutableStateOf(false) }
    var pendingAudioAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val context = LocalContext.current
    val addedToAudio = stringResource(R.string.audio_added)
    val notifyAdded: () -> Unit = { Toast.makeText(context, addedToAudio, Toast.LENGTH_SHORT).show() }
    // Runs [enqueue] now if the voice is installed, otherwise prompts to download it first and defers.
    val requestAudio: (() -> Unit) -> Unit = { enqueue ->
        if (viewModel.isTtsReady()) enqueue() else { pendingAudioAction = enqueue; showTtsPrompt = true }
    }
    val savedText = stringResource(R.string.saved)

    // Both versions are split into paragraph/heading blocks so each is an addressable anchor: that's
    // what lets the translation and original stay aligned when the reader flips between them.
    val original = conversation?.originalText.orEmpty()
    val translationBlocks = remember(translation) { splitBlocks(translation) }
    val originalBlocks = remember(original) { splitBlocks(original) }

    val translationListState = rememberLazyListState()
    val originalListState = rememberLazyListState()

    // Restore the saved reading position once the translation has content to scroll into. Gated by
    // [scrollRestored] so the persistence effect below doesn't overwrite the stored offset with 0
    // before we've had a chance to jump there.
    var scrollRestored by remember { mutableStateOf(false) }
    LaunchedEffect(savedScroll, translationBlocks.size) {
        if (scrollRestored || translationBlocks.isEmpty()) return@LaunchedEffect
        savedScroll?.let { (index, offset) ->
            if (index > 0 || offset > 0) translationListState.scrollToItem(index, offset)
        }
        scrollRestored = true
    }
    // After restoring, keep the stored position in step with the reader so backing out resumes here.
    LaunchedEffect(scrollRestored, translationListState) {
        if (!scrollRestored) return@LaunchedEffect
        snapshotFlow {
            translationListState.firstVisibleItemIndex to translationListState.firstVisibleItemScrollOffset
        }
            .distinctUntilChanged()
            .collect { (index, offset) -> viewModel.saveScroll(index, offset) }
    }
    // Mark the text finished once the last block is visible (only when fully translated), which fades
    // its accent styling in the Texts list to the muted "read" look.
    val reachedEnd by remember {
        derivedStateOf {
            val info = translationListState.layoutInfo
            info.totalItemsCount > 0 &&
                info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }
    LaunchedEffect(reachedEnd, state) {
        if (reachedEnd && state is ReadingState.Ready) viewModel.markFinishedReading()
    }

    LaunchedEffect(savedNotice) {
        if (savedNotice) {
            delay(1800)
            viewModel.consumeSavedNotice()
        }
    }

    // On flip, scroll the newly shown version to the block that corresponds (proportionally) to where
    // the other version was, so the reader lands at the same place in the text. The first run is
    // skipped so it doesn't reset the translation list to the top before the saved position restores.
    var flipPrimed by remember { mutableStateOf(false) }
    LaunchedEffect(showOriginal) {
        if (!flipPrimed) {
            flipPrimed = true
            return@LaunchedEffect
        }
        if (showOriginal) {
            originalListState.scrollToItem(
                mapBlockIndex(translationListState.firstVisibleItemIndex, translationBlocks.size, originalBlocks.size),
            )
        } else {
            translationListState.scrollToItem(
                mapBlockIndex(originalListState.firstVisibleItemIndex, originalBlocks.size, translationBlocks.size),
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ReadingTopBar(
                conversation = conversation,
                starred = starred,
                onBack = onBack,
                onToggleStar = viewModel::toggleStar,
                onSendToAudio = { requestAudio { viewModel.sendToAudio(); notifyAdded() } },
                onDelete = { showDeleteConfirm = true },
            )

            SegmentedToggle(
                selected = if (showOriginal) 1 else 0,
                labels = listOf(
                    stringResource(R.string.text_view_translation),
                    stringResource(R.string.text_view_original),
                ),
                onSelect = { index ->
                    val wantOriginal = index == 1
                    if (wantOriginal != showOriginal) viewModel.dismissTranslate()
                    showOriginal = wantOriginal
                },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (showOriginal) {
                    OriginalList(conversation = conversation, blocks = originalBlocks, listState = originalListState)
                } else {
                    TranslationList(
                        state = state,
                        title = conversation?.title.orEmpty(),
                        blocks = translationBlocks,
                        listState = translationListState,
                        highlightTargets = highlightTargets,
                        translate = translate,
                        onSelectionTranslate = viewModel::onSelectionTranslate,
                        onDismissSelection = viewModel::dismissTranslate,
                        onRetry = viewModel::retry,
                        onSave = { viewModel.saveCurrentTranslation(it) },
                        onDownloadDictionary = viewModel::downloadDictionary,
                        onOpenSaved = onOpenVocab,
                        onSendSelectionToAudio = { text ->
                            requestAudio { viewModel.sendSelectionToAudio(text); notifyAdded() }
                        },
                    )
                }
            }

            Surface(tonalElevation = 2.dp) {
                Button(
                    onClick = onDiscuss,
                    enabled = state is ReadingState.Ready,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Filled.ChatBubbleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.text_discuss), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }

        AnimatedVisibility(
            visible = savedNotice,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 56.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shadowElevation = 6.dp,
            ) {
                Text(savedText, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelLarge)
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.delete()
                    onBack()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            title = { Text(stringResource(R.string.delete_text_title)) },
            text = { Text(stringResource(R.string.delete_text_confirm)) },
        )
    }

    if (showTtsPrompt) {
        TtsDownloadPrompt(
            onConfirm = {
                showTtsPrompt = false
                viewModel.startTtsDownload()
                pendingAudioAction?.invoke()
                pendingAudioAction = null
            },
            onDismiss = { showTtsPrompt = false; pendingAudioAction = null },
        )
    }

}

/**
 * The translation reading pane: delegates to the shared [SelectableReader] (highlighting, tap-to-define,
 * save-to-vocab) and appends this importer's own progress / partial-error rows inside the same scroll
 * list. The import-spinner and hard-failure (no text yet) states are shown in place of the reader.
 */
@Composable
private fun TranslationList(
    state: ReadingState,
    title: String,
    blocks: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    highlightTargets: List<HighlightTarget>,
    translate: TranslateState?,
    onSelectionTranslate: (text: String, anchorY: Float, sourceText: String) -> Unit,
    onDismissSelection: () -> Unit,
    onRetry: () -> Unit,
    onSave: (String) -> Unit,
    onDownloadDictionary: () -> Unit,
    onOpenSaved: (Long) -> Unit,
    onSendSelectionToAudio: (String) -> Unit,
) {
    when {
        state is ReadingState.Importing -> CenteredStatus(stringResource(R.string.text_importing))
        state is ReadingState.Failed && blocks.isEmpty() ->
            CenteredError(message = state.error?.let { llmErrorText(it) }, onRetry = onRetry)
        else -> SelectableReader(
            blocks = blocks,
            highlightTargets = highlightTargets,
            translate = translate,
            onSelectionTranslate = onSelectionTranslate,
            onDismissSelection = onDismissSelection,
            onSave = onSave,
            onDownloadDictionary = onDownloadDictionary,
            title = title.ifBlank { null },
            listState = listState,
            onOpenSaved = onOpenSaved,
            onSendSelectionToAudio = onSendSelectionToAudio,
            footer = {
                when (state) {
                    is ReadingState.Translating -> item(key = "progress") { ProgressRow(state.done, state.total) }
                    is ReadingState.Failed -> item(key = "partial") {
                        PartialErrorRow(reason = state.error?.let { llmErrorText(it) }, onContinue = onRetry)
                    }
                    else -> {}
                }
            },
        )
    }
}

@Composable
private fun OriginalList(
    conversation: ConversationEntity?,
    blocks: List<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    if (blocks.isEmpty()) {
        CenteredStatus(stringResource(R.string.text_importing))
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        conversation?.originalTitle?.takeIf { it.isNotBlank() }?.let { title ->
            item(key = "otitle") { Text(title, style = SerifTitle) }
        }
        itemsIndexed(blocks, key = { i, _ -> "o$i" }) { _, block ->
            SelectionContainer { MarkdownText(block) }
        }
    }
}

@Composable
private fun ReadingTopBar(
    conversation: ConversationEntity?,
    starred: Boolean,
    onBack: () -> Unit,
    onToggleStar: () -> Unit,
    onSendToAudio: () -> Unit,
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

/** Splits Markdown text into paragraph/heading blocks (separated by a blank line). */
private fun splitBlocks(text: String): List<String> =
    text.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }

/** Maps a block index in one version to the proportionally-corresponding index in the other. */
private fun mapBlockIndex(index: Int, fromCount: Int, toCount: Int): Int {
    if (fromCount <= 1 || toCount <= 1) return 0
    return ((index.toLong() * (toCount - 1)) / (fromCount - 1)).toInt().coerceIn(0, toCount - 1)
}
