package com.copiosa.ui.course

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.DrillItemCodec
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.conjugation.VerbEntry
import com.copiosa.domain.course.ClozeSegment
import com.copiosa.domain.course.ConjDrillSegment
import com.copiosa.domain.course.CourseDefinition
import com.copiosa.domain.course.DidacticSegment
import com.copiosa.domain.course.ExamplesBlock
import com.copiosa.domain.course.LessonBlock
import com.copiosa.domain.course.LessonSegment
import com.copiosa.domain.course.PiSegment
import com.copiosa.domain.course.ProseBlock
import com.copiosa.domain.course.ProseText
import com.copiosa.domain.course.TableBlock
import com.copiosa.domain.course.WrapUpSegment
import com.copiosa.ui.components.MarkdownText
import com.copiosa.ui.components.SegmentProgress
import com.copiosa.ui.components.inlineMarkup
import com.copiosa.ui.conjugation.ConjugationDrillActivity
import com.copiosa.ui.conjugation.label
import com.copiosa.ui.routines.activities.ChoiceItem
import com.copiosa.ui.routines.activities.MultipleChoiceActivity
import com.copiosa.ui.theme.SourceSerif
import kotlinx.coroutines.launch

/**
 * Steps through one course sitting — the course mode's twin of `RoutinePlayerScreen`. The session
 * row drives everything: a SYNTHESIZING row shows the preparing spinner, a FAILED row the retry-less
 * apology, and a READY plan is stepped segment by segment with the resume point persisted on each
 * advance.
 */
@Composable
fun LessonPlayerScreen(
    onExit: () -> Unit,
    onRestart: (Long) -> Unit,
    viewModel: LessonPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmRestart by remember { mutableStateOf(false) }

    if (confirmRestart) {
        RestartDialog(
            onDismiss = { confirmRestart = false },
            onConfirm = {
                confirmRestart = false
                scope.launch { viewModel.restart().takeIf { it != 0L }?.let(onRestart) }
            },
        )
    }

    Column(Modifier.fillMaxSize()) {
        PlayerHeader(state, onExit, onRestart = { confirmRestart = true })

        when {
            state.loading || state.preparing -> Centered {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.course_preparing), style = MaterialTheme.typography.bodyMedium)
                }
            }

            state.failed -> Centered {
                Text(stringResource(R.string.course_failed), style = MaterialTheme.typography.bodyLarge)
            }

            else -> {
                val plan = state.plan ?: return@Column
                val segment = plan.segments.getOrNull(state.index) ?: return@Column
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 20.dp)
                        .padding(top = 8.dp, bottom = 16.dp),
                ) {
                    SegmentContent(segment, state, viewModel, onExit)
                }
            }
        }
    }
}

/** Restarting throws work away, so it asks first — it sits one tap from the close button. */
@Composable
private fun RestartDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.course_restart)) },
        text = { Text(stringResource(R.string.course_restart_confirm)) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.course_restart)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Composable
