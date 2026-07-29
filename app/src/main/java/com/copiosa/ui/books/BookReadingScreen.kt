package com.copiosa.ui.books

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.copiosa.R
import com.copiosa.data.epub.BookMedia
import com.copiosa.data.local.BookEntity
import com.copiosa.domain.book.nativeText
import com.copiosa.domain.book.targetText
import com.copiosa.domain.book.wantsTranslation
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.components.CefrBadge
import com.copiosa.ui.components.MarkdownText
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.components.SelectableMessage
import com.copiosa.ui.components.TrackFocusTime
import com.copiosa.ui.components.TranslationCard
import com.copiosa.ui.components.WordColorizer
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.flashcards.FileImage
import com.copiosa.ui.theme.LocalReadingStyle
import com.copiosa.ui.theme.skin.LocalAppSkin
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
    val book by viewModel.book.collectAsState()
    val status by viewModel.status.collectAsState()
    val autoReadAhead by viewModel.autoReadAhead.collectAsState()
    val hintDismissed by viewModel.frontierHintDismissed.collectAsState()
    val highlightTargets by viewModel.highlightTargets.collectAsState()
    val translate by viewModel.translate.collectAsState()
    val savedNotice by viewModel.savedNotice.collectAsState()
    val savedScroll by viewModel.savedScroll.collectAsState()
    val chapters by viewModel.chapters.collectAsState()
    val currentChapter by viewModel.currentChapter.collectAsState()
    val paragraphs = viewModel.paragraphs.collectAsLazyPagingItems()

    TrackFocusTime(FocusActivityType.READING_BOOK, book?.targetLanguage)

    var mode by remember { mutableStateOf(ReadMode.TARGET) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showToc by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val direction = book?.direction ?: BookDirection.FORWARD

    // Restore the saved reading position once rows exist. The pager's initialKey already loaded
    // around it; this jump makes it exact. Gated so the persistence effect can't zero it first.
    var scrollRestored by remember { mutableStateOf(false) }
    LaunchedEffect(savedScroll, paragraphs.itemCount) {
        if (scrollRestored || paragraphs.itemCount == 0) return@LaunchedEffect
        savedScroll?.let { (ord, offset) ->
            if (ord > 0 || offset > 0) {
                listState.scrollToItem((ord + HEADER_ITEMS).coerceAtMost(paragraphs.itemCount), offset)
            }
        } ?: return@LaunchedEffect
        scrollRestored = true
    }
    // After restoring, keep the stored position in step (and drive read-ahead) as the user reads.
    LaunchedEffect(scrollRestored) {
        if (!scrollRestored) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                viewModel.onFirstVisible((index - HEADER_ITEMS).coerceAtLeast(0), offset)
            }
    }
    val reachedEnd by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 &&
                info.visibleItemsInfo.lastOrNull()?.index == info.totalItemsCount - 1
        }
    }
    LaunchedEffect(reachedEnd, status) {
        if (reachedEnd && status is BookStatus.Ready) viewModel.markFinishedReading()
    }
    LaunchedEffect(savedNotice) {
        if (savedNotice) {
            delay(1800)
            viewModel.consumeSavedNotice()
        }
    }

    // Correspondence tints: on a mode switch, capture the ords within ±1 viewport and fade them
    // out. One shared Animatable; only rows inside [tintRange] read it, so the rest never
    // recompose for it. Both mode keeps its tints, so there the range covers the whole book.
    var tintRange by remember { mutableStateOf(IntRange.EMPTY) }
    var tintEpoch by remember { mutableIntStateOf(0) }
    val tintAlpha = remember { Animatable(0f) }
    LaunchedEffect(tintEpoch) {
        if (tintEpoch == 0) return@LaunchedEffect
        // Eased in rather than snapped on: colour arriving is as noticeable as colour leaving.
        tintAlpha.animateTo(1f, tween(TINT_RISE_MS, easing = FastOutSlowInEasing))
        // In Both mode the tint is the only thing pairing the two languages — it stays until the
        // reader leaves the mode, which restarts this effect and lets it fade.
        if (mode == ReadMode.BOTH) return@LaunchedEffect
        delay(TINT_HOLD_MS - TINT_RISE_MS)
        // Linear, so the colour ebbs evenly. The default ease-out holds near full and then drops
        // away at the end, which reads as the tint vanishing rather than fading.
        tintAlpha.animateTo(0f, tween(TINT_FADE_MS, easing = LinearEasing))
    }
    val onSelectMode: (ReadMode) -> Unit = { next ->
        if (next != mode) {
            viewModel.dismissTranslate()
            tintRange = if (next == ReadMode.BOTH) {
                // The tint persists here, so scrolling must not outrun the captured window.
                0..Int.MAX_VALUE
            } else {
                val visible = listState.layoutInfo.visibleItemsInfo
                val first = (visible.firstOrNull()?.index ?: 0) - HEADER_ITEMS
                val last = (visible.lastOrNull()?.index ?: 0) - HEADER_ITEMS
                val extent = (last - first + 1).coerceAtLeast(1)
                (first - extent).coerceAtLeast(0)..(last + extent)
            }
            mode = next
            tintEpoch += 1
        }
    }

    val savedColor = MaterialTheme.colorScheme.primary
    val colorizer = remember(highlightTargets, savedColor) { WordColorizer(highlightTargets, savedColor) }
    val tints = LocalAppSkin.current.palette.tints
    val savedText = stringResource(R.string.saved)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            BookTopBar(
                book = book,
                onBack = onBack,
                onOpenToc = { showToc = true },
                onToggleStar = viewModel::toggleStar,
                onContinueTranslating = viewModel::continueTranslating,
                onToggleAutoTranslate = { viewModel.setAutoTranslate(!(book?.autoTranslate ?: false)) },
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
                modifier = Modifier.weight(1f).fillMaxWidth(),
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
                        Box(
                            // The correspondence tint is painted in the draw phase, not composed:
                            // reading the animation inside drawBehind redraws without recomposing
                            // the paragraph's text on every frame of the fade. Padding is
                            // unconditional so a tinted row never shifts the text.
                            modifier = Modifier
                                .fillMaxWidth()
                                .drawBehind {
                                    val alpha = tintAlpha.value
                                    if (alpha <= 0.004f || row.ord !in tintRange) return@drawBehind
                                    drawRoundRect(
                                        color = tints[row.ord.mod(tints.size)].copy(alpha = alpha),
                                        cornerRadius = CornerRadius(6.dp.toPx()),
                                    )
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                        ) {
                            ParagraphRow(
                                target = row.targetText(direction),
                                native = row.nativeText(direction),
                                mode = mode,
                                colorizer = colorizer,
                                isActive = translate != null,
                                onSelected = { text, y, source -> viewModel.onSelectionTranslate(text, y, source) },
                                onCleared = viewModel::dismissTranslate,
                            )
                        }
                    }
                }
                item(key = "footerSpace") { Spacer(modifier = Modifier.height(24.dp)) }
            }

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
            TranslationCard(
                original = t.original,
                translation = t.translation,
                translationOf = t.translationOf,
                loading = t.loading,
                definition = t.definition,
                canExplain = false,
                anchorYPx = t.anchorY,
                savedCard = t.savedCard,
                lemmaSuggestion = t.saveLemma,
                savePos = t.savePos,
                onSave = { viewModel.saveCurrentTranslation(it) },
                onSelectEntry = viewModel::selectSaveEntry,
                onExplain = {},
                onDownloadDictionary = viewModel::downloadDictionary,
                onOpenSaved = { t.savedCard?.let { c -> onOpenVocab(c.id) } },
                onDismiss = viewModel::dismissTranslate,
            )
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
                Text(
                    savedText,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
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
                scrollRestored = true
                scope.launch { listState.scrollToItem(ord + HEADER_ITEMS, 0) }
            },
            onContinueTranslating = viewModel::continueTranslating,
            onDismiss = { showToc = false },
        )
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
            title = { Text(stringResource(R.string.book_delete_title)) },
            text = { Text(stringResource(R.string.book_delete_confirm)) },
        )
    }
}

