package com.copiosa.ui.routines.activities

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.routine.AffectivePiContent

/** Affective PI: pick the statement that's true for you — engagement without a right answer. */
@Composable
internal fun AffectivePiActivity(content: AffectivePiContent, onPick: () -> Unit, onComplete: () -> Unit) {
    var picked by remember { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize()) {
        Text(
            content.prompt.ifBlank { stringResource(R.string.routine_affective_prompt) },
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(16.dp))
        content.statements.forEachIndexed { idx, statement ->
            val selected = picked == idx
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .selectable(selected = selected) {
                        if (picked == null) onPick()
                        picked = idx
                    },
            ) {
                Text(statement, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.weight(1f))
        Button(onClick = onComplete, enabled = picked != null, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.routine_continue))
        }
    }
}
