package com.copiosa.ui.books

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.copiosa.R
import com.copiosa.data.epub.BookMedia
import com.copiosa.data.epub.imageKeyOf
import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.book.fullyReadable
import com.copiosa.domain.book.nativeText
import com.copiosa.domain.book.targetText
import com.copiosa.domain.book.wantsTranslation
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.reading.ReadMode
import com.copiosa.domain.reading.canStep
import com.copiosa.domain.reading.stepped
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.audio.TtsDownloadPrompt
import com.copiosa.ui.components.AsyncFileImage
import com.copiosa.ui.components.BLOCK_STRIDE
import com.copiosa.ui.components.BilingualBlockRow
import com.copiosa.ui.components.CefrBadge
import com.copiosa.ui.components.ConfirmDeleteDialog
import com.copiosa.ui.components.HighlightWordsMenuItem
import com.copiosa.ui.components.ReaderTranslationCard
import com.copiosa.ui.components.ReadingProgressRule
import com.copiosa.ui.components.SavedNotice
import com.copiosa.ui.components.SavedWordsSheet
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.components.TrackFocusTime
import com.copiosa.ui.components.TranslationCard
import com.copiosa.ui.components.WordColorizer
import com.copiosa.ui.components.correspondenceTint
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.components.readFraction
import com.copiosa.ui.components.readingModeSwipe
import com.copiosa.ui.components.rememberCorrespondenceTint
import com.copiosa.ui.components.rememberReadModeState
import com.copiosa.ui.components.rememberReadingSelectionSpan
import com.copiosa.ui.components.rememberScrollRestore
import com.copiosa.ui.vocab.FileImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The book reader: one paged LazyColumn over paragraph rows whose index maps 1:1 onto paragraph
 * ord (placeholders on), so switching reading mode swaps text on the *same* items — the anchor
 * paragraph keeps its position and correspondence is exact. Both mode renders the pair inside one
 * item for the same reason.
 *
 * On a mode switch, rows near the viewport pick up a faint tint from the theme's reading palette,
 * cycled by ord, so the matching paragraph is easy to find in the other language.
 * It fades in, holds eight seconds, then ebbs away — except in Both mode, where the tint is what
 * binds each target/native pair and so stays lit until the reader leaves that mode.
 */
