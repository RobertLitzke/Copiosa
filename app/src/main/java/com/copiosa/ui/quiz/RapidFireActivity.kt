package com.copiosa.ui.quiz

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.routine.RapidFireItem
import kotlinx.coroutines.delay
import java.io.File

/**
 * The rapid-fire question runner shared by the standalone quiz and the routine step: per item, play
 * the clip (options hidden, pulsing speaker), reveal the digit options the moment the audio ends and
 * start the latency clock, flash green/red on the tap, then auto-advance. Items whose audio file no
 * longer exists (the pool was rebuilt) are skipped up front.
 */
@Composable
fun RapidFireActivity(
    items: List<RapidFireItem>,
    onAnswer: (item: RapidFireItem, correct: Boolean, latencyMs: Long) -> Unit,
    onComplete: () -> Unit,
    speed: Float = 1.0f,
) {
    val playable = remember(items) { items.filter { File(it.audioPath).exists() } }
    if (playable.isEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }
    val player = remember { QuizClipPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }

    var i by remember { mutableIntStateOf(0) }
    var answering by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var shownAt by remember { mutableStateOf(0L) }

    // Guard the index: if it ever falls out of range (a rebuilt pool, a torn-down session mid-frame),
    // finish cleanly rather than indexing past the end.
    val item = playable.getOrNull(i)
    if (item == null) {
        LaunchedEffect(playable.size) { onComplete() }
        return
    }
    LaunchedEffect(i, speed) {
        answering = false
        selected = null
        player.play(item.audioPath, speed) {
            // A replayed clip completes again — only the first completion starts the clock.
            if (!answering) {
                answering = true
                shownAt = System.currentTimeMillis()
                // Prepare the next clip during the answer window so it starts with no load gap.
                player.prewarm(playable.getOrNull(i + 1)?.audioPath)
            }
        }
    }
    // Brief green/red beat, then next question. Clear the reveal state BEFORE advancing so the next
    // item never paints a frame with this item's picked index still set — that stale index isn't the
    // next answer, so it would flash red for a frame even on a correct pick.
    LaunchedEffect(selected) {
        if (selected != null) {
            delay(ADVANCE_DELAY_MS)
            val last = i + 1 >= playable.size
            answering = false
            selected = null
            if (last) onComplete() else i++
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            stringResource(R.string.quiz_progress, i + 1, playable.size),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (!answering) {
                PulsingSpeaker()
            } else {
                OptionsGrid(
                    options = item.options,
                    answerIndex = item.answerIndex,
                    selected = selected,
                    onSelect = { idx ->
                        if (selected == null) {
                            selected = idx
                            onAnswer(item, idx == item.answerIndex, System.currentTimeMillis() - shownAt)
                        }
                    },
                )
            }
        }
        TextButton(
            onClick = { player.replay() },
            enabled = answering && selected == null,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        ) {
            Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.quiz_replay))
        }
    }
}

@Composable
private fun PulsingSpeaker() {
    val transition = rememberInfiniteTransition(label = "pulse")
    val scale by transition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "pulseScale",
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(
            Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp).graphicsLayer(scaleX = scale, scaleY = scale),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.quiz_listening),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Big digit options in two-per-row tiles — built for fast thumbs, not careful reading. */
@Composable
private fun OptionsGrid(
    options: List<String>,
    answerIndex: Int,
    selected: Int?,
    onSelect: (Int) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        options.chunked(2).forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEachIndexed { colIndex, option ->
                    val idx = rowIndex * 2 + colIndex
                    OptionTile(
                        text = option,
                        reveal = selected != null,
                        isCorrect = idx == answerIndex,
                        isWrongPick = selected == idx && idx != answerIndex,
                        onClick = { onSelect(idx) },
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun RowScope.OptionTile(
    text: String,
    reveal: Boolean,
    isCorrect: Boolean,
    isWrongPick: Boolean,
    onClick: () -> Unit,
) {
    val container = when {
        !reveal -> MaterialTheme.colorScheme.surfaceVariant
        isCorrect -> MaterialTheme.colorScheme.primaryContainer
        isWrongPick -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Button(
        onClick = onClick,
        enabled = !reveal,
        // Pin the disabled colours so the correct/your-pick marking survives the disable-on-reveal
        // (same trick as the routine player's MultipleChoiceActivity).
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContainerColor = container,
            disabledContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = Modifier.weight(1f).height(72.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

private const val ADVANCE_DELAY_MS = 700L
