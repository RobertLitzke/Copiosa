package com.copiosa.ui.conjugation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.domain.conjugation.ConjugationDrillConfig
import com.copiosa.domain.conjugation.ConjugationSessionStats
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.conjugation.IrregularityFamily
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.TrackFocusTime

/**
 * The standalone conjugation drill: setup, session, results. Structurally the rapid-fire quiz screen,
 * with the reference tables behind the header's trailing icon.
 */
@Composable
fun ConjugationDrillScreen(
    onBack: () -> Unit,
    onOpenReference: (currentVerb: String?) -> Unit,
    viewModel: ConjugationDrillViewModel = hiltViewModel(),
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val active = session
    // The verb on the card right now, so the reference opens on it. Stale once the session ends,
    // which is why it is only passed on while one is in flight.
    var currentVerb by remember { mutableStateOf<String?>(null) }
    val running = active != null && !active.finished

    // Only a session in flight counts as study time; browsing the setup screen doesn't.
    TrackFocusTime(FocusActivityType.CONJUGATION, enabled = running)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.conj_title), onBack = onBack) {
            IconButton(onClick = { onOpenReference(currentVerb.takeIf { running }) }) {
                Icon(Icons.Filled.MenuBook, contentDescription = stringResource(R.string.conj_reference_open))
            }
        }

        when {
            home.loading -> Unit

            !home.supported -> Text(
                stringResource(R.string.conj_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )

            active == null -> SetupPane(
                home = home,
                onConfig = viewModel::setConfig,
                onStart = viewModel::startSession,
            )

            active.finished -> ResultsPane(
                stats = active.stats,
                onAgain = { viewModel.dismissSession(); viewModel.startSession() },
                onDone = viewModel::dismissSession,
            )

            else -> ConjugationDrillActivity(
                items = active.items,
                spec = home.spec!!,
                onGrade = viewModel::onGrade,
                onComplete = viewModel::onSessionComplete,
                modifier = Modifier.padding(horizontal = 20.dp),
                onCardShown = { currentVerb = it },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPane(
    home: ConjugationHomeUi,
    onConfig: (ConjugationDrillConfig) -> Unit,
    onStart: () -> Unit,
) {
    val config = home.config
    val spec = home.spec ?: return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        // Sections carry their own internal spacing, so this only separates one section from the next.
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (home.summary.attempts > 0) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        stringResource(
                            R.string.conj_summary_mastered,
                            home.summary.itemsMastered,
                            home.summary.itemsSeen,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    home.summary.accuracyPercent?.let {
                        // The count goes with the percentage: "100%" off two answers means nothing on its own.
                        Text(
                            pluralStringResource(
                                R.plurals.conj_summary_accuracy,
                                home.summary.attempts,
                                it,
                                home.summary.attempts,
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        SetupSection(stringResource(R.string.conj_setup_count)) {
            ConjugationDrillConfig.ITEM_COUNTS.forEach { count ->
                FilterChip(
                    selected = config.itemCount == count,
                    onClick = { onConfig(config.copy(itemCount = count)) },
                    label = { Text("$count") },
                )
            }
        }

        // The language's own paradigms, not every Tense there is: Portuguese has two Spanish hasn't.
        SetupSection(stringResource(R.string.conj_setup_tenses)) {
            spec.tenses.forEach { tense ->
                val on = tense in config.tenses
                FilterChip(
                    selected = on,
                    onClick = {
                        onConfig(config.copy(tenses = config.tenses.toggle(tense)))
                    },
                    label = { Text(tense.label()) },
                )
            }
        }

        SetupSection(stringResource(R.string.conj_setup_directions)) {
            DrillDirection.entries.forEach { direction ->
                FilterChip(
                    selected = direction in config.directions,
                    onClick = {
                        onConfig(config.copy(directions = config.directions.toggle(direction)))
                    },
                    label = { Text(direction.label()) },
                )
            }
        }

        SetupSection(stringResource(R.string.conj_setup_emphasis)) {
            EMPHASIS_STEPS.forEach { (value, labelRes) ->
                FilterChip(
                    selected = kotlin.math.abs(config.irregularEmphasis - value) < 0.01,
                    onClick = { onConfig(config.copy(irregularEmphasis = value)) },
                    label = { Text(stringResource(labelRes)) },
                )
            }
        }

        // Named after whichever person the language lets you switch off — vosotros, vós.
        spec.optionalPerson?.let { optional ->
            FilterChip(
                selected = config.includeVosotros,
                onClick = { onConfig(config.copy(includeVosotros = !config.includeVosotros)) },
                label = { Text(stringResource(R.string.conj_setup_optional_person, optional.label(spec))) },
            )
        }

        if (home.nothingToDrill) {
            Text(
                stringResource(R.string.conj_nothing_to_drill),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.conj_start))
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** A setup heading with its chips pulled in tight beneath it, so the label reads as part of the group. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupSection(title: String, chips: @Composable FlowRowScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = chips,
        )
    }
}

@Composable
private fun ResultsPane(
    stats: ConjugationSessionStats?,
    onAgain: () -> Unit,
    onDone: () -> Unit,
) {
    if (stats == null) return
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.conj_results_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        StatRow(stringResource(R.string.conj_results_accuracy), "${stats.accuracyPercent}%")
        StatRow(stringResource(R.string.conj_results_answered), "${stats.correct}/${stats.answered}")
        StatRow(
            stringResource(R.string.conj_results_speed),
            stringResource(R.string.conj_results_seconds, stats.avgLatencyMs / 1000f),
        )

        if (stats.byTense.size > 1) {
            SectionLabel(stringResource(R.string.conj_results_by_tense))
            stats.byTense.forEach { (tense, split) ->
                StatRow(tense.label(), "${split.second}/${split.first}")
            }
        }
        if (stats.byFamily.isNotEmpty()) {
            SectionLabel(stringResource(R.string.conj_results_by_family))
            stats.byFamily.forEach { (family, split) ->
                FamilyStatRow(family, "${split.second}/${split.first}")
            }
        }

        Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.conj_restart))
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.conj_finish))
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FamilyStatRow(family: IrregularityFamily, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FamilySwatch(family.color(), family.label())
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Toggling the last item off would leave nothing to draw from, so it stays on. */
private fun <T> Set<T>.toggle(value: T): Set<T> =
    if (value in this) (this - value).ifEmpty { this } else this + value

private val EMPHASIS_STEPS = listOf(
    0.0 to R.string.conj_setup_emphasis_low,
    0.6 to R.string.conj_setup_emphasis_mid,
    1.0 to R.string.conj_setup_emphasis_high,
)
