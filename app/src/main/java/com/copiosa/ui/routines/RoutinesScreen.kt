package com.copiosa.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.local.TopicEntity
import com.copiosa.data.routine.ResumableRoutine
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.routine.RoutineType
import com.copiosa.domain.vocab.freq.FrequencyBucket
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.vocab.pickerLabel
import kotlinx.coroutines.launch

/**
 * The Routines tab: a list of launchable routines — the bedtime wind-down and its mirror image, the
 * daytime activation. Tapping one opens a setup sheet (duration + optional topic seeded from the
 * interest table); confirming creates a run, starts synthesis, and hands [onStartRun] the run id so
 * the host can open the player.
 */
@Composable
fun RoutinesScreen(
    onStartRun: (Long) -> Unit,
    onOpenStatus: () -> Unit,
    onOpenRapidFire: (CueType) -> Unit,
    onOpenConjugationDrill: () -> Unit,
    onOpenCloze: () -> Unit,
    onOpenListenRebuild: () -> Unit,
    onOpenFrequencyDrill: (size: Int, maxRank: Int) -> Unit,
    onOpenCourses: () -> Unit,
    viewModel: RoutinesViewModel = hiltViewModel(),
) {
    val suggestions by viewModel.suggestions.collectAsStateWithLifecycle()
    val conjugationAvailable by viewModel.conjugationAvailable.collectAsStateWithLifecycle()
    val frequencyDrillAvailable by viewModel.frequencyDrillAvailable.collectAsStateWithLifecycle()
    val coursesAvailable by viewModel.coursesAvailable.collectAsStateWithLifecycle()
    val resumable by viewModel.resumable.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    // Which routine's setup sheet is open, if any — the sheet is shared by both routine types.
    var setupFor by remember { mutableStateOf<RoutineType?>(null) }
    var drillDialog by remember { mutableStateOf(false) }

    // Scrollable: the card list outgrew a phone screen once the frequent-word drill joined it, and a
    // plain Column would simply clip the last card out of reach.
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        ScreenHeader(title = stringResource(R.string.routines_title)) {
            IconButton(onClick = onOpenStatus) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = stringResource(R.string.routine_status_open),
                )
            }
        }
        // First, above the routines themselves: an unfinished one is the likeliest reason to be here.
        resumable?.let { run ->
            ResumeCard(
                run = run,
                onContinue = { onStartRun(run.runId) },
                onDiscard = { viewModel.discard(run.runId) },
            )
        }
        RoutineCard(
            title = stringResource(R.string.routine_daytime_title),
            description = stringResource(R.string.routine_daytime_desc),
            icon = Icons.Filled.WbSunny,
            onClick = { setupFor = RoutineType.DAYTIME },
        )
        RoutineCard(
            title = stringResource(R.string.routine_bedtime_title),
            description = stringResource(R.string.routine_bedtime_desc),
            onClick = { setupFor = RoutineType.BEDTIME },
        )
        // Rapid-fire is split by cue type so each is an undiluted single stream (numbers OR times).
        RoutineCard(
            title = stringResource(R.string.quiz_title_format, stringResource(R.string.quiz_cue_numbers)),
            description = stringResource(R.string.quiz_card_desc_numbers),
            icon = Icons.Filled.Speed,
            onClick = { onOpenRapidFire(CueType.NUMBER) },
        )
        RoutineCard(
            title = stringResource(R.string.quiz_title_format, stringResource(R.string.quiz_cue_times)),
            description = stringResource(R.string.quiz_card_desc_times),
            icon = Icons.Filled.Speed,
            onClick = { onOpenRapidFire(CueType.TIME) },
        )
        RoutineCard(
            title = stringResource(R.string.quiz_title_format, stringResource(R.string.quiz_cue_restaurant)),
            description = stringResource(R.string.quiz_card_desc_restaurant),
            icon = Icons.Filled.Speed,
            onClick = { onOpenRapidFire(CueType.RESTAURANT) },
        )
        // Hidden for a language with no authored courses — Spanish past tenses ship first.
        if (coursesAvailable) {
            RoutineCard(
                title = stringResource(R.string.course_card_title),
                description = stringResource(R.string.course_card_desc),
                icon = Icons.Filled.School,
                onClick = onOpenCourses,
            )
        }
        // Always offered: the bank is built from the learner's own cards and reading, so it needs
        // no lexicon, no frequency list and no pack — only a deck, which they necessarily have.
        RoutineCard(
            title = stringResource(R.string.routines_cloze_title),
            description = stringResource(R.string.routines_cloze_desc),
            icon = Icons.Filled.Extension,
            onClick = onOpenCloze,
        )
        // Always offered, like the bank it draws on; the screen itself asks for the voice model and
        // points back to the bank when either is missing.
        RoutineCard(
            title = stringResource(R.string.rebuild_title),
            description = stringResource(R.string.routines_rebuild_desc),
            icon = Icons.Filled.Hearing,
            onClick = onOpenListenRebuild,
        )
        // Hidden outside Spanish: there is no verb lexicon to drill from.
        if (conjugationAvailable) {
            RoutineCard(
                title = stringResource(R.string.conj_card_title),
                description = stringResource(R.string.conj_card_desc),
                icon = Icons.Filled.Bolt,
                onClick = onOpenConjugationDrill,
            )
        }
        // Hidden for a language with no bundled frequency list — there is nothing to scope to. The
        // routine planner draws on the same drill; this card is the way to ask for one directly.
        if (frequencyDrillAvailable) {
            RoutineCard(
                title = stringResource(R.string.freq_drill_title),
                description = stringResource(R.string.freq_drill_explain),
                icon = Icons.Filled.School,
                onClick = { drillDialog = true },
            )
        }
    }

    if (drillDialog) {
        FrequencyDrillDialog(
            cardCount = viewModel::frequentWordCount,
            onStart = { size, maxRank -> drillDialog = false; onOpenFrequencyDrill(size, maxRank) },
            onDismiss = { drillDialog = false },
        )
    }

    setupFor?.let { routineType ->
        RoutineSetupSheet(
            routineType = routineType,
            suggestions = suggestions[routineType].orEmpty(),
            onDismiss = { setupFor = null },
            onBegin = { duration, topic, context ->
                setupFor = null
                scope.launch { onStartRun(viewModel.startRoutine(routineType, duration, topic, context)) }
            },
        )
    }
}

