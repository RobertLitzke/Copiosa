package com.copiosa.ui.routines.activities

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.routine.RecognitionCardsContent
import com.copiosa.ui.routines.RoutinePlayerViewModel

/**
 * Recognition cards: the gentle reverse of the flashcard step. Cards are drawn from the same SRS pool
 * (so the wind-down recycles due/upcoming words), cued on the TARGET side and revealed on the native
 * side — comprehension, not graded recall. Seeing a card lightly pushes its next review (passive review).
 */
@Composable
internal fun RoutineRecognitionStep(
    content: RecognitionCardsContent,
    index: Int,
    total: Int,
    viewModel: RoutinePlayerViewModel,
    onComplete: () -> Unit,
) {
    var cards by remember { mutableStateOf<List<FlashcardEntity>?>(null) }
    LaunchedEffect(Unit) { cards = viewModel.recognitionCards(content.cardLimit) }

    val list = cards
    if (list == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (list.isEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }

    var i by remember { mutableIntStateOf(0) }
    var revealed by remember(i) { mutableStateOf(false) }
    val card = list[i]
    LaunchedEffect(i) { viewModel.noteRecognition(card) }
    // L2 -> L1: cue the target side, reveal the native meaning.
    val cue = if (card.frontIsTarget) card.front else card.back
    val meaning = if (card.frontIsTarget) card.back else card.front

    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(progress = { (index + 1f) / total }, modifier = Modifier.fillMaxWidth())
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.routine_recognition_prompt), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(16.dp))
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(cue, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    if (revealed) {
                        Spacer(Modifier.height(16.dp))
                        Text(meaning, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            if (!revealed) {
                Button(onClick = { revealed = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.routine_recognition_reveal))
                }
            } else {
                Button(
                    onClick = { if (i + 1 >= list.size) onComplete() else i++ },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(stringResource(R.string.routine_continue)) }
            }
        }
    }
}
