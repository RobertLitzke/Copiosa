package com.copiosa.ui.vocab

import android.text.format.DateUtils
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.ReviewLogEntity
import com.copiosa.data.local.cardDirection
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.domain.model.Cloze
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.domain.srs.isLeech
import com.copiosa.ui.components.ratingColor
import com.copiosa.ui.components.ratingLabel
import com.copiosa.ui.components.CefrBadge
import com.copiosa.ui.components.MarkdownText
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.inlineMarkup
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.components.readingStyle
import com.copiosa.ui.components.stripInlineMarkdown
import com.copiosa.ui.theme.SerifBody
import com.copiosa.ui.theme.SerifTitle
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Detail for a single vocabulary word: translation, an on-demand colloquial Markdown explanation
 * with POS/CEFR chips, an example sentence, and a link back to the chat it was first seen in.
 */
@Composable
fun VocabDetailScreen(
    onBack: () -> Unit,
    onOpenConversation: (Long) -> Unit,
    onOpenText: (Long) -> Unit,
    onOpenBook: (Long) -> Unit,
    /** Opens a cloze sitting on this word. */
    onPractiseInSentences: (Long) -> Unit,
    viewModel: VocabDetailViewModel = hiltViewModel(),
) {
    val card by viewModel.card.collectAsStateWithLifecycle()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val rank by viewModel.rank.collectAsStateWithLifecycle()
    val origin by viewModel.origin.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.vocab_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            card?.let { c ->
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                }
                IconButton(onClick = viewModel::toggleMarked) {
                    Icon(
                        if (c.marked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (c.marked) R.string.action_unmark else R.string.action_mark,
                        ),
                        tint = if (c.marked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = stringResource(R.string.vocab_delete))
                }
            }
        }

        val c = card
        if (c == null) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            DetailContent(
                card = c,
                // Until the note's cards arrive, the card the screen was opened on stands alone.
                directions = cards.ifEmpty { listOf(c) },
                history = history,
                onToggleFreeze = viewModel::toggleSuspended,
                onStartOver = viewModel::startOver,
                onSetDueDate = viewModel::setDueDate,
                onStudyBothDirections = viewModel::studyBothDirections,
                rank = rank,
                origin = origin,
                explaining = ui.explaining,
                errorText = ui.error?.let { llmErrorText(it) },
                onExplain = viewModel::explain,
                onPractise = { onPractiseInSentences(c.id) },
                onOpenOrigin = {
                    when (val o = origin) {
                        is WordOrigin.Chat -> onOpenConversation(o.id)
                        is WordOrigin.Text -> onOpenText(o.id)
                        is WordOrigin.Book -> onOpenBook(o.id)
                        WordOrigin.Routine, null -> Unit
                    }
                },
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.vocab_delete)) },
            text = { Text(stringResource(R.string.vocab_delete_message)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.delete(onBack) }) {
                    Text(stringResource(R.string.action_delete))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (editing) {
        card?.let { c ->
            EditCardDialog(
                card = c,
                onSave = viewModel::updateCard,
                onSearchImages = viewModel::searchImages,
                onAttachImage = viewModel::attachImage,
                onRemoveImage = viewModel::removeImage,
                onDismiss = { editing = false },
            )
        }
    }
}