/**
 * The way back into a routine that was left unfinished. Tapping it reopens the player at the step it
 * stopped on; the X discards the run, for the times it was left on purpose. Tinted so it reads as a
 * different kind of thing from the launchers below it.
 */
@Composable
private fun ResumeCard(run: ResumableRoutine, onContinue: () -> Unit, onDiscard: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onContinue),
    ) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                run.type.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(
                    stringResource(R.string.routine_resume_title, stringResource(run.type.titleRes)),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    run.statusLine(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onDiscard) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.routine_resume_discard),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** Where an unfinished run stands, in a line: still being prepared, ready and unopened, or part-way. */
@Composable
internal fun ResumableRoutine.statusLine(): String = when {
    preparing -> stringResource(R.string.routine_resume_preparing)
    notStarted -> stringResource(R.string.routine_resume_ready)
    else -> stringResource(R.string.routine_resume_step, step, total)
}

internal val RoutineType.icon: ImageVector
    get() = when (this) {
        RoutineType.BEDTIME -> Icons.Filled.Bedtime
        RoutineType.DAYTIME -> Icons.Filled.WbSunny
    }

/** The wording and duration choices each routine's setup sheet offers. */
internal val RoutineType.titleRes: Int
    get() = when (this) {
        RoutineType.BEDTIME -> R.string.routine_bedtime_title
        RoutineType.DAYTIME -> R.string.routine_daytime_title
    }

