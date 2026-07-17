package com.copiosa.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.copiosa.R
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.RoutinePlanCodec
import com.copiosa.domain.routine.RoutineRunStatus
import com.copiosa.ui.routines.activities.ActivityRenderContext
import com.copiosa.ui.routines.activities.ActivityRenderers

/** Full-screen routine player: preparing → step through activities → good-night close. */
@Composable
fun RoutinePlayerScreen(
    onExit: () -> Unit,
    viewModel: RoutinePlayerViewModel = hiltViewModel(),
) {
    val run by viewModel.run.collectAsState()
    val index by viewModel.index.collectAsState()
    val current = run

    when {
        current == null -> CenteredSpinner()
        current.status == RoutineRunStatus.SYNTHESIZING ->
            CenteredMessage(stringResource(R.string.routine_preparing), spinner = true)
        current.status == RoutineRunStatus.FAILED ->
            FailedScreen(message = current.error, onRetry = viewModel::retry, onExit = onExit)
        else -> {
            val plan = remember(current.planJson) { RoutinePlanCodec.decode(current.planJson) }
            if (plan == null) {
                CenteredMessage(stringResource(R.string.routine_preparing), spinner = true)
            } else {
                LaunchedEffect(plan) { viewModel.startIfNeeded() }
                if (index >= plan.activities.size) {
                    DoneScreen(onExit)
                } else {
                    ActivityHost(plan.activities, index, viewModel, onExit)
                }
            }
        }
    }
}

@Composable
private fun ActivityHost(
    activities: List<ActivityInstance>,
    index: Int,
    viewModel: RoutinePlayerViewModel,
    onExit: () -> Unit,
) {
    val activity = activities[index]
    val total = activities.size
    val advance = { viewModel.advance(total) }

    // Rendering is a registry lookup on the content kind (see ActivityRenderers); a plan entry with
    // no registered renderer (e.g. from a newer app version) is skipped rather than crashing the run.
    val entry = ActivityRenderers.forContent(activity.content)
    if (entry == null) {
        LaunchedEffect(activity) { advance() }
        return
    }
    val ctx = ActivityRenderContext(activity, index, total, viewModel, advance, onExit)
    if (entry.fullScreen) {
        entry.renderer.Render(ctx)
    } else {
        Column(Modifier.fillMaxSize()) {
            LinearProgressIndicator(
                progress = { (index + 1f) / total },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.fillMaxSize().padding(20.dp)) {
                entry.renderer.Render(ctx)
            }
        }
    }
}

// --- Status / scaffold ---

@Composable
private fun CenteredSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
private fun CenteredMessage(message: String, spinner: Boolean = false) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (spinner) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
            }
            Text(message, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun FailedScreen(message: String?, onRetry: () -> Unit, onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                message ?: stringResource(R.string.routine_failed),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(onClick = onRetry) { Text(stringResource(R.string.routine_retry)) }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onExit) { Text(stringResource(R.string.routine_exit)) }
        }
    }
}

@Composable
private fun DoneScreen(onExit: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xFF05060A)).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(R.string.routine_done_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color(0xFFB8BDD0),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.routine_done_body),
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF6B7280),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onExit) { Text(stringResource(R.string.routine_exit), color = Color(0xFF8A8FA3)) }
        }
    }
}