@Composable
private fun DetailContent(
    card: FlashcardEntity,
    directions: List<FlashcardEntity>,
    history: Map<Long, List<ReviewLogEntity>>,
    onToggleFreeze: (FlashcardEntity) -> Unit,
    onStartOver: (FlashcardEntity) -> Unit,
    onSetDueDate: (FlashcardEntity, Long) -> Unit,
    onStudyBothDirections: () -> Unit,
    rank: Int?,
    origin: WordOrigin?,
    explaining: Boolean,
    errorText: String?,
    onExplain: () -> Unit,
    onPractise: () -> Unit,
    onOpenOrigin: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val clozeText = Cloze.source(card.front, card.back)
        if (clozeText != null) {
            // Cloze card: show the fully-filled sentence with its answer(s) called out.
            Text(Cloze.answer(clozeText), style = SerifTitle.copy(fontSize = 22.sp, lineHeight = 30.sp))
            val answers = Cloze.activeAnswers(clozeText, card.ankiOrd?.plus(1))
            if (answers.isNotEmpty()) {
                Text(
                    stringResource(R.string.cloze_answer_label, answers.joinToString(", ")),
                    style = SerifBody.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Text(stripInlineMarkdown(card.front), style = SerifTitle.copy(fontSize = 28.sp, lineHeight = 34.sp))
            // The country a word is specific to (if the user set one) sits just under the headword.
            card.country?.takeIf { it.isNotBlank() }?.let {
                Text(
                    countryLabel(it),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val article = genderArticle(card.gender, card.targetLanguage)
            if (article != null || rank != null ||
                !card.partOfSpeech.isNullOrBlank() || !card.cefrLevel.isNullOrBlank()
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GenderChip(card.gender, card.targetLanguage)
                    card.partOfSpeech?.takeIf { it.isNotBlank() }?.let { PosChip(it) }
                    FreqChip(rank)
                    card.cefrLevel?.takeIf { it.isNotBlank() }?.let { CefrBadge(it) }
                }
            }

            card.altForms?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "= $it",
                    style = SerifBody.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (card.back.isNotBlank()) {
                // The translation is a plain gloss — never Markdown (only the usage note / explanation are).
                Text(
                    stripInlineMarkdown(card.back),
                    style = readingStyle(MaterialTheme.typography.bodyLarge),
                )
            }

            card.usageNote?.takeIf { it.isNotBlank() }?.let { note ->
                Spacer(Modifier.size(4.dp))
                SectionLabel(stringResource(R.string.vocab_usage_note_label))
                MarkdownText(note)
            }
        }

        CardImage(card.imageFile)

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        val explanation = card.explanation
        if (!explanation.isNullOrBlank()) {
            MarkdownText(explanation)
        } else {
            Button(onClick = onExplain, enabled = !explaining) {
                if (explaining) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(R.string.action_generate_explanation))
            }
        }
        errorText?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        card.contextSentence?.takeIf { it.isNotBlank() }?.let { sentence ->
            Spacer(Modifier.size(4.dp))
            SectionLabel(stringResource(R.string.vocab_example_label))
            Text(
                inlineMarkup(sentence, italicBase = true),
                style = SerifBody.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Words and phrases only: the bank blanks a word in a sentence, which is not what a
        // conjugation or grammar card is.
        if (card.cardKind in FlashcardRepository.LEARNED_BOTH_WAYS) {
            OutlinedButton(onClick = onPractise) { Text(stringResource(R.string.vocab_practise_in_sentences)) }
        }

        if (origin != null) {
            OriginCard(origin = origin, onClick = onOpenOrigin)
        }

        // Secondary spaced-repetition metadata, de-emphasised at the bottom in faint inset boxes —
        // one per direction, since each direction of a word is scheduled on its own.
        directions.forEach { direction ->
            CardMetaSection(
                card = direction,
                history = history[direction.id].orEmpty(),
                onToggleFreeze = { onToggleFreeze(direction) },
                onStartOver = { onStartOver(direction) },
                onSetDueDate = { onSetDueDate(direction, it) },
            )
        }
        // A word, phrase or sentence is learned both ways; one that has a single card (an import
        // that only brought one, say) can be given the other.
        if (directions.size == 1 && card.cardKind in FlashcardRepository.LEARNED_BOTH_WAYS) {
            val missing = if (directions.single().cardDirection == ReviewDirection.TARGET_FIRST) {
                ReviewDirection.NATIVE_FIRST
            } else {
                ReviewDirection.TARGET_FIRST
            }
            TextButton(onClick = onStudyBothDirections) {
                Text(stringResource(R.string.card_add_direction, directionTitle(card, missing)))
            }
        }
        Text(
            stringResource(R.string.card_meta_added_line, relativeTime(card.createdAt)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One direction's spaced-repetition state, derived entirely from its card's schedule fields (no new
 * tracking): status, review count, next due, current interval, and last-reviewed time. Rendered as a
 * faint, muted inset box at the bottom — it's secondary to the word's actual content. The snowflake
 * freezes this direction alone, and the menu beside it holds the two ways of moving its schedule by
 * hand. [history] is this direction's graded reviews, newest first, folded away until asked for.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CardMetaSection(
    card: FlashcardEntity,
    history: List<ReviewLogEntity>,
    onToggleFreeze: () -> Unit,
    onStartOver: () -> Unit,
    onSetDueDate: (Long) -> Unit,
) {
    val now = System.currentTimeMillis()
    var menuOpen by remember { mutableStateOf(false) }
    var confirmStartOver by remember { mutableStateOf(false) }
    var pickingDate by remember { mutableStateOf(false) }
    var historyOpen by remember { mutableStateOf(false) }
    var historyAll by remember { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) { SectionLabel(directionTitle(card, card.cardDirection)) }
                IconButton(onClick = onToggleFreeze) {
                    Icon(
                        Icons.Filled.AcUnit,
                        contentDescription = stringResource(
                            if (card.suspended) R.string.action_unfreeze else R.string.action_freeze,
                        ),
                        modifier = Modifier.size(20.dp),
                        tint = if (card.suspended) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(R.string.card_schedule_menu),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_set_due_date)) },
                            onClick = { menuOpen = false; pickingDate = true },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.card_start_over)) },
                            onClick = { menuOpen = false; confirmStartOver = true },
                        )
                    }
                }
            }
            MetaRow(stringResource(R.string.card_meta_status), statusOf(card).label())
            MetaRow(stringResource(R.string.card_meta_reviews), card.repetitions.toString())
            if (card.lapses > 0) {
                MetaRow(stringResource(R.string.card_meta_lapses), card.lapses.toString())
            }
            MetaRow(
                stringResource(R.string.card_meta_due),
                if (card.dueAt <= now) stringResource(R.string.card_meta_due_now) else relativeTime(card.dueAt),
            )
            if (card.intervalDays >= 1.0) {
                val days = card.intervalDays.roundToInt()
                MetaRow(
                    stringResource(R.string.card_meta_interval),
                    if (days <= 1) stringResource(R.string.card_meta_interval_day)
                    else stringResource(R.string.card_meta_interval_days, days),
                )
            }
            MetaRow(
                stringResource(R.string.card_meta_last_reviewed),
                card.lastReviewedAt?.let { relativeTime(it) } ?: stringResource(R.string.card_meta_never),
            )
            if (card.isLeech) {
                Text(
                    stringResource(R.string.card_leech_note),
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp, top = 4.dp),
                )
            }
            if (history.isNotEmpty()) {
                TextButton(
                    onClick = { historyOpen = !historyOpen },
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(
                            if (historyOpen) R.string.card_history_hide else R.string.card_history_show,
                            history.size,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                if (historyOpen) {
                    val shown = if (historyAll) history else history.take(HISTORY_PREVIEW)
                    shown.forEach { HistoryRow(it) }
                    if (!historyAll && history.size > HISTORY_PREVIEW) {
                        TextButton(
                            onClick = { historyAll = true },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Text(
                                stringResource(R.string.card_history_all),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmStartOver) {
        AlertDialog(
            onDismissRequest = { confirmStartOver = false },
            title = { Text(stringResource(R.string.card_start_over)) },
            text = { Text(stringResource(R.string.card_start_over_message)) },
            confirmButton = {
                TextButton(onClick = { confirmStartOver = false; onStartOver() }) {
                    Text(stringResource(R.string.card_start_over))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmStartOver = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    if (pickingDate) {
        val zone = ZoneId.systemDefault()
        // The picker speaks UTC midnights; the card's due date is a local moment. Open on the day the
        // card is due as the learner would name it, and no earlier than today.
        val opensOn = Instant.ofEpochMilli(maxOf(card.dueAt, now)).atZone(zone).toLocalDate()
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = opensOn.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(
                    enabled = pickerState.selectedDateMillis != null,
                    onClick = {
                        pickingDate = false
                        pickerState.selectedDateMillis?.let { utcMidnight ->
                            val day = Instant.ofEpochMilli(utcMidnight).atZone(ZoneOffset.UTC).toLocalDate()
                            onSetDueDate(day.atStartOfDay(zone).toInstant().toEpochMilli())
                        }
                    },
                ) { Text(stringResource(R.string.card_set_due_date)) }
            },
            dismissButton = {
                TextButton(onClick = { pickingDate = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}

/** How many reviews the history shows before "show all" — enough to see a pattern, not a wall. */
private const val HISTORY_PREVIEW = 10

/** One graded review: when, what was pressed, and where the interval went. */
@Composable
private fun HistoryRow(log: ReviewLogEntity) {
    Row(modifier = Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            DateUtils.formatDateTime(
                LocalContext.current,
                log.reviewedAt,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            ratingLabel(log.rating),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = ratingColor(log.rating),
        )
        Text(
            stringResource(
                R.string.card_history_interval,
                intervalShort(log.lastIntervalDays),
                intervalShort(log.intervalDays),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.width(96.dp),
        )
    }
}

/** An interval in the list's own terse units; a card with none yet reads as a dash, not as "due". */
@Composable
private fun intervalShort(days: Double): String =
    if (days <= 0.0) "–" else formatDueShort((days * ReviewWindows.DAY_MS).toLong())

@Composable
private fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A direction spelled out as the two languages in the order they are met, e.g. "Spanish → English". */
@Composable
private fun directionTitle(card: FlashcardEntity, direction: ReviewDirection): String {
    val target = langDisplayName(card.targetLanguage)
    val native = langDisplayName(card.nativeLanguage)
    return if (direction == ReviewDirection.NATIVE_FIRST) {
        stringResource(R.string.card_direction_title, native, target)
    } else {
        stringResource(R.string.card_direction_title, target, native)
    }
}

private fun langDisplayName(tag: String): String =
    Locale.forLanguageTag(tag).displayLanguage.replaceFirstChar { it.uppercase() }.ifBlank { tag }

private fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

/** Names where the word was saved from; a card that opens it unless the source is not somewhere to go back to. */
@Composable
private fun OriginCard(origin: WordOrigin, onClick: () -> Unit) {
    val (icon, label) = when (origin) {
        is WordOrigin.Chat -> Icons.Filled.ChatBubbleOutline to stringResource(R.string.vocab_seen_in_chat, origin.title)
        is WordOrigin.Text -> Icons.Filled.Article to stringResource(R.string.vocab_seen_in_text, origin.title)
        is WordOrigin.Book -> Icons.AutoMirrored.Filled.MenuBook to stringResource(R.string.vocab_seen_in_book, origin.title)
        WordOrigin.Routine -> Icons.Filled.SelfImprovement to stringResource(R.string.vocab_seen_in_routine)
    }
    val opens = origin != WordOrigin.Routine
    OutlinedCard(modifier = Modifier.fillMaxWidth().then(if (opens) Modifier.clickable(onClick = onClick) else Modifier)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (opens) {
                    Text(
                        stringResource(R.string.vocab_return_to_context),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
