package com.copiosa.ui.conjugation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.conjugation.conjugate
import com.copiosa.data.conjugation.conjugateSource
import com.copiosa.domain.conjugation.ConjugatedForm
import com.copiosa.domain.conjugation.DrillGrade
import com.copiosa.domain.conjugation.DrillItem
import com.copiosa.domain.conjugation.SegmentRole
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.ui.components.ProgressBar
import com.copiosa.ui.components.rememberRevealTapGuard

/**
 * The drill runner, shared by the standalone screen and the routine step.
 *
 * The shape is the flashcard one: read the prompt, work the answer out in your head, reveal, then say
 * whether you had it. The reveal tap is what stops the clock — that latency is the whole point, since
 * a conjugation you had to reconstruct is not one you know, and it feeds a much tighter mastery
 * threshold than the routine engine's.
 */
@Composable
fun ConjugationDrillActivity(
    items: List<DrillItem>,
    spec: ConjugationSpec,
    onGrade: (DrillItem, ConjugatedForm, DrillGrade, Long) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    showProgress: Boolean = true,
    /** The verb each card asks about, so the host can point the reference screen at it. */
    onCardShown: (String) -> Unit = {},
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }
    var index by remember(items) { mutableIntStateOf(0) }
    var revealed by remember(items) { mutableStateOf(false) }
    var shownAt by remember(items) { mutableStateOf(System.currentTimeMillis()) }
    var latencyMs by remember(items) { mutableStateOf(0L) }

    LaunchedEffect(items, index) {
        revealed = false
        latencyMs = 0L
        shownAt = System.currentTimeMillis()
        items.getOrNull(index)?.let { onCardShown(it.infinitive) }
    }

    val item = items[index]
    val form = remember(item) { item.conjugate(spec) }
    // A verb the lexicon can no longer place (an older saved plan) is skipped rather than crashing.
    if (form == null) {
        LaunchedEffect(item) { if (index + 1 >= items.size) onComplete() else index++ }
        return
    }

    // The grades take the reveal button's place, so a double tap on it must not pick one.
    val revealGuard = rememberRevealTapGuard(index)

    fun grade(grade: DrillGrade) {
        if (!revealGuard.settled()) return
        onGrade(item, form, grade, latencyMs)
        if (index + 1 >= items.size) onComplete() else index++
    }

    Column(modifier.fillMaxSize()) {
        if (showProgress) {
            ProgressBar(
                progress = { (index + 1f) / items.size },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(20.dp))
        }

        Prompt(item, form, revealed, spec)

        Spacer(Modifier.height(24.dp))

        if (revealed) {
            Reveal(item, form, spec)
            Spacer(Modifier.weight(1f))
            GradeRow(onGrade = ::grade)
        } else {
            Spacer(Modifier.weight(1f))
            Button(
                onClick = {
                    latencyMs = System.currentTimeMillis() - shownAt
                    revealGuard.mark()
                    revealed = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.conj_reveal)) }
        }
    }
}

