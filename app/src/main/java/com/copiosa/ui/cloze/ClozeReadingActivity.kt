package com.copiosa.ui.cloze

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.cloze.ClozePassage
import com.copiosa.domain.cloze.ClozePassageItem
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.routines.activities.QuestionHeader
import com.copiosa.ui.theme.SourceSerif

/**
 * One passage with several gaps and a bank of words to fill them from.
 *
 * Tap a word to put it in the gap that is waiting (the first empty one, or whichever was last
 * tapped); tap a filled gap to take its word back. When every gap is filled the passage is checked
 * as a whole — each gap marked, the wrong ones showing what belonged — and the translation appears,
 * which until then would have given the game away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClozeReadingActivity(
    passage: ClozePassageItem,
    index: Int,
    total: Int,
    title: String,
    onExit: () -> Unit,
    onChecked: (results: List<Pair<Long, Boolean>>) -> Unit,
    onContinue: () -> Unit,
) {
    // Per gap, the index into the bank of the word placed there. Indices rather than words, so a
    // word the bank holds twice is two separate tiles.
    val placed = remember(passage) { mutableStateListOf<Int?>().apply { repeat(passage.blanks.size) { add(null) } } }
    var active by remember(passage) { mutableIntStateOf(0) }
    var checked by remember(passage) { mutableStateOf(false) }

    fun place(bankIndex: Int) {
        val gap = if (placed.getOrNull(active) == null) active else placed.indexOfFirst { it == null }
        if (gap < 0) return
        placed[gap] = bankIndex
        active = placed.indexOfFirst { it == null }.takeIf { it >= 0 } ?: gap
        if (placed.none { it == null }) {
            checked = true
            onChecked(passage.blanks.mapIndexed { i, blank -> blank.cardId to ClozePassage.fits(blank, passage.bank[placed[i]!!]) })
        }
    }

    Column(Modifier.fillMaxSize()) {
        QuestionHeader(
            title = title,
            subtitle = null,
            onExit = onExit,
            index = index,
            total = total,
            outcomes = List(total) { null },
            action = null,
        )
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Spacer(Modifier.height(12.dp))
            SectionLabel(stringResource(R.string.cloze_reading_prompt))
            Spacer(Modifier.height(12.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                var cursor = 0
                passage.blanks.forEachIndexed { i, blank ->
                    Words(passage.text.substring(cursor, blank.start))
                    val word = placed[i]?.let { passage.bank[it] }
                    Gap(
                        word = word,
                        state = when {
                            !checked -> if (i == active && word == null) GapState.WAITING else GapState.OPEN
                            ClozePassage.fits(blank, word) -> GapState.RIGHT
                            else -> GapState.WRONG
                        },
                        answer = blank.answer,
                        onClick = if (checked) null else ({
                            if (word != null) placed[i] = null
                            active = i
                        }),
                    )
                    cursor = blank.end
                }
                Words(passage.text.substring(cursor))
            }

            Spacer(Modifier.height(20.dp))
            if (!checked) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    passage.bank.forEachIndexed { bankIndex, word ->
                        val used = bankIndex in placed
                        Surface(
                            onClick = { place(bankIndex) },
                            enabled = !used,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            // A used word keeps its place as a ghost, so the rest don't shift under the finger.
                            modifier = if (used) Modifier.alpha(0f) else Modifier,
                        ) {
                            Text(word, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                        }
                    }
                }
            } else {
                passage.translation?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SourceSerif, fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        if (checked) {
            val right = passage.blanks.indices.count { ClozePassage.fits(passage.blanks[it], placed[it]?.let(passage.bank::get)) }
            val scheme = MaterialTheme.colorScheme
            Column(Modifier.fillMaxWidth().background(scheme.surface)) {
                Box(Modifier.fillMaxWidth().height(2.dp).background(if (right == passage.blanks.size) scheme.primary else scheme.tertiary))
                Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.cloze_results_score, right, passage.blanks.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Button(
                        onClick = onContinue,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        shape = RoundedCornerShape(16.dp),
                    ) { Text(stringResource(R.string.routine_continue)) }
                }
            }
        }
    }
}

private val WORD_BREAK = Regex("\\s+")

/** A run of the passage between two gaps, one word per flow item so it wraps like prose. */
@Composable
private fun Words(text: String) {
    text.trim().split(WORD_BREAK).filter { it.isNotEmpty() }.forEach { word ->
        Text(
            word,
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = SourceSerif),
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

private enum class GapState { OPEN, WAITING, RIGHT, WRONG }

@Composable
private fun Gap(word: String?, state: GapState, answer: String, onClick: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    val (container, content, border) = when (state) {
        GapState.OPEN -> Triple(scheme.surface, scheme.onSurface, scheme.outlineVariant)
        GapState.WAITING -> Triple(scheme.primaryContainer.copy(alpha = 0.4f), scheme.onSurface, scheme.primary)
        GapState.RIGHT -> Triple(scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary)
        // Tertiary rather than error, as everywhere else a miss is shown.
        GapState.WRONG -> Triple(scheme.tertiaryContainer, scheme.onTertiaryContainer, scheme.tertiary)
    }
    val shape = RoundedCornerShape(8.dp)
    val label: @Composable () -> Unit = {
        Box(Modifier.widthIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
            Text(
                // A miss shows what belonged there; the word that was tried is already known to be wrong.
                if (state == GapState.WRONG) answer else word.orEmpty(),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = SourceSerif),
                fontWeight = FontWeight.Medium,
            )
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = container, contentColor = content, border = BorderStroke(1.dp, border), content = label)
    } else {
        Surface(shape = shape, color = container, contentColor = content, border = BorderStroke(1.dp, border), content = label)
    }
}
