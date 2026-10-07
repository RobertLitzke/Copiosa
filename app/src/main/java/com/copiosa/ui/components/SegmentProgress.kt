package com.copiosa.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp

/** Beyond this many ticks they would be thinner than the gaps between them. */
private const val MAX_TICKS = 16

/**
 * One tick per step rather than a single sliding bar. A sitting is a handful of discrete beats —
 * read this, drill that — and the learner's real question at the top of a screen is "how many more
 * of these", which a continuous bar answers only by implication. Ticks are cheap to count.
 *
 * [outcomes] optionally colours each tick by how it went: right in [MaterialTheme.colorScheme]'s
 * `primary`, wrong in `tertiary`, unreached in `outlineVariant`. Amber rather than red for a miss
 * is deliberate — a wrong answer here is information, not a failure. Passing no outcomes falls back
 * to colouring purely by index, which is what a step-based player (a course lesson) wants: its
 * segments have no correctness of their own.
 *
 * Beyond [MAX_TICKS] steps it falls back to the plain bar it replaces.
 */
@Composable
fun SegmentProgress(
    index: Int,
    total: Int,
    modifier: Modifier = Modifier,
    outcomes: List<Boolean?> = emptyList(),
) {
    if (total <= 0) return
    val scheme = MaterialTheme.colorScheme
    if (total > MAX_TICKS) {
        LinearProgressIndicator(
            progress = { (index + 1f) / total },
            modifier = modifier.fillMaxWidth(),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        return
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(total) { i ->
            val colour = when {
                outcomes.isEmpty() -> if (i <= index) scheme.primary else scheme.outlineVariant
                outcomes.getOrNull(i) == true -> scheme.primary
                outcomes.getOrNull(i) == false -> scheme.tertiary
                // Not yet answered. The one being looked at still reads as "here", faintly.
                i == index -> scheme.outline
                else -> scheme.outlineVariant
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(colour, CircleShape),
            )
        }
    }
}
