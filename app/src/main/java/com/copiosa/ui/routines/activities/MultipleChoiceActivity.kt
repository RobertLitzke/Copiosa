package com.copiosa.ui.routines.activities

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R

internal data class ChoiceItem(
    val sentence: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String?,
)

/** Generic multiple-choice runner shared by the PI, contrast-cloze, and MC-cloze activities. */
@Composable
internal fun MultipleChoiceActivity(
    prompt: String,
    items: List<ChoiceItem>,
    onAnswer: (correct: Boolean, latencyMs: Long) -> Unit,
    onComplete: () -> Unit,
) {
    if (items.isEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }
    var i by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Int?>(null) }
    var shownAt by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(i) { selected = null; shownAt = System.currentTimeMillis() }

    val item = items[i]
    Column(Modifier.fillMaxSize()) {
        Text(prompt, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(12.dp))
        Text(item.sentence, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(24.dp))
        item.options.forEachIndexed { idx, option ->
            val reveal = selected != null
            val isCorrect = idx == item.answerIndex
            val isWrongPick = reveal && idx == selected && !isCorrect
            val container = when {
                !reveal -> MaterialTheme.colorScheme.surfaceVariant
                isCorrect -> MaterialTheme.colorScheme.primaryContainer
                isWrongPick -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            Button(
                onClick = {
                    selected = idx
                    onAnswer(isCorrect, System.currentTimeMillis() - shownAt)
                },
                enabled = !reveal,
                // Disabled buttons normally drop the custom container colour, which would hide the reveal —
                // pin the disabled colours to the same values so the correct/your-pick marking stays visible.
                colors = ButtonDefaults.buttonColors(
                    containerColor = container,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = container,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                // Once answered, mark the correct option (✓) and a wrong pick (✗) so it's unmistakable.
                if (reveal && (isCorrect || isWrongPick)) {
                    Icon(
                        if (isCorrect) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(option)
            }
        }
        if (selected != null) {
            Spacer(Modifier.height(12.dp))
            item.explanation?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Button(
                onClick = { if (i + 1 >= items.size) onComplete() else i++ },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.routine_continue)) }
        }
    }
}
