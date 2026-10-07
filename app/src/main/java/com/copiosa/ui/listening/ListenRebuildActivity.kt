package com.copiosa.ui.listening

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.listening.ListenRebuild
import com.copiosa.domain.listening.RebuildItem
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.quiz.QuizClipPlayer
import com.copiosa.ui.routines.activities.HeaderAction
import com.copiosa.ui.routines.activities.QuestionHeader
import com.copiosa.ui.theme.SourceSerif
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Listen-and-rebuild's runner: hear the sentence, tap its words into order, see it written out.
 *
 * The sentence is not shown until it has been rebuilt — that is the whole difference from every
 * other place the app plays speech. A full arrangement is checked at once. When it is wrong, the
 * words that were right from the start stay put and the rest drift back to the bank after a beat,
 * so a miss narrows the problem instead of resetting it; the item then counts as missed, but the
 * learner still finishes it. Show answer is always there, so no sentence is a dead end.
 *
 * Shared by the standalone screen (which passes [title] for its own header) and the routine step
 * (which is framed by the player and passes none). [RebuildItem.audioPath] may arrive late on the
 * standalone screen; the item waits on it and plays the moment it lands.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ListenRebuildActivity(
    items: List<RebuildItem>,
    onAnswer: (index: Int, correct: Boolean, latencyMs: Long) -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 20.dp,
    title: String? = null,
    onExit: (() -> Unit)? = null,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }
    // Keyed on the item count rather than the list: the standalone screen swaps in a new list each
    // time a clip lands, and that must not restart the sitting.
    var at by remember(items.size) { mutableIntStateOf(0) }
    val outcomes = remember(items.size) { mutableStateListOf<Boolean?>().apply { repeat(items.size) { add(null) } } }
    val item = items[at.coerceIn(items.indices)]

    val scope = rememberCoroutineScope()
    val player = remember { QuizClipPlayer() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    var playing by remember { mutableStateOf(false) }

    fun play(speed: Float) {
        val path = item.audioPath ?: return
        playing = true
        player.play(path, speed) { playing = false }
    }

    // Per-item state. `placed` holds tile indices in the order tapped; the first `locked` of them
    // were confirmed right by a check and can no longer be taken back.
    val placed = remember(at) { mutableStateListOf<Int>() }
    var locked by remember(at) { mutableIntStateOf(0) }
    var missed by remember(at) { mutableStateOf(false) }
    var revealed by remember(at) { mutableStateOf(false) }
    // How many placed tiles were right when a wrong arrangement was checked; non-null while the wrong
    // ones are shown as wrong, before they return to the bank.
    var wrongFrom by remember(at) { mutableStateOf<Int?>(null) }
    val shownAt = remember(at) { System.currentTimeMillis() }

    fun finish(correct: Boolean) {
        revealed = true
        outcomes[at] = correct
        onAnswer(at, correct, System.currentTimeMillis() - shownAt)
    }

    // A full arrangement is checked as soon as the last tile goes down: there is nothing else to do
    // with a full answer line, so a separate Check button would only be a tap of ceremony.
    fun place(tile: Int) {
        placed.add(tile)
        if (placed.size != item.tiles.size) return
        val prefix = ListenRebuild.correctPrefix(item.tiles, placed)
        if (prefix == item.tiles.size) {
            finish(correct = !missed)
            return
        }
        missed = true
        wrongFrom = prefix
        val line = placed
        val forItem = at
        scope.launch {
            delay(WRONG_BEAT_MS)
            // Skipped past during the beat: this item's state is gone, and so is the reason to tidy
            // it. `at` is read through its state here, so this sees the current item, not the old one.
            if (at != forItem) return@launch
            while (line.size > prefix) line.removeAt(line.lastIndex)
            locked = prefix
            wrongFrom = null
        }
    }

    fun advance() {
        player.release()
        playing = false
        if (at + 1 >= items.size) onComplete() else at++
    }

    // Plays each sentence on arrival — and, on the standalone screen, as soon as a late clip lands.
    LaunchedEffect(at, item.audioPath) { if (item.audioPath != null) play(1.0f) }

    Column(modifier.fillMaxSize()) {
        if (title != null) {
            QuestionHeader(
                title = title,
                subtitle = null,
                onExit = onExit,
                index = at,
                total = items.size,
                outcomes = outcomes,
                action = if (!revealed) {
                    HeaderAction(R.string.action_skip, filled = false) { advance() }
                } else {
                    null
                },
            )
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = contentPadding),
        ) {
            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.rebuild_prompt))
            Spacer(Modifier.height(12.dp))

            ListenControls(
                ready = item.audioPath != null,
                playing = playing,
                onPlay = { play(1.0f) },
                onSlow = { play(SLOW_SPEED) },
            )

            Spacer(Modifier.height(20.dp))

            // The answer line. Before anything is placed it says what to do; once revealed it holds
            // the rebuilt words, with the sentence as written underneath.
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .border(
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        RoundedCornerShape(12.dp),
                    )
                    .padding(10.dp),
            ) {
                if (placed.isEmpty()) {
                    Text(
                        stringResource(R.string.rebuild_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    placed.forEachIndexed { position, tile ->
                        val state = when {
                            revealed -> if (missed) TileState.SHOWN else TileState.RIGHT
                            wrongFrom != null && position >= wrongFrom!! -> TileState.WRONG
                            position < locked -> TileState.RIGHT
                            else -> TileState.PLACED
                        }
                        Tile(
                            text = item.tiles[tile],
                            state = state,
                            // Taking a tile back: only unconfirmed ones, and never mid-check.
                            onClick = if (!revealed && wrongFrom == null && position >= locked) {
                                { placed.removeAt(position) }
                            } else {
                                null
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            if (!revealed) {
                // The bank keeps a ghost where each placed tile was, so the rest don't jump around
                // under the finger as tiles leave.
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item.order.forEach { tile ->
                        val used = tile in placed
                        Tile(
                            text = item.tiles[tile],
                            state = TileState.BANK,
                            onClick = if (!used && wrongFrom == null) ({ place(tile) }) else null,
                            modifier = if (used) Modifier.alpha(0f) else Modifier,
                        )
                    }
                }
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = {
                    missed = true
                    placed.clear()
                    placed.addAll(item.tiles.indices)
                    finish(correct = false)
                }) {
                    Text(
                        stringResource(R.string.rebuild_show_answer),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    item.sentence,
                    style = MaterialTheme.typography.titleLarge.copy(fontFamily = SourceSerif),
                )
                item.translation?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = SourceSerif,
                            fontStyle = FontStyle.Italic,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (revealed) {
            ResultPanel(correct = !missed, contentPadding = contentPadding, onContinue = ::advance)
        }
    }
}

private enum class TileState { BANK, PLACED, RIGHT, WRONG, SHOWN }

@Composable
private fun Tile(
    text: String,
    state: TileState,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, border) = when (state) {
        TileState.BANK, TileState.PLACED -> Triple(scheme.surface, scheme.onSurface, scheme.outline)
        TileState.RIGHT -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        // Tertiary rather than error, as everywhere else a miss is shown: a wrong order is a step
        // in working it out, not an alarm.
        TileState.WRONG -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
        TileState.SHOWN -> Triple(scheme.surfaceVariant, scheme.onSurfaceVariant, Color.Transparent)
    }
    val shape = RoundedCornerShape(10.dp)
    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = container,
            contentColor = content,
            border = BorderStroke(1.dp, border),
            modifier = modifier,
        ) { TileText(text) }
    } else {
        Surface(
            shape = shape,
            color = container,
            contentColor = content,
            border = BorderStroke(1.dp, border),
            modifier = modifier,
        ) { TileText(text) }
    }
}

@Composable
private fun TileText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
    )
}

