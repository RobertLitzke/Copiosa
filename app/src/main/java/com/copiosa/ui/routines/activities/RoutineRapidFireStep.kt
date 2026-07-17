package com.copiosa.ui.routines.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.copiosa.R
import com.copiosa.domain.quiz.QuizSessionBuilder
import com.copiosa.domain.routine.RapidFireContent
import com.copiosa.ui.quiz.RapidFireActivity

/**
 * The rapid-fire step inside a routine: the shared [RapidFireActivity], topped with a compact speed
 * control. The block starts at the mastery-derived auto speed ([RapidFireContent.playbackSpeed]); the
 * learner can nudge it within the manual ±50% band as a live difficulty lever, and the speed actually
 * heard flows into the mastery credit.
 */
@Composable
internal fun RoutineRapidFireStep(
    content: RapidFireContent,
    onAnswer: (correct: Boolean, latencyMs: Long, speed: Float) -> Unit,
    onComplete: () -> Unit,
) {
    var speed by remember(content) { mutableFloatStateOf(content.playbackSpeed) }
    Column(Modifier.fillMaxSize()) {
        RoutineSpeedControl(speed = speed, onChange = { speed = it })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            RapidFireActivity(
                items = content.items,
                onAnswer = { _, correct, ms -> onAnswer(correct, ms, speed) },
                onComplete = onComplete,
                speed = speed,
            )
        }
    }
}

@Composable
private fun RoutineSpeedControl(speed: Float, onChange: (Float) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.quiz_speed), style = MaterialTheme.typography.labelLarge)
        IconButton(
            onClick = { onChange((speed - SPEED_STEP).coerceAtLeast(QuizSessionBuilder.MANUAL_SPEED_MIN)) },
            enabled = speed > QuizSessionBuilder.MANUAL_SPEED_MIN,
        ) { Icon(Icons.Filled.Remove, contentDescription = stringResource(R.string.quiz_speed_slower)) }
        Text(
            stringResource(R.string.quiz_speed_label, formatSpeed(speed)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        IconButton(
            onClick = { onChange((speed + SPEED_STEP).coerceAtMost(QuizSessionBuilder.MANUAL_SPEED_MAX)) },
            enabled = speed < QuizSessionBuilder.MANUAL_SPEED_MAX,
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.quiz_speed_faster)) }
    }
}

private const val SPEED_STEP = 0.1f

/** Trims a speed to a tidy label: 1.0 → "1", 0.9000004 → "0.9", 0.75 → "0.75". */
private fun formatSpeed(speed: Float): String = "%.2f".format(speed).trimEnd('0').trimEnd('.')
