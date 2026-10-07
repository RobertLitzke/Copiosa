package com.copiosa.ui.routines

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.RoutinePlanCodec
import com.copiosa.domain.routine.RoutineRunStatus
import com.copiosa.domain.routine.RoutineType
import com.copiosa.domain.stats.FocusActivityType
import com.copiosa.ui.components.TrackFocusTime
import com.copiosa.ui.routines.activities.ActivityRenderContext
import com.copiosa.ui.routines.activities.ActivityRenderers

/**
 * Full-screen routine player: preparing → step through activities → the routine type's close.
 *
 * Every state has a way out. Leaving — by the header's X or the system back — keeps the run, which
 * the Routines tab and Home then offer to continue from the step it was left on; the one exit that
 * throws the run away is Cancel on the preparing screen, where there is nothing yet to come back to.
 */
@Composable
fun RoutinePlayerScreen(
    onExit: () -> Unit,
    viewModel: RoutinePlayerViewModel = hiltViewModel(),
) {
    val run by viewModel.run.collectAsStateWithLifecycle()
    val index by viewModel.index.collectAsStateWithLifecycle()
    val current = run

    TrackFocusTime(FocusActivityType.ROUTINE, current?.targetLanguage)

    when {
        current == null -> CenteredSpinner()
        current.status == RoutineRunStatus.SYNTHESIZING ->
            PreparingScreen(onCancel = { viewModel.cancelPreparing(onExit) })
        current.status == RoutineRunStatus.FAILED ->
            FailedScreen(message = current.error, onRetry = viewModel::retry, onExit = onExit)
        else -> {
            val plan = remember(current.planJson) { RoutinePlanCodec.decode(current.planJson) }
            if (plan == null) {
                // A plan that is stored but no longer decodes (see ARCHITECTURE.md on retiring an
                // activity) will not start decoding by being waited for, so it gets the failure
                // screen's retry rather than a spinner with no end.
                FailedScreen(message = null, onRetry = viewModel::retry, onExit = onExit)
            } else {
                LaunchedEffect(plan) { viewModel.startIfNeeded() }
                val step = index
                when {
                    // The saved position hasn't been read yet; see RoutinePlayerViewModel.index.
                    step == null -> CenteredSpinner()
                    step >= plan.activities.size -> DoneScreen(plan.routineType, onExit)
                    else -> ActivityHost(plan.activities, plan.targetLanguage, step, viewModel, onExit)
                }
            }
        }
    }
}

@Composable
private fun ActivityHost(
    activities: List<ActivityInstance>,
    lang: String,
    index: Int,
    viewModel: RoutinePlayerViewModel,
    onExit: () -> Unit,
) {
    val activity = activities[index]
    val total = activities.size
    val advance = { viewModel.advance(from = index, total = total) }

    // Rendering is a registry lookup on the content kind (see ActivityRenderers); a plan entry with
    // no registered renderer (e.g. from a newer app version) is skipped rather than crashing the run.
    val entry = ActivityRenderers.forContent(activity.content)
    if (entry == null) {
        LaunchedEffect(activity) { advance() }
        return
    }
    val ctx = ActivityRenderContext(activity, lang, index, total, viewModel, advance, onExit)
    if (entry.fullScreen) {
        entry.renderer.Render(ctx)
    } else {
        Column(Modifier.fillMaxSize()) {
            ctx.Header()
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

/**
 * The wait for synthesis. Cancel stops the generation and discards the run; backing out instead
 * leaves it being prepared, which the hint says so the two don't read as the same thing.
 */
@Composable
private fun PreparingScreen(onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.routine_preparing),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.routine_preparing_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
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

/**
 * The close, in the register of the routine it ends. The wind-down goes dark and says good night
 * whatever the theme; the daytime activation hands the learner back to their day, so it stays in the
 * app's own colours and says nothing about sleep.
 */
@Composable
private fun DoneScreen(routineType: RoutineType, onExit: () -> Unit) {
    val bedtime = routineType == RoutineType.BEDTIME
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(if (bedtime) Color(0xFF05060A) else scheme.background)
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                stringResource(if (bedtime) R.string.routine_done_title else R.string.routine_done_title_daytime),
                style = MaterialTheme.typography.headlineMedium,
                color = if (bedtime) Color(0xFFB8BDD0) else scheme.onBackground,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(if (bedtime) R.string.routine_done_body else R.string.routine_done_body_daytime),
                style = MaterialTheme.typography.bodyLarge,
                color = if (bedtime) Color(0xFF6B7280) else scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onExit) {
                Text(
                    stringResource(R.string.routine_exit),
                    color = if (bedtime) Color(0xFF8A8FA3) else scheme.primary,
                )
            }
        }
    }
}
