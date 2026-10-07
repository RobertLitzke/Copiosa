package com.copiosa.ui.vocab

import android.media.MediaPlayer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.anki.AnkiMedia
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.repository.ImageCandidate
import com.copiosa.domain.model.Cloze
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.srs.SpacedRepetitionScheduler
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.components.MarkdownText
import com.copiosa.ui.components.TrackFocusTime
import com.copiosa.ui.components.flagForLanguage
import com.copiosa.ui.components.inlineMarkup
import com.copiosa.ui.components.rememberRevealTapGuard
import com.copiosa.ui.components.stripInlineMarkdown
import kotlin.random.Random
import com.copiosa.ui.theme.SerifBody
import com.copiosa.ui.theme.SerifTitle
import java.io.File

/**
 * Spaced-repetition study flow for a sized session, reached from the Léxico Review button.
 *
 * Reused verbatim as a routine's flashcard step: [topFrame] renders the routine's progress above the
 * header, [limit] configures the card count (instead of the nav-arg / prompt), and a non-null
 * [onFinished] turns the end-of-session screen into a "Continue" that advances the routine. The
 * frequent-word drill is that same step with [freqTop] set, narrowing the pool to the commonest words.
 */
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
    limit: Int? = null,
    freqTop: Int? = null,
    topFrame: (@Composable () -> Unit)? = null,
    onFinished: (() -> Unit)? = null,
    /** Opens a card's detail screen from the reveal. Null where leaving would abandon the session (a routine step). */
    onOpenDetail: ((Long) -> Unit)? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Back from a card's detail screen it may have been edited, explained or deleted; see refreshCurrent.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshCurrent() }
    // The routine player embeds this screen as a step and tracks ROUTINE time itself; only the
    // standalone review session records REVIEW time.
    TrackFocusTime(FocusActivityType.REVIEW, enabled = onFinished == null)
    LaunchedEffect(Unit) { viewModel.start(limit, freqTop) }
    Column(modifier = Modifier.fillMaxSize()) {
        topFrame?.invoke()
        ReviewHeader(
            position = state.position,
            total = state.total,
            canUndo = state.canUndo,
            onUndo = viewModel::undo,
            onBack = onBack,
        )
        when {
            state.loading -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            state.current == null -> Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Finishing a session says nothing about what is still due — only an empty session does.
                Text(
                    stringResource(
                        if (state.nothingToStudy) R.string.review_done else R.string.review_session_complete,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.summary?.let { SessionSummary(it) }
                if (onFinished != null) {
                    Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.routine_continue))
                    }
                } else {
                    // Standalone: straight into the next batch of what is still due, or out. Without
                    // this, another session meant back, Review, and the size prompt again.
                    ReviewMoreRow(
                        sizes = state.nextSessionSizes,
                        repeatSize = state.nextSessionSize,
                        onPick = viewModel::startAnother,
                    )
                    TextButton(onClick = onBack) {
                        Text(stringResource(R.string.action_done))
                    }
                }
            }
            else -> ReviewBody(
                card = state.current!!,
                previews = state.previews,
                // Changes on every advance, so the reveal state resets even when an "Again" card is
                // re-shown immediately (same id) — e.g. failing the last remaining card in the session.
                presentationKey = state.position,
                onReview = viewModel::rate,
                onToggleMark = viewModel::toggleMark,
                onEdit = viewModel::editCard,
                onDelete = viewModel::deleteCard,
                onSearchImages = viewModel::searchImages,
                onAttachImage = viewModel::attachImage,
                onRemoveImage = viewModel::removeImage,
                onOpenDetail = onOpenDetail,
            )
        }
    }
}

/**
 * The follow-on sessions on offer, side by side: a few sizes, so a long session can be topped up
 * with a short one rather than only repeated. A lone option spells itself out ("Review 7 more");
 * several share one caption and show just their counts, which is what lets three fit on a row.
 * [repeatSize] — the session just finished, over again — is the filled button; the alternatives
 * around it are outlined.
 */
