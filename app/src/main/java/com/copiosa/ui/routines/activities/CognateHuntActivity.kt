package com.copiosa.ui.routines.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.routine.CognateHuntContent

/**
 * Cognate hunt: a small set of target words — mostly true cognates the learner will recognize, plus a
 * couple of false friends. Tapping a word reveals whether it means what it looks like, or (for a false
 * friend) what it actually means. Lexical noticing, and a little rewarding.
 */
@Composable
internal fun CognateHuntActivity(content: CognateHuntContent, onComplete: () -> Unit) {
    val words = content.words
    if (words.isEmpty()) {
        LaunchedEffect(Unit) { onComplete() }
        return
    }
    val revealed = remember { mutableStateListOf<Int>() }
    Column(Modifier.fillMaxSize()) {
        Text(stringResource(R.string.routine_cognate_prompt), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            words.forEachIndexed { idx, word ->
                val isRevealed = idx in revealed
                val container = when {
                    !isRevealed -> MaterialTheme.colorScheme.surfaceVariant
                    word.falseFriend -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.primaryContainer
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = container),
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = isRevealed) { if (!isRevealed) revealed.add(idx) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(word.form, style = MaterialTheme.typography.titleMedium)
                        if (isRevealed) {
                            Text(
                                if (word.falseFriend) {
                                    stringResource(R.string.routine_cognate_false_friend, word.gloss.orEmpty())
                                } else {
                                    stringResource(R.string.routine_cognate_true)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onComplete, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.routine_continue))
        }
    }
}