@Composable
fun BookReadingScreen(
    onBack: () -> Unit,
    onOpenVocab: (Long) -> Unit,
    viewModel: BookReadingViewModel = hiltViewModel(),
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val autoReadAhead by viewModel.autoReadAhead.collectAsStateWithLifecycle()
    val hintDismissed by viewModel.frontierHintDismissed.collectAsStateWithLifecycle()
    val reading = viewModel.readingSelection
    val highlightTargets by reading.highlightTargets.collectAsStateWithLifecycle()
    val highlightWords by viewModel.highlightWords.collectAsStateWithLifecycle()
    val translate by reading.translate.collectAsStateWithLifecycle()
    val savedNotice by reading.savedNotice.collectAsStateWithLifecycle()
    val savedScroll by viewModel.savedScroll.collectAsStateWithLifecycle()
    val savedMode by viewModel.savedMode.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val needsTtsDownload by viewModel.needsTtsDownload.collectAsStateWithLifecycle()
    val currentChapter by viewModel.currentChapter.collectAsStateWithLifecycle()
    val paragraphs = viewModel.paragraphs.collectAsLazyPagingItems()
    val openNote by viewModel.openNote.collectAsStateWithLifecycle()
    val context = LocalContext.current

    TrackFocusTime(FocusActivityType.READING_BOOK, book?.targetLanguage)

    // One selection across the whole book, so a drag can run from one paragraph into the next.
    val selection = rememberReadingSelectionSpan()
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    var showSavedWords by remember { mutableStateOf(false) }
    val savedWords by viewModel.savedWords.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val direction = book?.direction ?: BookDirection.FORWARD

    // Restore the saved reading position once rows exist. The pager's initialKey already loaded
    // around it; this jump makes it exact. The same hook then drives read-ahead as the reader moves.
    val scrollRestore = rememberScrollRestore(
        listState = listState,
        saved = savedScroll,
        itemCount = paragraphs.itemCount,
        toListIndex = { (it + HEADER_ITEMS).coerceAtMost(paragraphs.itemCount) },
        onPosition = { index, offset -> viewModel.onFirstVisible((index - HEADER_ITEMS).coerceAtLeast(0), offset) },
    )
    // A book finishes itself on its very last page: when the last *paragraph* is on screen, after
    // the saved position has been restored. Asked of the paragraph row rather than of "the last
    // list item", because before the paragraphs arrive the list is just its header and footer and
    // the last item is trivially visible — which marked a book finished the instant it was opened,
    // however far in the reader actually was. (Anything short of the last page is the reader's call:
    // the Library row's "Mark read".)
    val reachedEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastParagraph = info.totalItemsCount - 1 - FOOTER_ITEMS
            scrollRestore.restored &&
                lastParagraph >= HEADER_ITEMS &&
                info.visibleItemsInfo.any { it.index == lastParagraph }
        }
    }
    LaunchedEffect(reachedEnd, status) {
        if (reachedEnd && status is BookStatus.Ready) viewModel.markFinishedReading()
    }
    val readMode = rememberReadModeState(savedMode, listState, HEADER_ITEMS, onSwitch = { next ->
        // The popover is anchored to a position in the version being left behind.
        reading.dismissTranslate()
        viewModel.saveReadMode(next)
    })
    val mode = readMode.mode
    val tint = readMode.tint
    val onSelectMode = readMode.select

    val savedColor = MaterialTheme.colorScheme.primary
    val colorizer = remember(highlightTargets, savedColor) { WordColorizer(highlightTargets, savedColor) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            BookTopBar(
                book = book,
                onBack = onBack,
                onOpenToc = { showToc = true },
                onToggleStar = viewModel::toggleStar,
                onContinueTranslating = viewModel::continueTranslating,
                onOpenDownloadMode = viewModel::openDownloadDialog,
                onGenerateAudio = viewModel::requestAudio,
                onExport = viewModel::openExportDialog,
                highlightWords = highlightWords,
                onToggleHighlightWords = viewModel::toggleHighlightWords,
                savedWordCount = savedWords.size,
                onOpenSavedWords = { showSavedWords = true },
                onDelete = { showDeleteConfirm = true },
            )

            // Translation status sits at the top, out of the reading flow. The "more will translate
            // as you read" variant can be waved away for good — "Continue translating" stays in the
            // overflow menu, so dismissing it never strands the reader.
            BookStatusBar(
                status = status,
                autoReadAhead = autoReadAhead,
                hintDismissed = hintDismissed,
                onContinue = viewModel::continueTranslating,
                onDismissHint = viewModel::dismissFrontierHint,
            )

            LazyColumn(
                state = listState,
                // Swiping the page is the same act as tapping the toggle, so it goes through the
                // same handler — the correspondence tint and the dismissed popover are what make a
                // mode switch legible, and a swipe that skipped them would be a second, worse way
                // to change mode.
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .readingModeSwipe(canStep = mode::canStep) { onSelectMode(mode.stepped(it)) },
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "header") { BookHeader(book) }
                items(
                    count = paragraphs.itemCount,
                    key = paragraphs.itemKey { "p${it.ord}" },
                ) { index ->
                    val row = paragraphs[index]
                    if (row == null) {
                        // Placeholder while the page loads in; fixed height keeps scrolling smooth.
                        Spacer(modifier = Modifier.fillMaxWidth().height(56.dp))
                    } else {
                        Box(modifier = Modifier.fillMaxWidth().correspondenceTint(tint, row.ord)) {
                            if (row.kind == BookParagraphEntity.KIND_IMAGE) {
                                // Still exactly one list item even when the file is missing (an
                                // image past the import budget), because the item index is the ord.
                                AsyncFileImage(
                                    file = book?.id?.let { id ->
                                        imageKeyOf(row.originalText)
                                            ?.let { BookMedia.imageFile(context, id, it) }
                                    },
                                )
                            } else {
                                BilingualBlockRow(
                                    target = row.targetText(direction),
                                    native = row.nativeText(direction),
                                    mode = mode,
                                    colorizer = colorizer,
                                    isActive = translate != null,
                                    selection = selection,
                                    // Strided so a table's cells get keys of their own between this
                                    // row and the next; see BilingualTableRow.
                                    block = row.ord * BLOCK_STRIDE,
                                    onSelected = reading::onSelectionTranslate,
                                    onCleared = reading::dismissTranslate,
                                    kind = row.kind,
                                    onFootnote = { label -> viewModel.onFootnote(row.chapterIndex, label) },
                                )
                            }
                        }
                    }
                }
                item(key = "footerSpace") { Spacer(modifier = Modifier.height(24.dp)) }
            }

            // How far through the book, immediately below the text it measures.
            ReadingProgressRule(fraction = { listState.readFraction() })

            // The version switch lives at the bottom, within thumb reach while reading.
            Surface(tonalElevation = 2.dp) {
                SegmentedToggle(
                    selected = mode.ordinal,
                    // Target/native rather than translation/original: which stored column is which
                    // depends on the book's direction, but which language is being *studied* never
                    // does — so that is what the control names.
                    labels = listOf(
                        stringResource(R.string.book_view_target),
                        stringResource(R.string.book_view_native),
                        stringResource(R.string.text_view_both),
                    ),
                    onSelect = { onSelectMode(ReadMode.entries[it]) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        translate?.let { t ->
            ReaderTranslationCard(state = t, selection = reading, onOpenVocab = onOpenVocab)
        }

        SavedNotice(visible = savedNotice, onConsume = reading::consumeSavedNotice)
    }

    openNote?.let { note ->
        BookFootnoteSheet(
            note = note,
            direction = direction,
            mode = mode,
            onDismiss = viewModel::dismissNote,
        )
    }

    if (showSavedWords) {
        SavedWordsSheet(
            words = savedWords,
            onOpen = { id -> showSavedWords = false; onOpenVocab(id) },
            onDismiss = { showSavedWords = false },
        )
    }

    if (showToc) {
        BookTocSheet(
            chapters = chapters,
            currentChapter = currentChapter,
            onJump = { ord ->
                showToc = false
                // Claim the restore gate before jumping. The savedScroll effect is inhibited only by
                // scrollRestored, so opening the TOC before restore has run would otherwise leave it
                // free to yank the reader back to the old position a frame later.
                scrollRestore.markRestored()
                scope.launch { listState.scrollToItem(ord + HEADER_ITEMS, 0) }
            },
            onContinueTranslating = viewModel::continueTranslating,
            onDismiss = { showToc = false },
        )
    }

    BookActionDialogs(viewModel.actions)

    if (needsTtsDownload) {
        TtsDownloadPrompt(
            onConfirm = viewModel::confirmTtsDownload,
            onDismiss = viewModel::dismissTtsDownload,
        )
    }

    if (showDeleteConfirm) {
        ConfirmDeleteDialog(
            title = R.string.book_delete_title,
            message = R.string.book_delete_confirm,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.delete()
                onBack()
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}

/**
 * Cover + author, the list's single header item (HEADER_ITEMS accounts for it). No title: the top
 * bar already carries it, and repeating it immediately below reads as a duplicate rather than as
 * the start of the book.
 */
@Composable
private fun BookHeader(book: BookEntity?) {
    if (book == null || (book.coverFile == null && book.author.isNullOrBlank())) return
    val context = LocalContext.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        book.coverFile?.let { name ->
            FileImage(
                file = BookMedia.file(context, name),
                modifier = Modifier.width(72.dp).height(104.dp),
                contentScale = ContentScale.Crop,
            )
        }
        book.author?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BookTopBar(
    book: BookEntity?,
    onBack: () -> Unit,
    onOpenToc: () -> Unit,
    onToggleStar: () -> Unit,
    onContinueTranslating: () -> Unit,
    onOpenDownloadMode: () -> Unit,
    onGenerateAudio: () -> Unit,
    onExport: () -> Unit,
    highlightWords: Boolean,
    onToggleHighlightWords: () -> Unit,
    savedWordCount: Int,
    onOpenSavedWords: () -> Unit,
    onDelete: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        // Title over author: which book you are in is the thing worth knowing once the cover
        // header has scrolled away. Both lines are single-line — a long title must not push the
        // level badge or the overflow menu around.
        Column(modifier = Modifier.weight(1f)) {
            Text(
                book?.title ?: stringResource(R.string.book_source),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            book?.author?.let { author ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        author,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        book?.let {
            IconButton(onClick = onOpenToc) {
                Icon(
                    Icons.AutoMirrored.Filled.List,
                    contentDescription = stringResource(R.string.cd_table_of_contents),
                )
            }
            // A reverse book was never graded — its translation, if any, is the learner's own
            // language — so a level badge on it would be a claim about nothing.
            if (it.direction == BookDirection.FORWARD) CefrBadge(it.level.name)
        }
        Box {
            IconButton(onClick = { overflowOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
            }
            DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                val starred = book?.starred == true
                DropdownMenuItem(
                    text = { Text(stringResource(if (starred) R.string.action_unstar else R.string.action_star)) },
                    leadingIcon = {
                        Icon(if (starred) Icons.Outlined.StarBorder else Icons.Filled.Star, contentDescription = null)
                    },
                    onClick = { onToggleStar(); overflowOpen = false },
                )
                // Always available, so dismissing the banner never strands a partly-translated book.
                // Hidden on a reverse book that never opted into the gloss: its untranslated
                // sections are not a shortfall to continue from.
                if (book != null && book.wantsTranslation && book.translatedSections < book.totalSections) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.text_continue)) },
                        leadingIcon = { Icon(Icons.Filled.Translate, contentDescription = null) },
                        onClick = { onContinueTranslating(); overflowOpen = false },
                    )
                }
                // How much this book translates on its own, and with which model. On a reverse book
                // it is also the gloss opt-in — the one control that turns an LLM-free book into one
                // that gets a native translation.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.book_download_menu)) },
                    leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    onClick = { onOpenDownloadMode(); overflowOpen = false },
                )
                // Offered from the first translated chapter: the dialog reports how much is ready,
                // and the rest is added as translation catches up.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_generate_audio)) },
                    leadingIcon = { Icon(Icons.Filled.VolumeUp, contentDescription = null) },
                    onClick = { onGenerateAudio(); overflowOpen = false },
                )
                // Only once the book reads end to end: a half-translated dump would be a file that
                // trails off into the other language partway through.
                if (book != null && book.fullyReadable) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.book_export_menu)) },
                        leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                        onClick = { onExport(); overflowOpen = false },
                    )
                }
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