@Composable
private fun ReviewMoreRow(sizes: List<Int>, repeatSize: Int, onPick: (Int) -> Unit) {
    if (sizes.isEmpty()) return
    if (sizes.size == 1) {
        Button(onClick = { onPick(sizes.single()) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.review_more, sizes.single()))
        }
        return
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.review_more_label),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sizes.forEach { size ->
                val label = @Composable { Text(stringResource(R.string.review_more_count, size), maxLines = 1) }
                if (size == repeatSize) {
                    Button(onClick = { onPick(size) }, modifier = Modifier.weight(1f)) { label() }
                } else {
                    OutlinedButton(onClick = { onPick(size) }, modifier = Modifier.weight(1f)) { label() }
                }
            }
        }
    }
}

/** Back button, title, undo (for the last grade), and a "n / total" progress counter. */
@Composable
private fun ReviewHeader(position: Int, total: Int, canUndo: Boolean, onUndo: () -> Unit, onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 20.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
        }
        Text(
            stringResource(R.string.tab_review),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (canUndo) {
            IconButton(onClick = onUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.review_undo),
                )
            }
        }
        if (total > 0) {
            Text(
                stringResource(R.string.review_progress, position, total),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The end-of-session recap: how the session went, in three numbers you can read at a glance —
 * first-try accuracy, how you answered, and how well-established the cards were coming in. Only the
 * last of those needs explaining: the scheduler keeps no confidence score, so a card's existing
 * interval stands in for one.
 */
@Composable
private fun SessionSummary(summary: ReviewSessionSummary) {
    val scheme = MaterialTheme.colorScheme
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.review_summary_first_try, summary.firstTryPercent),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(
                    R.string.review_summary_first_try_detail,
                    summary.firstTryCorrect,
                    summary.cards,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            HorizontalDivider(color = scheme.outlineVariant)
            Distribution(
                title = stringResource(R.string.review_summary_results),
                slices = listOf(
                    Slice(stringResource(R.string.rating_again), summary.count(ReviewRating.AGAIN), scheme.error),
                    Slice(stringResource(R.string.rating_hard), summary.count(ReviewRating.HARD), scheme.tertiary),
                    Slice(stringResource(R.string.rating_good), summary.count(ReviewRating.GOOD), scheme.primary),
                    Slice(stringResource(R.string.rating_easy), summary.count(ReviewRating.EASY), scheme.secondary),
                ),
            )
            Distribution(
                title = stringResource(R.string.review_summary_incoming),
                // One hue ramped from faint to solid, so the buckets read as the ordered scale they are.
                slices = listOf(
                    IntervalBucket.NEW to R.string.review_bucket_new,
                    IntervalBucket.UNDER_DAY to R.string.review_bucket_day,
                    IntervalBucket.UNDER_WEEK to R.string.review_bucket_week,
                    IntervalBucket.UNDER_MONTH to R.string.review_bucket_month,
                    IntervalBucket.MONTH_PLUS to R.string.review_bucket_mature,
                ).mapIndexed { i, (bucket, label) ->
                    Slice(
                        stringResource(label),
                        summary.intervalCounts[bucket] ?: 0,
                        scheme.primary.copy(alpha = 0.35f + 0.65f * i / 4f),
                    )
                },
            )
            Text(
                stringResource(R.string.review_summary_counts, summary.cards, summary.grades),
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

private fun ReviewSessionSummary.count(rating: ReviewRating): Int = ratingCounts[rating] ?: 0

/** One band of a [Distribution]: its label, how many fell in it, and the colour that stands for it. */
private data class Slice(val label: String, val count: Int, val color: Color)

/**
 * A titled stacked bar plus a legend. The legend names every slice, including empty ones — "0 Again"
 * is worth seeing — but only non-empty slices take up bar width.
 */
@Composable
private fun Distribution(title: String, slices: List<Slice>) {
    val total = slices.sumOf { it.count }
    if (total == 0) return
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(scheme.surfaceVariant),
        ) {
            slices.filter { it.count > 0 }.forEach { slice ->
                Box(
                    modifier = Modifier
                        .weight(slice.count.toFloat())
                        .fillMaxHeight()
                        .background(slice.color),
                )
            }
        }
        Text(
            buildAnnotatedString {
                slices.forEachIndexed { i, slice ->
                    if (i > 0) append("  ")
                    val empty = slice.count == 0
                    withStyle(
                        SpanStyle(
                            color = if (empty) scheme.onSurfaceVariant.copy(alpha = 0.5f) else slice.color,
                            fontWeight = if (empty) FontWeight.Normal else FontWeight.SemiBold,
                        ),
                    ) {
                        append("${slice.label} ${slice.count}")
                    }
                }
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun ColumnScope.ReviewBody(
    card: FlashcardEntity,
    previews: Map<ReviewRating, SpacedRepetitionScheduler.Preview>,
    presentationKey: Int,
    onReview: (ReviewRating) -> Unit,
    onToggleMark: () -> Unit,
    onEdit: (CardEdits) -> Unit,
    onDelete: () -> Unit,
    onSearchImages: suspend (String) -> List<ImageCandidate>,
    onAttachImage: suspend (ImageCandidate) -> String,
    onRemoveImage: () -> Unit,
    onOpenDetail: ((Long) -> Unit)?,
) {
    // Saveable: opening the card's detail screen takes this one out of composition, and the answer
    // the learner was looking at should still be showing when they come back.
    var revealed by rememberSaveable(presentationKey) { mutableStateOf(false) }
    var editing by remember(presentationKey) { mutableStateOf(false) }
    var explanationShown by rememberSaveable(presentationKey) { mutableStateOf(false) }
    // The grades appear where "Show answer" was, so a double tap on it must not pick one.
    val revealGuard = rememberRevealTapGuard(presentationKey)
    val onGrade: (ReviewRating) -> Unit = { if (revealGuard.settled()) onReview(it) }

    // The card fills the top (scrollable) region; the actions are pinned to the bottom for thumb reach.
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val clozeText = Cloze.source(card.front, card.back)
                // The concept image rides with the native-language face: when native is the prompt it
                // supplements the question up front (so the learner ties the target word to the concept,
                // not to a specific native word); when native is the answer it appears on reveal. Either
                // way it never gives away a target-language answer.
                var imageShown = false
                if (clozeText != null) {
                    ClozeFace(card, clozeText, revealed = revealed)
                } else {
                    // BOTH alternates the starting side per card so both directions get practiced;
                    // the pinned directions always start from the same side.
                    val showTargetFirst = remember(card.id) {
                        when (card.reviewDirection) {
                            ReviewDirection.TARGET_FIRST -> true
                            ReviewDirection.NATIVE_FIRST -> false
                            ReviewDirection.BOTH -> Random.nextBoolean()
                        }
                    }
                    // The image sits above the native-language face. When native is the prompt it
                    // leads the card; when native is the answer it appears above it on reveal.
                    if (!showTargetFirst) {
                        CardImage(card.imageFile)
                        imageShown = true
                    }
                    CardFace(card, target = showTargetFirst, big = true)
                    if (revealed) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (!imageShown) {
                            CardImage(card.imageFile)
                            imageShown = true
                        }
                        CardFace(card, target = !showTargetFirst, big = false)
                    }
                }
                if (revealed) {
                    card.contextSentence?.let {
                        Text(
                            inlineMarkup(it, italicBase = true),
                            style = SerifBody.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CardAudioButton(card)
                    Enrichment(
                        card = card,
                        explanationShown = explanationShown,
                        onToggleExplanation = { explanationShown = !explanationShown },
                    )
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!revealed) {
            Button(
                onClick = {
                    revealGuard.mark()
                    revealed = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.show_answer))
            }
        } else {
            // Quick per-card actions before grading: mark the card, or edit it to fix a spotted typo.
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                IconButton(onClick = onToggleMark) {
                    Icon(
                        if (card.marked) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = stringResource(
                            if (card.marked) R.string.review_unmark else R.string.review_mark,
                        ),
                    )
                }
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.action_edit))
                }
                if (onOpenDetail != null) {
                    IconButton(onClick = { onOpenDetail(card.id) }) {
                        Icon(Icons.Filled.OpenInNew, contentDescription = stringResource(R.string.review_open_card))
                    }
                }
            }
            // Captured once when the buttons appear, so all four intervals read against one clock.
            val now = remember(presentationKey) { System.currentTimeMillis() }
            RatingRow(
                listOf(
                    ReviewRating.AGAIN to R.string.rating_again,
                    ReviewRating.HARD to R.string.rating_hard,
                ),
                previews = previews,
                now = now,
                onReview = onGrade,
            )
            RatingRow(
                listOf(
                    ReviewRating.GOOD to R.string.rating_good,
                    ReviewRating.EASY to R.string.rating_easy,
                ),
                previews = previews,
                now = now,
                onReview = onGrade,
            )
        }
    }

    if (editing) {
        EditCardDialog(
            card = card,
            onSave = onEdit,
            onSearchImages = onSearchImages,
            onAttachImage = onAttachImage,
            onRemoveImage = onRemoveImage,
            onDismiss = { editing = false },
            onDelete = onDelete,
        )
    }
}

/**
 * What the model and the learner have added to a card, shown once its answer is: the usage note
 * straight away (it is a line or two, and often the difference between two near-synonyms), the
 * longer explanation behind a toggle so the grading buttons stay in reach. Draws nothing for a card
 * with neither; the detail screen, one tap away, is where an explanation gets generated.
 */
@Composable
private fun Enrichment(card: FlashcardEntity, explanationShown: Boolean, onToggleExplanation: () -> Unit) {
    val note = card.usageNote?.takeIf { it.isNotBlank() }
    val explanation = card.explanation?.takeIf { it.isNotBlank() }
    if (note == null && explanation == null) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (note != null) {
            Text(
                stringResource(R.string.vocab_usage_note_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MarkdownText(note)
        }
        if (explanation != null) {
            TextButton(onClick = onToggleExplanation, modifier = Modifier.align(Alignment.Start)) {
                Text(
                    stringResource(
                        if (explanationShown) R.string.review_hide_explanation else R.string.review_show_explanation,
                    ),
                )
            }
            if (explanationShown) MarkdownText(explanation)
        }
    }
}

/**
 * One side of the review card: a language flag + the term, plus (when this is the target-language
 * side) a gender ("el"/"la") chip, region chip, and any altForms shown as accepted synonyms. The
 * flag disambiguates words identical across the two languages (e.g. "son"). [target] selects which
 * language side to render (the review direction decides which side is shown first).
 */
@Composable
private fun CardFace(card: FlashcardEntity, target: Boolean, big: Boolean) {
    // [front] holds the target term when frontIsTarget, otherwise the native term.
    val text = if (target == card.frontIsTarget) card.front else card.back
    val lang = if (target) card.targetLanguage else card.nativeLanguage
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // The flag sits quietly at the left edge, separated from the word (which stays centered), so
        // it's a discreet language cue rather than crowding the term.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                flagForLanguage(lang),
                fontSize = if (big) 16.sp else 14.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                // Headwords are plain — strip any stored Markdown so no stray markers show mid-review.
                stripInlineMarkdown(text),
                style = if (big) SerifTitle.copy(fontSize = 26.sp, lineHeight = 32.sp) else SerifBody,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            // Balances the flag's width so the word stays visually centered in the card.
            Spacer(Modifier.width(if (big) 24.dp else 22.dp))
        }
        // The country a word is specific to (if set) sits just below the flag, on the target side.
        if (target) {
            card.country?.takeIf { it.isNotBlank() }?.let {
                Text(
                    countryLabel(it),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Start),
                )
            }
        }
        // Gender and part of speech share one line of chips. The article belongs to the target side
        // only; the part of speech to the question side, where it disambiguates words whose meaning
        // (and thus translation) differs by role — e.g. a term that's both a noun and a verb.
        val gendered = target && genderArticle(card.gender, card.targetLanguage) != null
        val pos = card.partOfSpeech?.takeIf { big && it.isNotBlank() }
        if (gendered || pos != null) {
            Row(
                modifier = Modifier.align(Alignment.Start),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (gendered) GenderChip(card.gender, card.targetLanguage)
                pos?.let { PosChip(it) }
            }
        }
        if (target) {
            card.altForms?.takeIf { it.isNotBlank() }?.let {
                Text(
                    "= $it",
                    style = SerifBody.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * A cloze card: the target-language sentence with the active deletion blanked. On reveal the sentence
 * is shown fully filled, the answer word(s) emphasized, and any extra/hint field below.
 */
@Composable
private fun ClozeFace(card: FlashcardEntity, clozeText: String, revealed: Boolean) {
    val activeOrd = card.ankiOrd?.plus(1)
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(flagForLanguage(card.targetLanguage), fontSize = 16.sp)
        Spacer(Modifier.width(8.dp))
        Text(
            Cloze.question(clozeText, activeOrd),
            style = SerifTitle.copy(fontSize = 22.sp, lineHeight = 30.sp),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(24.dp))
    }
    if (revealed) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            Cloze.answer(clozeText),
            style = SerifBody,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        val answers = Cloze.activeAnswers(clozeText, activeOrd)
        if (answers.isNotEmpty()) {
            Text(
                answers.joinToString(", "),
                style = SerifBody.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        // The non-cloze side (an Anki "Extra"/hint field), if any.
        val extra = if (clozeText == card.front) card.back else card.front
        if (extra.isNotBlank() && !Cloze.hasCloze(extra)) {
            Text(
                extra,
                style = SerifBody.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Audio playback (a target-language pronunciation carried over from an imported Anki card). Gated to
 * reveal by the caller, since the audio would otherwise give away the target word.
 */
@Composable
private fun CardAudioButton(card: FlashcardEntity) {
    val context = LocalContext.current
    val name = card.audioFile ?: return
    val file = remember(card.id, name) { AnkiMedia.file(context, name) }
    if (file.exists()) {
        OutlinedButton(onClick = { playAudio(file) }) {
            Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_play_audio))
        }
    }
}

@Composable
private fun RatingRow(
    items: List<Pair<ReviewRating, Int>>,
    previews: Map<ReviewRating, SpacedRepetitionScheduler.Preview>,
    now: Long,
    onReview: (ReviewRating) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (rating, labelRes) ->
            OutlinedButton(onClick = { onReview(rating) }, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(labelRes))
                    // A quiet hint of where this grade sends the card — secondary to the label.
                    // A grade that leaves the card in (re)learning shows the step it lands on *and*
                    // the interval waiting on the far side of it, so a short first number reads as
                    // "back shortly, then a week" rather than as a card knocked to square one.
                    previews[rating]?.let { preview ->
                        val next = formatDueShort(preview.dueAt - now)
                        Text(
                            preview.followUpAt?.let {
                                stringResource(
                                    R.string.review_preview_then,
                                    next,
                                    formatDueShort(it - preview.dueAt),
                                )
                            } ?: next,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** Fire-and-forget playback of a local media file; the player releases itself on completion. */
private fun playAudio(file: File) {
    runCatching {
        MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { it.release() }
            setOnErrorListener { mp, _, _ -> mp.release(); true }
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }
}
