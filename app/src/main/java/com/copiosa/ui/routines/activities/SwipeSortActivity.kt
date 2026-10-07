package com.copiosa.ui.routines.activities

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.copiosa.domain.language.LanguageProfiles
import com.copiosa.R
import com.copiosa.domain.routine.SortDimension
import com.copiosa.domain.routine.SwipeSortContent
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Swipe-to-sort: one word at a time, swiped left/right into a bin (masculine/feminine for the gender
 * dimension). Tactile and one-handed. A brief tint confirms right/wrong, then the next word slides in.
 */
@Composable
internal fun SwipeSortActivity(content: SwipeSortContent, lang: String, onComplete: () -> Unit) {
    val items = content.items
    // The bins are the target language's own articles ("el (m.)" / "o (m.)"), so they come off the
    // profile rather than out of a string resource that could only ever name one language's.
    val binLabels = when (content.dimension) {
        SortDimension.GENDER -> LanguageProfiles.forTag(lang)?.genderBins?.map { it.label }
    }
    if (items.isEmpty() || binLabels.isNullOrEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }

    var i by remember { mutableIntStateOf(0) }
    var offsetX by remember(i) { mutableFloatStateOf(0f) }
    var correct by remember(i) { mutableStateOf<Boolean?>(null) }
    val item = items[i]

    LaunchedEffect(correct, i) {
        if (correct != null) {
            delay(600)
            if (i + 1 >= items.size) onComplete() else i++
        }
    }
    fun commit(bin: Int) {
        if (correct != null) return
        correct = bin == item.binIndex
    }

    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.routine_swipe_prompt), style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("← ${binLabels[0]}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${binLabels[1]} →", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val container = when (correct) {
                true -> MaterialTheme.colorScheme.primaryContainer
                false -> MaterialTheme.colorScheme.errorContainer
                null -> MaterialTheme.colorScheme.surfaceVariant
            }
            Card(
                colors = CardDefaults.cardColors(containerColor = container),
                modifier = Modifier
                    .offset { IntOffset(offsetX.roundToInt(), 0) }
                    .pointerInput(i) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    offsetX < -SWIPE_THRESHOLD -> commit(0)
                                    offsetX > SWIPE_THRESHOLD -> commit(1)
                                    else -> offsetX = 0f
                                }
                            },
                            onDragCancel = { offsetX = 0f },
                        ) { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount
                        }
                    },
            ) {
                Text(item.word, Modifier.padding(40.dp), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

private const val SWIPE_THRESHOLD = 130f