private fun PlayerHeader(state: LessonPlayerUi, onExit: () -> Unit, onRestart: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_back))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.lessonTitle.ifBlank { state.courseTitle },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // "The preterite · lesson 3 of 17" — where this sitting sits in the whole course,
                // which the player otherwise never says.
                val subtitle = when {
                    state.courseTitle.isBlank() || state.lessonTitle.isBlank() -> ""
                    state.lessonNumber > 0 -> stringResource(
                        R.string.course_player_subtitle,
                        state.courseTitle,
                        state.lessonNumber,
                        state.lessonCount,
                    )
                    else -> state.courseTitle
                }
                if (subtitle.isNotBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Always offered, including on the failure screen — a sitting whose build died has no
            // other way forward.
            IconButton(onClick = onRestart) {
                Icon(
                    Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.course_restart),
                )
            }
        }
        SegmentProgress(
            index = state.index,
            total = state.plan?.segments?.size ?: 0,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SegmentContent(
    segment: LessonSegment,
    state: LessonPlayerUi,
    viewModel: LessonPlayerViewModel,
    onExit: () -> Unit,
) {
    when (segment) {
        is DidacticSegment -> DidacticStep(segment, state, onContinue = viewModel::advance)

        is ConjDrillSegment -> {
            val spec = state.spec
            val items = remember(segment, spec) {
                spec?.let { DrillItemCodec.decodeAll(segment.items, it) }.orEmpty()
            }
            if (spec == null || items.isEmpty()) {
                LaunchedEffect(segment) { viewModel.advance() }
            } else {
                Column {
                    if (segment.warmUp) {
                        Text(
                            stringResource(R.string.course_warm_up),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                    ConjugationDrillActivity(
                        items = items,
                        spec = spec,
                        onGrade = { item, form, grade, latencyMs ->
                            viewModel.onDrillGrade(item, form, grade, latencyMs, segment.warmUp)
                        },
                        onComplete = viewModel::advance,
                        showProgress = false,
                    )
                }
            }
        }

        is ClozeSegment -> ChoiceStep(
            prompt = stringResource(R.string.routine_cloze_prompt),
            sentences = segment.items.map { it.sentence },
            items = segment.items.map { ChoiceItem(it.sentence, it.options, it.answerIndex, it.explanation) },
            category = segment.category,
            warmUp = segment.warmUp,
            viewModel = viewModel,
        )

        is PiSegment -> ChoiceStep(
            prompt = stringResource(R.string.routine_pi_prompt),
            sentences = segment.items.map { it.sentence },
            items = segment.items.map { ChoiceItem(it.sentence, it.options, it.answerIndex, it.explanation) },
            category = segment.category,
            viewModel = viewModel,
        )

        is WrapUpSegment -> WrapUpStep(segment, state, onFinish = { viewModel.finish(); onExit() })
    }
}

/** A cloze or PI beat: the shared runner, with the missed sentence reported for the wrap-up note. */
@Composable
private fun ChoiceStep(
    prompt: String,
    sentences: List<String>,
    items: List<ChoiceItem>,
    category: String?,
    viewModel: LessonPlayerViewModel,
    warmUp: Boolean = false,
) {
    Column {
        // The same framing a warm-up drill gets: a course with no paradigm opens on items from
        // earlier lessons rather than on conjugation cards, and the learner should be told so.
        if (warmUp) {
            Text(
                stringResource(R.string.course_warm_up),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(8.dp))
        }
        MultipleChoiceActivity(
            prompt = prompt,
            items = items,
            onAnswer = { index, correct, ms ->
                viewModel.onChoiceAnswer(correct, ms, category, sentences.getOrNull(index), warmUp)
            },
            onComplete = viewModel::advance,
            // The player insets every segment alike; the runner must not inset again.
            contentPadding = 0.dp,
        )
    }
}

// --- didactic ---------------------------------------------------------------

@Composable
private fun DidacticStep(segment: DidacticSegment, state: LessonPlayerUi, onContinue: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            segment.title?.let { title ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    // The part of the course this teaching belongs to, as a kicker over the
                    // heading — the line that says what the next few screens are for.
                    if (state.partTitle.isNotBlank()) {
                        Text(
                            state.partTitle.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.4.sp,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        title,
                        fontFamily = SourceSerif,
                        fontSize = 27.sp,
                        lineHeight = 33.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            segment.blocks.forEach { DidacticBlockView(it, state) }
            Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onContinue,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                stringResource(R.string.routine_continue),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun DidacticBlockView(block: LessonBlock, state: LessonPlayerUi) {
    val spec = state.spec
    when (block) {
        // The app's own markdown renderer, so authored `**bold**` / `*italic*` behave exactly as
        // they do in a book or a graded text — and the learner's reading font and size scale apply
        // to lesson prose too. The renderer is line-based, so the prose is unwrapped first; see
        // [ProseText] for why that is not simply "join every line with a space".
        is ProseBlock -> MarkdownText(ProseText.forMarkdown(block.text))

        is TableBlock -> if (spec != null) ParadigmBlock(block, spec, state)

        is ExamplesBlock -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(R.string.course_examples_heading),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            block.items.forEach { example ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        inlineMarkup(example.sentence),
                        fontFamily = SourceSerif,
                        fontSize = 17.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        example.gloss,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    example.note?.let {
                        Text(
                            inlineMarkup(it, italicBase = true),
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
        }
    }
}

// --- paradigm tables --------------------------------------------------------

/** How one cell of a paradigm stands relative to the lesson being played. */
private enum class CellStanding { FOCUS, KNOWN, UNTAUGHT }

/**
 * A lesson's paradigm table: one card per verb, the six persons laid out in two columns.
 *
 * Drawn here rather than reused from the reference section because a lesson table is not a lookup —
 * it is a picture of progress through a paradigm. Cells the course has already taught are set
 * plainly, the ones this sitting adds are boxed, and the ones still to come are left as dashes
 * rather than quietly handed over early. Four lessons into the preterite a learner should be able to
 * see at a glance that they are two thirds of the way through *hablar*.
 *
 * Forms still come from the engine, so nothing here can disagree with what a drill grades.
 */
@Composable
private fun ParadigmBlock(block: TableBlock, spec: ConjugationSpec, state: LessonPlayerUi) {
    val tense = remember(block, spec) {
        Tense.fromSlug(block.tense)?.takeIf { it in spec.tenses }
    } ?: return
    val verbs = remember(block, spec) { block.verbs.mapNotNull { spec.lexicon.byInfinitive(it) } }
    if (verbs.isEmpty()) return

    val persons = spec.persons
    val lessonId = state.plan?.lessonId
    val known = remember(state.course, lessonId, persons) {
        knownPersonsBefore(state.course, lessonId, persons)
    }
    // What this sitting adds. The authored table usually already narrows to the persons the beat is
    // about; where it doesn't, the lesson's own scope does, and a consolidation lesson that narrows
    // nothing is adding precisely whatever is left over.
    val focus = remember(block, state.course, lessonId, known, persons) {
        block.persons?.mapNotNull(Person::fromSlug)?.toSet()
            ?: lessonId?.let { state.course?.lesson(it) }?.scope?.persons
            ?: persons.filterNot { it in known }.toSet()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        verbs.forEach { verb ->
            ParadigmCard(verb, tense, spec, persons, focus = focus, known = known)
        }
    }
}

/**
 * The persons every lesson before this one has taught between them.
 *
 * A null `persons` on the accumulated scope means "unrestricted" — the whole paradigm is already in
 * play. No preceding lesson at all means the opposite, and an empty set is the honest answer there.
 * Anything unresolvable (a checkup, an unknown course) counts as nothing known, which under-claims
 * rather than showing a learner forms as familiar that they have never met.
 */
private fun knownPersonsBefore(
    course: CourseDefinition?,
    lessonId: String?,
    persons: List<Person>,
): Set<Person> {
    if (course == null || lessonId == null) return emptySet()
    val before = course.scopeBefore(lessonId) ?: return emptySet()
    return before.persons ?: persons.toSet()
}

@Composable
private fun ParadigmCard(
    verb: VerbEntry,
    tense: Tense,
    spec: ConjugationSpec,
    persons: List<Person>,
    focus: Set<Person>,
    known: Set<Person>,
) {
    val scheme = MaterialTheme.colorScheme
    val taught = persons.count { it in focus || it in known }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                verb.infinitive.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(R.string.course_paradigm_known, taught, persons.size),
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = scheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = scheme.outlineVariant)
        // Singular down the left, plural down the right, so each row pairs the two forms that share
        // a person — the comparison the paradigm is actually about.
        val half = (persons.size + 1) / 2
        repeat(half) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOfNotNull(persons.getOrNull(row), persons.getOrNull(row + half)).forEach { person ->
                    ParadigmCell(
                        person = person,
                        form = spec.engine.conjugate(verb, tense, person)?.form,
                        standing = when {
                            person in focus -> CellStanding.FOCUS
                            person in known -> CellStanding.KNOWN
                            else -> CellStanding.UNTAUGHT
                        },
                        spec = spec,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * One cell: the pronoun over its form, not beside it.
 *
 * Side by side is the prettier arrangement and it does not survive contact with the content. Half a
 * card is about 140dp, and *ellos / ellas* alongside *hablaron* wants rather more than that before
 * the system font scale is turned up at all — so the label would ellipsize, on exactly the two
 * persons whose pronoun is doing the most work. Stacking costs a few dp of height per row and can
 * never truncate.
 */
@Composable
private fun ParadigmCell(
    person: Person,
    form: String?,
    standing: CellStanding,
    spec: ConjugationSpec,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val focused = standing == CellStanding.FOCUS
    Column(
        modifier = modifier
            .background(
                if (focused) scheme.tertiary.copy(alpha = 0.16f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .then(
                if (focused) {
                    Modifier.border(
                        1.dp,
                        scheme.tertiary.copy(alpha = 0.55f),
                        RoundedCornerShape(8.dp),
                    )
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            person.label(spec),
            style = MaterialTheme.typography.labelSmall,
            color = if (focused) scheme.tertiary else scheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            if (standing == CellStanding.UNTAUGHT || form == null) {
                stringResource(R.string.course_form_untaught)
            } else {
                form
            },
            fontFamily = SourceSerif,
            fontStyle = FontStyle.Italic,
            fontSize = 17.sp,
            fontWeight = if (focused) FontWeight.SemiBold else FontWeight.Normal,
            color = when (standing) {
                CellStanding.FOCUS -> scheme.tertiary
                CellStanding.KNOWN -> scheme.onSurface
                CellStanding.UNTAUGHT -> scheme.outline
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// --- wrap-up ----------------------------------------------------------------

@Composable
private fun WrapUpStep(segment: WrapUpSegment, state: LessonPlayerUi, onFinish: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.course_wrap_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (state.total > 0) {
                Text(
                    stringResource(R.string.course_wrap_accuracy, state.correct, state.total),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            segment.summary?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge)
            }
            // The diagnosis of this sitting's mistakes, fetched on arrival here. A sitting with
            // nothing missed — or no model reachable — simply shows nothing.
            val note = state.feedback ?: segment.feedback
            when {
                state.feedbackLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.course_feedback_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                !note.isNullOrBlank() -> Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(note, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                }
            }
            if (state.graduated) {
                Text(
                    stringResource(R.string.course_wrap_graduated),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Button(
            onClick = onFinish,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text(
                stringResource(R.string.course_finish),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// --- helpers ----------------------------------------------------------------

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

/** Past this many segments a tick is thinner than the gap beside it, so the bar comes back. */