/** Which language a paragraph shows. The ordinal is the segment index in the bottom control. */
private enum class ReadMode { TARGET, NATIVE, BOTH }

/**
 * One paragraph, rendered in either language — or, in [ReadMode.BOTH], in both at once, target
 * first with the native gloss beneath it. Which stored column supplies which language depends on
 * the book's direction and is resolved by the caller, so this only ever thinks in target/native.
 *
 * The target side gets the full tap-to-translate machinery, in both directions — it is the language
 * being learned, so it is the one worth looking words up in. Either side can be absent (a forward
 * book past the frontier, a reverse book with no gloss, or a rare alignment miss).
 *
 * A missing *target* is called out rather than papered over: showing the source text muted in the
 * translation's place reads as a translation that happens to be identical, which is exactly the
 * confusion a reader hits on a freshly opened book. In Both mode the native gloss still shows
 * beneath the notice, since that side is legitimately part of the view. A missing *native* in
 * Native mode still falls back to the target — there is nothing pending there to wait for.
 *
 * Both texts stay inside this one list item: index == ord + HEADER_ITEMS is what scroll restore,
 * read-ahead, the TOC jump and the tint range all rely on, and a second item per paragraph would
 * break it.
 */
@Composable
private fun ParagraphRow(
    target: String?,
    native: String?,
    mode: ReadMode,
    colorizer: WordColorizer,
    isActive: Boolean,
    onSelected: (text: String, anchorY: Float, sourceText: String) -> Unit,
    onCleared: () -> Unit,
) {
    when {
        mode == ReadMode.NATIVE -> if (native.isNullOrBlank()) {
            // Nothing pending — a reverse book simply has no gloss here. Show what there is, muted.
            SubordinateGloss(target.orEmpty())
        } else {
            SelectionContainer { MarkdownText(native) }
        }
        // No translation for this paragraph yet. Say so instead of showing the source in its place.
        target.isNullOrBlank() ->
            if (mode == ReadMode.BOTH && !native.isNullOrBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PendingTranslationNotice()
                    SubordinateGloss(native)
                }
            } else {
                PendingTranslationNotice()
            }
        mode == ReadMode.BOTH && !native.isNullOrBlank() ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // A tight gap against the list's 12.dp spacing is what makes the pair read as one
                // block; the correspondence tint wraps both and reinforces it.
                SelectableMessage(
                    content = target,
                    colorizer = colorizer,
                    isActive = isActive,
                    onSelected = { text, y -> onSelected(text, y, target) },
                    onCleared = onCleared,
                )
                SubordinateGloss(native)
            }
        else -> SelectableMessage(
            content = target,
            colorizer = colorizer,
            isActive = isActive,
            onSelected = { text, y -> onSelected(text, y, target) },
            onCleared = onCleared,
        )
    }
}

