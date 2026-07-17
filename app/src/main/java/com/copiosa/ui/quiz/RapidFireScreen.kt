package com.copiosa.ui.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.copiosa.R
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.quiz.SessionStats
import com.copiosa.ui.audio.TtsDownloadPrompt

/**
 * The standalone rapid-fire listening quiz, scoped to a single cue type ([focusType]) so the learner
 * gets an undiluted stream of numbers OR times: gate on the voice model and a rendered pool, then run
 * a session (length picked on the home screen) with live score/streak chrome, ending in a stats
 * summary. The gear opens the pool management screen (which doubles as first-time setup when no pool
 * exists yet — the pool itself still covers every configured type).
 */
@Composable
fun RapidFireScreen(
    focusType: CueType,
    onBack: () -> Unit,
    onOpenPool: () -> Unit,
    viewModel: RapidFireViewModel = hiltViewModel(),
) {
    val home by viewModel.home.collectAsState()
    val session by viewModel.session.collectAsState()
    val questionCount by viewModel.questionCount.collectAsState()
    val playbackSpeed by viewModel.playbackSpeed.collectAsState()

    val active = session
    when {
        active == null -> RapidFireHome(home, focusType, questionCount, playbackSpeed, viewModel, onBack, onOpenPool)
        active.finished -> ResultsScreen(
            stats = active.stats,
            topicSplit = active.topicSplit,
            onAgain = { viewModel.dismissSession(); viewModel.restart() },
            onDone = { viewModel.dismissSession() },
        )
        else -> Column(Modifier.fillMaxSize()) {
            SessionChrome(active)
            Box(Modifier.weight(1f).padding(20.dp)) {
                RapidFireActivity(
                    items = active.items,
                    onAnswer = viewModel::onAnswer,
                    onComplete = viewModel::onSessionComplete,
                    speed = playbackSpeed,
                )
            }
        }
    }
}

@Composable
private fun RapidFireHome(
    home: RapidFireHomeUi,
    focusType: CueType,
    questionCount: Int,
    playbackSpeed: Float,
    viewModel: RapidFireViewModel,
    onBack: () -> Unit,
    onOpenPool: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.quiz_title_format, cueLabel(focusType)),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onOpenPool) {
                Icon(Icons.Filled.Tune, contentDescription = stringResource(R.string.quiz_pool_open))
            }
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                home.loading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }

                // No voice model yet: same opt-in dialog as the Audio tab.
                !home.modelReady && !home.modelDownloading -> TtsDownloadPrompt(
                    onConfirm = viewModel::downloadModel,
                    onDismiss = onBack,
                )

                home.modelDownloading -> Box(Modifier.fillMaxWidth().padding(top = 48.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.quiz_model_downloading), style = MaterialTheme.typography.bodyMedium)
                    }
                }

                home.progress == null -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(stringResource(R.string.quiz_intro), style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onOpenPool, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.quiz_setup_pool))
                    }
                }

                else -> {
                    val progress = home.progress
                    if (progress.ready < progress.total) {
                        Text(
                            stringResource(R.string.quiz_pool_generating),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    QuestionCountSelector(
                        selected = questionCount,
                        onSelect = viewModel::setQuestionCount,
                    )
                    SpeedSelector(
                        selected = playbackSpeed,
                        onSelect = viewModel::setPlaybackSpeed,
                    )
                    // Scoped to one cue type: a single undiluted stream (numbers OR times).
                    ExerciseCard(
                        label = cueLabel(focusType),
                        ready = home.readyCount,
                        canStart = home.canStart(),
                        onStart = { viewModel.startSession() },
                    )
                }
            }
        }
    }
}

/** Picks how many questions each session runs; the choice sticks for "play again" too. */
@Composable
private fun QuestionCountSelector(selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.quiz_question_count),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUESTION_COUNTS.forEach { count ->
                FilterChip(
                    selected = selected == count,
                    onClick = { onSelect(count) },
                    label = { Text(count.toString()) },
                )
            }
        }
    }
}

private val QUESTION_COUNTS = listOf(10, 20, 30, 50)

/** Picks the listening speed (manual ±50%); the choice sticks for "play again" too. */
@Composable
private fun SpeedSelector(selected: Float, onSelect: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.quiz_speed),
            style = MaterialTheme.typography.titleSmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PLAYBACK_SPEEDS.forEach { speed ->
                FilterChip(
                    selected = selected == speed,
                    onClick = { onSelect(speed) },
                    label = { Text(stringResource(R.string.quiz_speed_label, speedText(speed))) },
                )
            }
        }
    }
}

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f)

/** "1", "0.75", … — a whole number drops its trailing ".0". */
private fun speedText(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()

/** One top-level exercise (Numbers, Times, …): its readiness and a start button. */
@Composable
private fun ExerciseCard(label: String, ready: Int, canStart: Boolean, onStart: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.quiz_exercise_ready, ready),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onStart, enabled = canStart, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.quiz_start))
            }
        }
    }
}

@Composable
private fun cueLabel(cue: CueType): String = stringResource(
    when (cue) {
        CueType.NUMBER -> R.string.quiz_cue_numbers
        CueType.TIME -> R.string.quiz_cue_times
    },
)

/** Score / streak / answered header shown above the running session. */
@Composable
private fun SessionChrome(session: RapidFireSessionUi) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.quiz_score, session.score),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        session.lastPoints?.let {
            Text(
                "+$it",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(
            stringResource(R.string.quiz_streak, session.streak),
            style = MaterialTheme.typography.titleMedium,
            color = if (session.streak >= 3) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ResultsScreen(
    stats: SessionStats?,
    topicSplit: Map<String, Pair<Int, Int>>,
    onAgain: () -> Unit,
    onDone: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(R.string.quiz_results_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(24.dp))
        if (stats != null) {
            StatRow(stringResource(R.string.quiz_results_score), stats.score.toString())
            StatRow(stringResource(R.string.quiz_results_accuracy), "${stats.correct}/${stats.answered}")
            StatRow(
                stringResource(R.string.quiz_results_avg_time),
                stringResource(R.string.quiz_results_seconds, stats.avgLatencyMs / 1000.0),
            )
            StatRow(stringResource(R.string.quiz_results_best_streak), stats.bestStreak.toString())
            topicSplit.forEach { (label, split) ->
                StatRow(label, "${split.first}/${split.second}")
            }
        }
        Spacer(Modifier.height(32.dp))
        Button(onClick = onAgain, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.quiz_again))
        }
        TextButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.quiz_done))
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
    }
}
