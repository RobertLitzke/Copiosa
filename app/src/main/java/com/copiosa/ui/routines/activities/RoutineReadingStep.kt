package com.copiosa.ui.routines.activities

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.ui.components.SavedWordsSheet
import com.copiosa.ui.components.SelectableReader
import com.copiosa.ui.routines.RoutinePlayerViewModel

/**
 * A reading step (narrow reading and the bedtime-story closer alike): the shared [SelectableReader] over
 * the generated [blocks] — saved words are tinted, tap/drag translates + defines a selection, and Save
 * adds a real vocab card (and reading a saved word passively reviews it), exactly like opening a Text.
 * The routine frames it with its [header] on top and a [buttonLabel]ed button that advances.
 */
@Composable
internal fun RoutineReadingStep(
    title: String,
    blocks: List<String>,
    viewModel: RoutinePlayerViewModel,
    @StringRes buttonLabel: Int,
    onComplete: () -> Unit,
    header: @Composable () -> Unit,
) {
    val savedWords by viewModel.savedWords.collectAsStateWithLifecycle()
    var showSavedWords by remember { mutableStateOf(false) }
    // Feed the reader's body to the VM so saved words appearing in it get a passive review.
    LaunchedEffect(title, blocks) {
        viewModel.setReadingText((listOf(title) + blocks).joinToString("\n\n"))
    }

    Column(Modifier.fillMaxSize()) {
        header()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectableReader(
                blocks = blocks,
                reading = viewModel.readingSelection,
                title = title.ifBlank { null },
            )
        }
        // Only once there is something to list, so the step stays as bare as it was until then.
        if (savedWords.isNotEmpty()) {
            TextButton(
                onClick = { showSavedWords = true },
                modifier = Modifier.align(Alignment.CenterHorizontally),
            ) { Text(stringResource(R.string.saved_words_menu, savedWords.size)) }
        }
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 20.dp, top = 8.dp),
        ) { Text(stringResource(buttonLabel)) }
    }

    // No row opens the word: leaving for its detail screen would abandon the step, and a list is
    // enough to see what the run has caught.
    if (showSavedWords) {
        SavedWordsSheet(words = savedWords, onOpen = null, onDismiss = { showSavedWords = false })
    }
}