/** Play at normal speed, or slowed — the classic intensive-listening move on a hard segment. */
@Composable
private fun ListenControls(ready: Boolean, playing: Boolean, onPlay: () -> Unit, onSlow: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(onClick = onPlay, enabled = ready, modifier = Modifier.heightIn(min = 52.dp)) {
            if (ready) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null)
            } else {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(
                    when {
                        !ready -> R.string.rebuild_preparing
                        playing -> R.string.rebuild_playing
                        else -> R.string.rebuild_play
                    },
                ),
            )
        }
        OutlinedButton(onClick = onSlow, enabled = ready, modifier = Modifier.heightIn(min = 52.dp)) {
            Icon(Icons.Filled.SlowMotionVideo, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.rebuild_slow))
        }
    }
}

@Composable
private fun ResultPanel(correct: Boolean, contentPadding: Dp, onContinue: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accent = if (correct) scheme.primary else scheme.tertiary
    Column(Modifier.fillMaxWidth().background(scheme.surface)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(accent))
        Column(Modifier.padding(horizontal = contentPadding, vertical = 14.dp)) {
            Text(
                stringResource(if (correct) R.string.question_right else R.string.rebuild_missed),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                shape = RoundedCornerShape(16.dp),
            ) {
                Text(stringResource(R.string.routine_continue))
                Spacer(Modifier.width(8.dp))
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** How long a wrong arrangement stays on screen, marked, before its wrong tiles go back. */
private const val WRONG_BEAT_MS = 900L

/** "Slow" playback rate; pitch-preserving, via [QuizClipPlayer]. */
private const val SLOW_SPEED = 0.7f