/**
 * Slim status strip directly under the top bar. The untranslated tail renders inline as muted
 * original text, so this is only for translation progress, the frontier hint, or a failure —
 * out of the reading flow rather than buried at the end of a 4000-row list.
 *
 * The frontier hint honours [hintDismissed] (a permanent, profile-wide "don't tell me again");
 * in-flight progress and failures always show, since those are transient and actionable.
 */
@Composable
private fun BookStatusBar(
    status: BookStatus,
    autoReadAhead: Boolean,
    hintDismissed: Boolean,
    onContinue: () -> Unit,
    onDismissHint: () -> Unit,
) {
    when (status) {
        is BookStatus.Ready -> return
        is BookStatus.Translating -> Surface(tonalElevation = 2.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    // done is a *count* of finished sections, so the one in flight is done + 1 —
                    // otherwise this reads "translating section 6" beside "6 of 71 translated".
                    stringResource(
                        R.string.book_translating_progress,
                        (status.done + 1).coerceAtMost(status.total),
                        status.total,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is BookStatus.Paused -> {
            if (hintDismissed) return
            Surface(tonalElevation = 2.dp) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
                ) {
                    Text(
                        stringResource(
                            if (autoReadAhead) R.string.book_paused_auto else R.string.book_paused_manual,
                            status.done,
                            status.total,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onContinue) { Text(stringResource(R.string.book_continue)) }
                    IconButton(onClick = onDismissHint) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.action_dismiss),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        is BookStatus.Failed -> Surface(tonalElevation = 2.dp) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            ) {
                Text(
                    stringResource(
                        R.string.book_failed_progress,
                        status.done,
                        status.total,
                        status.error?.let { llmErrorText(it) }
                            ?: stringResource(R.string.text_partial_error),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onContinue) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
}

/** The list's non-paragraph items before the rows (the cover/title header). */
private const val HEADER_ITEMS = 1

/** The spacer after the last paragraph; with [HEADER_ITEMS], what the list holds before any text. */
private const val FOOTER_ITEMS = 1