/**
 * The subordinate language as a gloss: muted and a touch smaller, so in Both mode the eye stays on
 * the target language and drops to this only to check itself. Also how an untranslated paragraph
 * renders. MarkdownText needs no parameters for it — it already honours both of these locals.
 */
@Composable
private fun SubordinateGloss(text: String) {
    val reading = LocalReadingStyle.current
    CompositionLocalProvider(
        LocalReadingStyle provides reading.copy(scale = reading.scale * GLOSS_SCALE),
        LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        SelectionContainer { MarkdownText(text) }
    }
}

/**
 * Stands in for a paragraph whose translation has not arrived yet — quiet, italic, and clearly not
 * prose, so it reads as a status rather than as text to be read.
 */
@Composable
private fun PendingTranslationNotice() {
    Text(
        stringResource(R.string.book_paragraph_pending),
        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
    onToggleAutoTranslate: () -> Unit,
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
                // On a reverse book this is also the gloss opt-in — the one switch that turns an
                // LLM-free book into one that gets a native translation.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.book_translate_background)) },
                    leadingIcon = {
                        if (book?.autoTranslate == true) {
                            Icon(Icons.Filled.Check, contentDescription = null)
                        } else {
                            Spacer(modifier = Modifier.size(24.dp))
                        }
                    },
                    onClick = { onToggleAutoTranslate(); overflowOpen = false },
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
                    status.error?.let { llmErrorText(it) } ?: stringResource(R.string.text_partial_error),
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

/** How much smaller the source gloss reads than the target text it sits under. */
private const val GLOSS_SCALE = 0.9f

// Tint timing: eased in, at full strength until eight seconds after the switch, then ebbing away
// over the next few. The colours themselves come from the theme's reading palette
// (CountryPalette.tints), which is where their subtlety lives — this animates them 0..1.
private const val TINT_RISE_MS = 300
private const val TINT_HOLD_MS = 8_000L
private const val TINT_FADE_MS = 2_800
