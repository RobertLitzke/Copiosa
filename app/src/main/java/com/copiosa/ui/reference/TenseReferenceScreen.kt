package com.copiosa.ui.reference

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.Mood
import com.copiosa.domain.conjugation.ReferenceTable
import com.copiosa.domain.conjugation.Tense
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.conjugation.ReferenceTableBlock
import com.copiosa.ui.conjugation.label
import java.util.Locale

/**
 * The tense guide: every paradigm the profile's language drills, grouped by mood, each with what it
 * expresses, how it is built, and the model verbs conjugated across it.
 *
 * Its companion is the conjugation topic — that one answers "how does *this verb* go", this one
 * answers "which of these do I want". Both are built from the same [ConjugationSpec] the drill
 * grades with, so neither can drift from a session; the only hand-written part is the sentence
 * saying what a tense is *for*, which no ending table encodes (see [usage]).
 */
@Composable
fun TenseReferenceScreen(
    onBack: () -> Unit,
    viewModel: ReferenceViewModel = hiltViewModel(),
) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val spec = ui.spec

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.ref_topic_tenses), onBack = onBack)

        // One pass over the model verbs (three verbs across every tense) rather than one per card,
        // and only when the spec actually changes.
        val tables = remember(spec) {
            spec?.reference?.paradigmSections(spec.persons)
                ?.flatMap { it.tables }
                ?.associateBy { it.tense }
                .orEmpty()
        }
        val byMood = remember(spec) { spec?.tenses?.groupBy { it.mood }.orEmpty() }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "intro") {
                Text(
                    stringResource(R.string.ref_tenses_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (spec != null) {
                byMood.forEach { (mood, tenses) ->
                    item(key = "mood-${mood.name}") { MoodHeading(mood) }
                    items(tenses, key = { it.slug }) { tense ->
                        TenseCard(tense, tables[tense], spec)
                    }
                }
            }

            item(key = "tail") { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun MoodHeading(mood: Mood) {
    Column(Modifier.padding(top = 8.dp)) {
        SectionLabel(mood.label())
        Spacer(Modifier.height(4.dp))
        Text(
            mood.blurb(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One paradigm: what it says, how it is formed, and what it looks like on the model verbs. The table
 * is the same one the conjugation tables show, highlighted in the same colours — a class of
 * irregularity that surfaces in a model verb is visible here too.
 */
@Composable
private fun TenseCard(tense: Tense, table: ReferenceTable?, spec: ConjugationSpec) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                tense.label().replaceFirstChar { it.titlecase(Locale.getDefault()) },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                tense.usage(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            formationNote(tense, spec)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            table?.let {
                Spacer(Modifier.height(2.dp))
                ReferenceTableBlock(it, spec, showTitle = false)
            }
        }
    }
}