/** What the learner is given. Each direction hides a different half of the cell. */
@Composable
private fun Prompt(item: DrillItem, form: ConjugatedForm, revealed: Boolean, spec: ConjugationSpec) {
    val verb = spec.lexicon.byInfinitive(item.infinitive)
    Column {
        Text(
            item.direction.label(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        when (item) {
            is DrillItem.Produce -> {
                Text(item.infinitive, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                verb?.gloss?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                CellLine(item, spec)
            }

            is DrillItem.Decode -> {
                // The form alone: name the verb, the person and the tense in your head. On reveal it
                // stays put and picks up its irregular colouring — the answer card doesn't repeat it.
                Text(
                    if (revealed) segmentedForm(form) else AnnotatedString(form.form),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (!revealed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.conj_decode_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is DrillItem.Transform -> {
                val source = item.conjugateSource(spec)
                Text(
                    source?.form ?: item.infinitive,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(item.rule.label(), style = MaterialTheme.typography.bodyMedium)
                CellLine(item, spec)
            }

            is DrillItem.Generalize -> {
                // Show the pattern by example, then ask about a verb never drilled before: the only
                // way through is the rule itself.
                Text(item.cls.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Exemplars(item, spec)
                Spacer(Modifier.height(16.dp))
                Text(item.infinitive, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                verb?.gloss?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                CellLine(item, spec)
            }
        }
    }
}

/** "nosotros · preterite" — the coordinates being asked for. */
@Composable
private fun CellLine(item: DrillItem, spec: ConjugationSpec) {
    Text(
        "${item.target.person.label(spec)} · ${item.target.tense.label()}",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

/** Two or three worked examples of the pattern a generalize card is testing. */
@Composable
private fun Exemplars(item: DrillItem.Generalize, spec: ConjugationSpec) {
    val examples = remember(item) {
        spec.lexicon.verbsInClass(item.cls)
            .filter { it.infinitive != item.infinitive }
            .take(EXEMPLAR_COUNT)
            .mapNotNull { verb ->
                verb.overrides.entries.firstNotNullOfOrNull { (_, byPerson) ->
                    byPerson.values.firstOrNull { item.cls in it.classes }
                }?.let { verb.infinitive to it.form }
            }
    }
    if (examples.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Text(
        examples.joinToString("   ") { "${it.first} → ${it.second}" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The answer, with the irregular span coloured by its class, and — only when it is a mistake anyone
 * would actually make — the regular form the rules alone would have produced, struck through.
 */
@Composable
private fun Reveal(item: DrillItem, form: ConjugatedForm, spec: ConjugationSpec) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            if (item is DrillItem.Decode) {
                DecodeAnswer(item, form, spec)
            } else {
                Text(segmentedForm(form), style = MaterialTheme.typography.headlineMedium)
            }

            if (form.showsNaive) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.conj_not),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        form.naiveForm,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                        textDecoration = TextDecoration.LineThrough,
                    )
                }
            }

            if (form.isIrregular) {
                Spacer(Modifier.height(12.dp))
                ClassLegend(form)
            }
        }
    }
}

/**
 * The answer to a decode card: its coordinates, not its form — the form is already in the prompt.
 * Ordered by what was hard to name, subject first, with the infinitive it came from underneath.
 */
@Composable
private fun DecodeAnswer(item: DrillItem.Decode, form: ConjugatedForm, spec: ConjugationSpec) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            item.target.person.label(spec),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            item.target.tense.label(),
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                form.infinitive,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            spec.lexicon.byInfinitive(form.infinitive)?.gloss?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.width(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** One chip per class the cell exhibits, in the same colour as its highlighted span. */
@Composable
private fun ClassLegend(form: ConjugatedForm) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        form.classes.distinct().forEach { cls ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    color = cls.family.color(),
                    shape = CircleShape,
                    modifier = Modifier.size(10.dp),
                ) {}
                Spacer(Modifier.width(8.dp))
                Text(cls.label(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Builds the coloured form: only [SegmentRole.IRREGULAR] slices are tinted and bolded. */
@Composable
private fun segmentedForm(form: ConjugatedForm): AnnotatedString {
    val colors = form.classes.distinct().associateWith { it.family.color() }
    val fallback: Color = MaterialTheme.colorScheme.onSurface
    return buildAnnotatedString {
        form.segments.forEach { segment ->
            if (segment.role == SegmentRole.IRREGULAR) {
                withStyle(
                    SpanStyle(
                        color = segment.irregularity?.let { colors[it] } ?: fallback,
                        fontWeight = FontWeight.Bold,
                    ),
                ) { append(segment.text) }
            } else {
                append(segment.text)
            }
        }
    }
}

/** Two buttons: latency already carries how *well* it was known. */
@Composable
private fun GradeRow(onGrade: (DrillGrade) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            onClick = { onGrade(DrillGrade.MISSED) },
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text(stringResource(R.string.conj_grade_missed)) }
        Button(
            onClick = { onGrade(DrillGrade.GOT_IT) },
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(R.string.conj_grade_got_it)) }
    }
}

private const val EXEMPLAR_COUNT = 3

/** A coloured swatch + label row, reused by the reference screen's legend. */
@Composable
internal fun FamilySwatch(color: Color, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Spacer(
            Modifier
                .size(10.dp)
                .background(color, CircleShape),
        )
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
