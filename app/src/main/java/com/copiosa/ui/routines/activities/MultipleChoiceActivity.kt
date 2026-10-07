package com.copiosa.ui.routines.activities

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copiosa.R
import com.copiosa.ui.components.SegmentProgress
import com.copiosa.ui.theme.SourceSerif

internal data class ChoiceItem(
    val sentence: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String?,
    /** Native-language rendering of the whole sentence, if one is known. */
    val translation: String? = null,
    /** Short meanings, parallel to [options]; a null entry means "no dictionary answer for it". */
    val glosses: List<String?> = emptyList(),
) {
    val answer: String get() = options[answerIndex]
}

/**
 * Generic multiple-choice runner shared by the PI, contrast-cloze, and MC-cloze activities, by the
 * course player's cloze/PI steps, and by the standalone cloze bank. [onAnswer] carries the item's
 * index so a host that needs to know *which* item was missed (the course's end-of-lesson diagnosis)
 * can look it up.
 *
 * The screen is three bands: an optional header, a scrolling body of sentence and options, and —
 * once an answer is in — a feedback panel docked to the bottom. Docking it matters: the panel is
 * the part worth reading after a miss, and on a long item a single scrolling column pushes it off
 * the screen exactly when it has something to say.
 *
 * [afterReveal] is an optional slot rendered at the foot of the feedback panel once an answer is
 * in. It exists so the cloze bank can offer its report-a-problem control without teaching this
 * runner anything about the bank; hosts that don't need it pass nothing and are unaffected.
 *
 * [title] opts a host into the runner's own header, with per-question progress ticks. Hosts that
 * draw their own chrome — the course and routine players, whose progress counts *segments* rather
 * than questions — pass nothing and keep theirs.
 */
