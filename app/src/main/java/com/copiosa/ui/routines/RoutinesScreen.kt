package com.copiosa.ui.routines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.copiosa.R
import com.copiosa.data.local.TopicEntity
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.routine.RoutineType
import kotlinx.coroutines.launch

/**
 * The Routines tab: a list of launchable routines (just the bedtime wind-down for now). Tapping one
 * opens a setup sheet (duration + optional topic seeded from the interest table); confirming creates
 * a run, starts synthesis, and hands [onStartRun] the run id so the host can open the player.
 */
@Composable
fun RoutinesScreen(
    onStartRun: (Long) -> Unit,
    onOpenStatus: () -> Unit,
    onOpenRapidFire: (CueType) -> Unit,
    viewModel: RoutinesViewModel = hiltViewModel(),
) {
    val suggestions by viewModel.suggestions.collectAsState()
    val scope = rememberCoroutineScope()
    var setupOpen by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.routines_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenStatus) {
                Icon(
                    Icons.Filled.Insights,
                    contentDescription = stringResource(R.string.routine_status_open),
                )
            }
        }
        RoutineCard(
            title = stringResource(R.string.routine_bedtime_title),
            description = stringResource(R.string.routine_bedtime_desc),
            onClick = { setupOpen = true },
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
    }

    if (setupOpen) {
        BedtimeSetupSheet(
            suggestions = suggestions,
            onDismiss = { setupOpen = false },
            onBegin = { duration, topic, context ->
                setupOpen = false
                scope.launch { onStartRun(viewModel.startRoutine(RoutineType.BEDTIME, duration, topic, context)) }
            },
        )
    }
}

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
private fun BedtimeSetupSheet(
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
                stringResource(R.string.routine_bedtime_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )

            Text(stringResource(R.string.routine_duration_label), style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(5, 10, 20).forEach { mins ->
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
                placeholder = { Text(stringResource(R.string.routine_topic_hint)) },
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