// "How long tonight?" / "a calming theme" only make sense for the wind-down.
private val RoutineType.durationLabelRes: Int
    get() = when (this) {
        RoutineType.BEDTIME -> R.string.routine_duration_label
        RoutineType.DAYTIME -> R.string.routine_duration_label_daytime
    }

private val RoutineType.topicHintRes: Int
    get() = when (this) {
        RoutineType.BEDTIME -> R.string.routine_topic_hint
        RoutineType.DAYTIME -> R.string.routine_topic_hint_daytime
    }

private val RoutineType.durationChoices: List<Int>
    get() = when (this) {
        RoutineType.BEDTIME -> listOf(5, 10, 20)
        // A daytime session is bounded by the day rather than by sleep, so it tops out shorter.
        RoutineType.DAYTIME -> listOf(5, 10, 15)
    }

/**
 * Picks the frequency band and session size for a directly-launched frequent-word drill.
 *
 * The count is fetched per band rather than for all five up front: one read on each tap, against
 * five before the dialog could appear.
 */
@Composable
private fun FrequencyDrillDialog(
    cardCount: suspend (Int) -> Int,
    onStart: (size: Int, maxRank: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var scope by remember { mutableStateOf(FrequencyBucket.TOP_500) }
    var size by remember { mutableIntStateOf(DRILL_SIZES[1]) }
    var available by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(scope) {
        available = null
        available = cardCount(scope.maxRank)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.freq_drill_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SegmentedToggle(
                    selected = FrequencyBucket.entries.indexOf(scope),
                    labels = FrequencyBucket.entries.map { it.pickerLabel() },
                    onSelect = { scope = FrequencyBucket.entries[it] },
                )
                Text(stringResource(R.string.freq_drill_size), style = MaterialTheme.typography.labelLarge)
                SegmentedToggle(
                    selected = DRILL_SIZES.indexOf(size).coerceAtLeast(0),
                    labels = DRILL_SIZES.map { "$it" },
                    onSelect = { size = DRILL_SIZES[it] },
                )
                Text(
                    stringResource(R.string.freq_drill_count, available ?: 0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.freq_drill_explain),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = (available ?: 0) > 0, onClick = { onStart(size, scope.maxRank) }) {
                Text(stringResource(R.string.tab_review))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

private val DRILL_SIZES = listOf(10, 20, 50)

@Composable
private fun RoutineCard(
    title: String,
    description: String,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Filled.Bedtime,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(0.dp))
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RoutineSetupSheet(
    routineType: RoutineType,
    suggestions: List<TopicEntity>,
    onDismiss: () -> Unit,
    onBegin: (durationMinutes: Int, topic: String?, topicContext: String?) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var duration by remember { mutableIntStateOf(10) }
    var topic by remember { mutableStateOf("") }
    // Context tags along only when the topic came from a suggestion chip (free text has none).
    var topicContext by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(routineType.titleRes),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Text(stringResource(routineType.durationLabelRes), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                routineType.durationChoices.forEach { mins ->
                    FilterChip(
                        selected = duration == mins,
                        onClick = { duration = mins },
                        label = { Text(stringResource(R.string.routine_minutes, mins)) },
                    )
                }
            }

            OutlinedTextField(
                value = topic,
                onValueChange = { topic = it; topicContext = null },
                label = { Text(stringResource(R.string.routine_topic_label)) },
                placeholder = { Text(stringResource(routineType.topicHintRes)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (suggestions.isNotEmpty()) {
                Text(stringResource(R.string.routine_suggestions_label), style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    suggestions.forEach { suggestion ->
                        FilterChip(
                            selected = topic == suggestion.phrase,
                            onClick = {
                                topic = suggestion.phrase
                                topicContext = suggestion.description
                            },
                            label = { Text(suggestion.phrase) },
                        )
                    }
                }
            }

            Button(
                onClick = { onBegin(duration, topic.ifBlank { null }, topicContext) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.routine_start))
            }
        }
    }
}