@Composable
internal fun MultipleChoiceActivity(
    prompt: String,
    items: List<ChoiceItem>,
    onAnswer: (index: Int, correct: Boolean, latencyMs: Long) -> Unit,
    onComplete: () -> Unit,
    afterReveal: (@Composable (index: Int) -> Unit)? = null,
    modifier: Modifier = Modifier,
    // The header rule and the feedback panel run edge to edge, so the runner pads its middle band
    // itself rather than being padded from outside. Hosts that already inset their content — the
    // routine and course players, which pad every activity alike — pass zero.
    contentPadding: Dp = 20.dp,
    title: String? = null,
    subtitle: String? = null,
    onExit: (() -> Unit)? = null,
    allowSkip: Boolean = false,
    allowAgainLater: Boolean = false,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }
    // The queue is item indices, not items, so "again later" can push one back onto the end without
    // copying it. `at` walks the queue; `order[at]` is the item actually on screen.
    val order = remember(items) { mutableStateListOf<Int>().apply { addAll(items.indices) } }
    // Parallel to [order], not to [items]: a re-queued item occupies two places in the queue and
    // each deserves its own tick, so how it went the first time stays on screen.
    val outcomes = remember(items) { mutableStateListOf<Boolean?>().apply { repeat(items.size) { add(null) } } }
    // Indices already sent to onAnswer. A re-queued item is shown again but never graded twice —
    // on the cloze bank each grade writes an FSRS review, and a second one would be a lie.
    val reported = remember(items) { mutableStateListOf<Int>() }

    var at by remember(items) { mutableIntStateOf(0) }
    var selected by remember(items) { mutableStateOf<Int?>(null) }
    var showTranslation by remember(items) { mutableStateOf(false) }
    var shownAt by remember(items) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) {
        selected = null
        showTranslation = false
        shownAt = System.currentTimeMillis()
    }

    val index = order.getOrElse(at) { 0 }
    val item = items[index]
    val picked = selected
    val correct = picked == item.answerIndex

    // Keyed on the question, so a long item scrolled to its foot doesn't open the next one
    // halfway down.
    val scroll = remember(at) { ScrollState(0) }

    fun advance() {
        if (at + 1 >= order.size) onComplete() else at++
    }

    Column(modifier.fillMaxSize()) {
        if (title != null) {
            QuestionHeader(
                title = title,
                subtitle = subtitle,
                onExit = onExit,
                index = at,
                total = order.size,
                outcomes = outcomes,
                // Before an answer the escape hatch is "skip"; after a wrong one it becomes
                // "again later", which is the same gesture with the item kept rather than lost.
                action = when {
                    picked == null && allowSkip -> HeaderAction(R.string.action_skip, filled = false) { advance() }
                    picked != null && !correct && allowAgainLater -> HeaderAction(
                        R.string.question_again_later,
                        filled = true,
                    ) {
                        order.add(index)
                        outcomes.add(null)
                        advance()
                    }
                    else -> null
                },
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = contentPadding),
        ) {
            Spacer(Modifier.height(12.dp))
            SectionLabel(prompt)
            Spacer(Modifier.height(10.dp))
            SentenceLine(item = item, picked = picked)

            if (item.translation != null && (showTranslation || picked != null)) {
                Spacer(Modifier.height(10.dp))
                Text(
                    item.translation,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = SourceSerif,
                        fontStyle = FontStyle.Italic,
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(24.dp))
            SectionLabel(
                stringResource(R.string.question_candidates),
                trailing = if (picked == null) stringResource(R.string.question_one_fits) else null,
            )
            Spacer(Modifier.height(4.dp))
            item.options.forEachIndexed { idx, option ->
                if (idx > 0) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
                OptionRow(
                    letter = ('A' + idx),
                    option = option,
                    isAnswer = idx == item.answerIndex,
                    isPick = idx == picked,
                    revealed = picked != null,
                    onClick = {
                        selected = idx
                        val right = idx == item.answerIndex
                        outcomes[at] = right
                        if (index !in reported) {
                            reported.add(index)
                            onAnswer(index, right, System.currentTimeMillis() - shownAt)
                        }
                    },
                )
            }

            // Offered only while it would still cost something to look — once the answer is in, the
            // translation is shown outright above.
            if (item.translation != null && picked == null && !showTranslation) {
                Spacer(Modifier.height(4.dp))
                TextButton(
                    onClick = { showTranslation = true },
                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                ) {
                    Text(
                        stringResource(R.string.question_translate),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (picked != null) {
            FeedbackPanel(
                item = item,
                picked = picked,
                correct = correct,
                contentPadding = contentPadding,
                onContinue = ::advance,
                afterReveal = afterReveal?.let { slot -> { slot(index) } },
            )
        }
    }
}

// --- header -----------------------------------------------------------------

internal data class HeaderAction(val label: Int, val filled: Boolean, val onClick: () -> Unit)

/**
 * Close, what this is, and how far in — with the ticks below coloured by how each question went.
 *
 * The subtitle is where a sitting says what it is *about* ("-ar verbs · drill 4 of 9"); with no
 * category to name it degrades to the count alone, which is still the thing a learner most wants
 * to know at the top of a question.
 */
@Composable
internal fun QuestionHeader(
    title: String,
    subtitle: String?,
    onExit: (() -> Unit)?,
    index: Int,
    total: Int,
    outcomes: List<Boolean?>,
    action: HeaderAction?,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 4.dp),
        ) {
            if (onExit != null) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_back))
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.2.sp,
                    color = MaterialTheme.colorScheme.primary,
                )
                val line = subtitle?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.question_position_labelled, it, index + 1, total) }
                    ?: stringResource(R.string.question_position, index + 1, total)
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            action?.let {
                val label = stringResource(it.label)
                if (it.filled) {
                    Button(
                        onClick = it.onClick,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.heightIn(min = 34.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary,
                            contentColor = MaterialTheme.colorScheme.onTertiary,
                        ),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                } else {
                    TextButton(
                        onClick = it.onClick,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) { Text(label, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
        SegmentProgress(
            index = index,
            total = total,
            outcomes = outcomes,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

// --- sentence ---------------------------------------------------------------

/**
 * The sentence with its blank, filled in once an answer is given.
 *
 * Seeing the word land in the slot is the point: a list of options graded elsewhere on the screen
 * never shows the learner the sentence they were actually building. On a miss both words appear —
 * theirs struck through, the right one after it — so the correction is read in place rather than
 * inferred from two coloured rows further down.
 */
@Composable
private fun SentenceLine(item: ChoiceItem, picked: Int?) {
    val scheme = MaterialTheme.colorScheme
    val split = remember(item.sentence) { splitOnBlank(item.sentence) }
    val serif = MaterialTheme.typography.headlineSmall.copy(
        fontFamily = SourceSerif,
        lineHeight = 38.sp,
    )

    if (split == null) {
        // A PI item ("Who or when?") has no blank — the sentence is whole and nothing is inlined.
        Text(item.sentence, style = serif)
        return
    }
    val (before, after) = split
    val text = buildAnnotatedString {
        append(before)
        when {
            picked == null -> withBlankRule(scheme.primary)
            picked == item.answerIndex -> withStyle(
                SpanStyle(
                    fontStyle = FontStyle.Italic,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                    color = scheme.primary,
                ),
            ) { append(item.answer) }
            else -> {
                withStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        textDecoration = TextDecoration.LineThrough,
                        color = scheme.onSurfaceVariant.copy(alpha = 0.6f),
                    ),
                ) { append(item.options[picked]) }
                append(" ")
                withStyle(
                    SpanStyle(
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.SemiBold,
                        textDecoration = TextDecoration.Underline,
                        color = scheme.primary,
                    ),
                ) { append(item.answer) }
            }
        }
        append(after)
    }
    Text(text, style = serif)
}

/** An empty slot has to be *visible*, so the unanswered blank is drawn as an underlined rule. */
private fun AnnotatedString.Builder.withBlankRule(colour: Color) {
    withStyle(SpanStyle(textDecoration = TextDecoration.Underline, color = colour)) {
        append("   ")
    }
}

/**
 * Splits a sentence around its blank, or null when it has none.
 *
 * The blank token is not uniform across the app — the cloze bank writes five underscores, the
 * routine and course plans write three — so this matches a run of them rather than a literal, and
 * ignores the stray pair that turns up inside transcribed text.
 */
internal fun splitOnBlank(sentence: String): Pair<String, String>? {
    val match = BLANK_RUN.find(sentence) ?: return null
    var before = sentence.substring(0, match.range.first)
    var after = sentence.substring(match.range.last + 1)

    // Mined sentences often arrive with the studied word emphasised — "un *crisol* de culturas" —
    // and blanking the word strands the markers around the gap. Only a *matched* pair is eaten, so
    // a lone asterisk that means something stays put.
    val paired = minOf(before.length - before.trimEnd('*').length, after.length - after.trimStart('*').length)
    if (paired > 0) {
        before = before.dropLast(paired)
        after = after.drop(paired)
    }
    return before to after
}

private val BLANK_RUN = Regex("_{3,}")

// --- options ----------------------------------------------------------------

/**
 * One candidate: a lettered badge and the word.
 *
 * What the words *mean* is deliberately not here — it lives in the feedback panel below, where the
 * one worth reading sits beside the one that was picked. Repeating it on the rows as well says the
 * same thing twice and buries the comparison in a vocabulary list.
 */
@Composable
private fun OptionRow(
    letter: Char,
    option: String,
    isAnswer: Boolean,
    isPick: Boolean,
    revealed: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val wrongPick = revealed && isPick && !isAnswer
    val marked = revealed && (isAnswer || wrongPick)
    // Amber for a miss rather than red: at this point the learner is being taught, not scored.
    val accent = when {
        revealed && isAnswer -> scheme.primary
        wrongPick -> scheme.tertiary
        else -> Color.Transparent
    }
    val faded = revealed && !marked
    val textColour = if (faded) scheme.onSurfaceVariant.copy(alpha = 0.45f) else scheme.onSurface

    Row(
        Modifier
            .fillMaxWidth()
            .background(if (marked) accent.copy(alpha = 0.10f) else Color.Transparent)
            .drawBehind { drawRect(accent, size = size.copy(width = 3.dp.toPx())) }
            .clickable(enabled = !revealed, onClick = onClick)
            .heightIn(min = 50.dp)
            .padding(start = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OptionBadge(letter = letter, isAnswer = isAnswer, wrongPick = wrongPick, revealed = revealed, faded = faded)
        Spacer(Modifier.width(14.dp))
        Text(
            option,
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = SourceSerif,
                fontStyle = FontStyle.Italic,
            ),
            color = textColour,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A/B/C/D before the answer; a filled tick or cross on the two rows that matter after it. */
@Composable
private fun OptionBadge(letter: Char, isAnswer: Boolean, wrongPick: Boolean, revealed: Boolean, faded: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val marked = revealed && (isAnswer || wrongPick)
    val fill = when {
        !marked -> Color.Transparent
        isAnswer -> scheme.primary
        else -> scheme.tertiary
    }
    Box(
        Modifier
            .size(22.dp)
            .background(fill, RoundedCornerShape(5.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            marked -> Icon(
                if (isAnswer) Icons.Filled.Check else Icons.Filled.Close,
                contentDescription = null,
                tint = if (isAnswer) scheme.onPrimary else scheme.onTertiary,
                modifier = Modifier.size(15.dp),
            )
            else -> Text(
                letter.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = if (faded) {
                    scheme.onSurfaceVariant.copy(alpha = 0.45f)
                } else {
                    scheme.onSurfaceVariant
                },
            )
        }
    }
}

// --- feedback ---------------------------------------------------------------

/**
 * What just happened, docked below the options so a long item can't push it off the screen.
 *
 * The headline says only whether it was right; the teaching is underneath it. A miss gets the two
 * words side by side with their meanings, which is the comparison the learner needs and the one a
 * single "the answer was X" line never makes.
 */
@Composable
private fun FeedbackPanel(
    item: ChoiceItem,
    picked: Int,
    correct: Boolean,
    contentPadding: Dp,
    onContinue: () -> Unit,
    afterReveal: (@Composable () -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (correct) scheme.primary else scheme.tertiary
    Column(
        Modifier
            .fillMaxWidth()
            .background(scheme.surface),
    ) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(accent))
        Column(Modifier.padding(horizontal = contentPadding, vertical = 14.dp)) {
            Text(
                stringResource(if (correct) R.string.question_right else R.string.question_not_quite),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
            item.explanation?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
            MeaningBlock(item = item, picked = picked, correct = correct)
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.routine_continue))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            afterReveal?.let {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { it() }
            }
        }
    }
}

/**
 * What the words mean: the answer alone when it was right, the wrong pick beside it when it wasn't.
 *
 * The comparison is the teaching. Two words that both fit the grammar of the sentence — which is
 * how the distractors are chosen — are separated by meaning and nothing else, so setting the two
 * meanings side by side is the whole correction.
 *
 * A miss needs both halves to be worth drawing; with only one gloss known it falls back to that
 * word on its own rather than to a half-empty table. With no dictionary pack downloaded there is
 * nothing to say and the panel is the headline and Continue, which is a quality loss and not a
 * broken screen.
 */
@Composable
private fun MeaningBlock(item: ChoiceItem, picked: Int, correct: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val rightGloss = item.glosses.getOrNull(item.answerIndex)
    val wrongGloss = if (correct) null else item.glosses.getOrNull(picked)
    if (rightGloss == null && wrongGloss == null) return

    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
    Spacer(Modifier.height(10.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (wrongGloss != null) {
            ContrastColumn(
                word = item.options[picked],
                gloss = wrongGloss,
                struck = true,
                colour = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        if (rightGloss != null) {
            ContrastColumn(
                word = item.answer,
                gloss = rightGloss,
                struck = false,
                colour = scheme.primary,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ContrastColumn(
    word: String,
    gloss: String,
    struck: Boolean,
    colour: Color,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(
            word,
            style = MaterialTheme.typography.bodyLarge.copy(
                fontFamily = SourceSerif,
                fontStyle = FontStyle.Italic,
            ),
            color = colour,
            textDecoration = if (struck) TextDecoration.LineThrough else null,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            gloss,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// --- bits -------------------------------------------------------------------

/** The small-caps rule that names each band of the screen. */
@Composable
private fun SectionLabel(text: String, trailing: String? = null) {
    Column {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth()) {
            Text(
                text.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.1.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            trailing?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
