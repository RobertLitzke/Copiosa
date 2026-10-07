package com.copiosa.ui.conjugation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.domain.conjugation.ConjugatedForm
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.IrregularityClass
import com.copiosa.domain.conjugation.IrregularityFamily
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.ReferenceSection
import com.copiosa.domain.conjugation.ReferenceTable
import com.copiosa.domain.conjugation.Tense
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.reference.ReferenceViewModel

/**
 * The conjugation tables: the verb currently being drilled, the regular paradigms, and one table per
 * class of irregularity.
 *
 * Reached two ways — as the `CONJUGATION` topic of the reference section, and from the drill's
 * top-right icon mid-session. [currentVerb] is whatever the card on screen was asking about, so the
 * second route lands on that verb's full paradigm, the reason you reached for it. Searching widens
 * the same tab to the whole lexicon; with no session and no query there is nothing to show but the
 * search.
 *
 * Every cell is produced by the same engine the drill grades with and highlighted in the same
 * colours, so the tables cannot drift from what a session just showed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ConjugationReferenceScreen(
    onBack: () -> Unit,
    currentVerb: String? = null,
    viewModel: ReferenceViewModel = hiltViewModel(),
) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    val spec = viewModel.ui.collectAsStateWithLifecycle().value.spec
    val reference = spec?.reference
    // The optional person is always shown here: a reference should be complete even when the drill
    // skips it.
    val persons = spec?.persons ?: emptyList()

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.ref_topic_conjugation), onBack = onBack)

        SegmentedToggle(
            selected = tab,
            labels = listOf(
                stringResource(R.string.conj_reference_current),
                stringResource(R.string.conj_reference_patterns),
                stringResource(R.string.conj_reference_irregular),
            ),
            onSelect = { tab = it },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Spacer(Modifier.height(12.dp))

        // The pattern tabs are small enough to build up front; a search across the lexicon is not
        // (200 verbs x 42 cells), so it stays a list of entries, each table built as its row scrolls in.
        val sections = remember(tab, reference) {
            when {
                reference == null -> emptyList()
                tab == 1 -> reference.paradigmSections(persons)
                tab == 2 -> reference.irregularSections(persons)
                else -> emptyList()
            }
        }
        val verbs = remember(tab, query, currentVerb, reference) {
            when {
                reference == null || tab != 0 -> emptyList()
                query.isNotBlank() -> reference.verbMatches(query)
                else -> listOfNotNull(currentVerb?.let(reference::byInfinitive))
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "header-$tab") {
                when (tab) {
                    1 -> Blurb(stringResource(R.string.conj_reference_patterns_desc))
                    2 -> Column {
                        Blurb(stringResource(R.string.conj_reference_irregular_desc))
                        Spacer(Modifier.height(12.dp))
                        Legend()
                    }
                    else -> Column {
                        Blurb(
                            stringResource(
                                R.string.conj_reference_verbs_desc,
                                spec?.lexicon?.verbs?.size ?: 0,
                            ),
                        )
                        Spacer(Modifier.height(8.dp))
                        SearchBox(query) { query = it }
                    }
                }
            }

            // Only a search that found nothing is a dead end; an empty tab with no query is just idle.
            if (tab == 0 && verbs.isEmpty() && query.isNotBlank()) {
                item(key = "empty") { Blurb(stringResource(R.string.conj_reference_no_match)) }
            }

            items(sections, key = { it.key }) { section -> SectionCard(section, spec!!) }

            items(verbs, key = { it.infinitive }) { verb ->
                SectionCard(remember(verb) { reference!!.verbSection(verb, persons) }, spec!!)
            }

            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun Blurb(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend() {
    Column {
        SectionLabel(stringResource(R.string.conj_reference_legend))
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            (IrregularityFamily.entries - IrregularityFamily.REGULAR).forEach {
                FamilySwatch(it.color(), it.label())
            }
        }
    }
}

@Composable
private fun SearchBox(query: String, onQuery: (String) -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            decorationBox = { inner ->
                if (query.isEmpty()) {
                    Text(
                        stringResource(R.string.conj_reference_search),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                inner()
            },
        )
    }
}

@Composable
private fun SectionCard(section: ReferenceSection, spec: ConjugationSpec) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            section.subtitle?.let {
                Column {
                    Text(section.key, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            section.tables.forEach { ReferenceTableBlock(it, spec) }
        }
    }
}

/**
 * One table. Wide content scrolls inside its own row rather than forcing the page sideways — six
 * persons of a long form (`estuviéramos`) will not fit a phone otherwise.
 *
 * [showTitle] is off where the surrounding card already names what the table is — the tense guide
 * heads each entry with the tense, and would otherwise print it twice.
 */
@Composable
internal fun ReferenceTableBlock(table: ReferenceTable, spec: ConjugationSpec, showTitle: Boolean = true) {
    Column {
        if (showTitle) {
            Text(
                tableTitle(table),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(6.dp))
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Column {
                // Header row: the persons, offset by the label column.
                Row {
                    Spacer(Modifier.width(LABEL_WIDTH))
                    table.persons.forEach {
                        Text(
                            it.label(spec),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(CELL_WIDTH),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                table.rows.forEach { row ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            rowLabel(row.label),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.width(LABEL_WIDTH),
                        )
                        row.forms.forEach { form ->
                            Text(
                                highlighted(form),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.width(CELL_WIDTH).padding(vertical = 3.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A table is titled by its tense, its irregularity class, or the verb it belongs to. */
@Composable
private fun tableTitle(table: ReferenceTable): String {
    IrregularityClass.fromSlug(table.titleKey)?.let { return it.label() }
    Tense.fromSlug(table.titleKey)?.let { return it.label() }
    return table.titleKey
}

/** A row is labelled by its verb, or — in a per-verb table — by its tense. */
@Composable
private fun rowLabel(label: String): String = Tense.fromSlug(label)?.label() ?: label

@Composable
private fun highlighted(form: ConjugatedForm): AnnotatedString {
    val colors = form.classes.distinct().associateWith { it.family.color() }
    return buildAnnotatedString {
        form.segments.forEach { segment ->
            val color = segment.irregularity?.let { colors[it] }
            if (color != null) {
                withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(segment.text) }
            } else {
                append(segment.text)
            }
        }
    }
}

private val LABEL_WIDTH = 116.dp
private val CELL_WIDTH = 108.dp
