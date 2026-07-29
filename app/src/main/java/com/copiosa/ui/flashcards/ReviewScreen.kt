package com.copiosa.ui.flashcards

import android.media.MediaPlayer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.copiosa.R
import com.copiosa.data.anki.AnkiMedia
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.repository.ImageCandidate
import com.copiosa.domain.model.Cloze
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.components.TrackFocusTime
import com.copiosa.ui.components.flagForLanguage
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
 * [onFinished] turns the end-of-session screen into a "Continue" that advances the routine.
 */
@Composable
fun ReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
    limit: Int? = null,
    topFrame: (@Composable () -> Unit)? = null,
    onFinished: (() -> Unit)? = null,
) {
    val state by viewModel.state.collectAsState()
    // The routine player embeds this screen as a step and tracks ROUTINE time itself; only the
    // standalone review session records REVIEW time.
    TrackFocusTime(FocusActivityType.REVIEW, enabled = onFinished == null)
    LaunchedEffect(Unit) { viewModel.start(limit) }
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
                modifier = Modifier.fillMaxWidth().weight(1f).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.review_done), style = MaterialTheme.typography.titleMedium)
                if (onFinished != null) {
                    Button(onClick = onFinished, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.routine_continue))
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
            )
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

@Composable
private fun ColumnScope.ReviewBody(
    card: FlashcardEntity,
    previews: Map<ReviewRating, Long>,
    presentationKey: Int,
    onReview: (ReviewRating) -> Unit,
    onToggleMark: () -> Unit,
    onEdit: (CardEdits) -> Unit,
    onDelete: () -> Unit,
    onSearchImages: suspend (String) -> List<ImageCandidate>,
    onAttachImage: suspend (ImageCandidate) -> String,
    onRemoveImage: () -> Unit,
) {
    var revealed by remember(presentationKey) { mutableStateOf(false) }
    var editing by remember(presentationKey) { mutableStateOf(false) }

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
                            it,
                            style = SerifBody.copy(fontStyle = FontStyle.Italic),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CardAudioButton(card)
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
            Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) {
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
                onReview = onReview,
            )
            RatingRow(
                listOf(
                    ReviewRating.GOOD to R.string.rating_good,
                    ReviewRating.EASY to R.string.rating_easy,
                ),
                previews = previews,
                now = now,
                onReview = onReview,
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
        // On the question side, the part of speech disambiguates words whose meaning (and thus
        // translation) differs by role — e.g. a term that's both a noun and a verb.
        if (big) {
            card.partOfSpeech?.takeIf { it.isNotBlank() }?.let {
                PosChip(it, modifier = Modifier.align(Alignment.Start))
            }
        }
        if (target) {
            val article = genderArticle(card.gender, card.targetLanguage)
            if (article != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GenderChip(card.gender, card.targetLanguage)
                }
            }
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
    previews: Map<ReviewRating, Long>,
    now: Long,
    onReview: (ReviewRating) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { (rating, labelRes) ->
            OutlinedButton(onClick = { onReview(rating) }, modifier = Modifier.weight(1f)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(labelRes))
                    // A quiet hint of where this grade sends the card — secondary to the label.
                    previews[rating]?.let {
                        Text(
                            formatDueShort(it - now),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
