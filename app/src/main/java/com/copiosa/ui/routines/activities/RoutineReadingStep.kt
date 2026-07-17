package com.copiosa.ui.routines.activities

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.ui.components.SelectableReader
import com.copiosa.ui.routines.RoutinePlayerViewModel

/**
 * A reading step (narrow reading and the bedtime-story closer alike): the shared [SelectableReader] over
 * the generated [blocks] — saved words are tinted, tap/drag translates + defines a selection, and Save
 * adds a real vocab card (and reading a saved word passively reviews it), exactly like opening a Text.
 * The routine frames it with its progress bar on top and a [buttonLabel]ed button that advances.
 */
@Composable
internal fun RoutineReadingStep(
    title: String,
    blocks: List<String>,
    index: Int,
    total: Int,
    viewModel: RoutinePlayerViewModel,
    @StringRes buttonLabel: Int,
    onComplete: () -> Unit,
) {
    val translate by viewModel.translate.collectAsState()
    val highlightTargets by viewModel.highlightTargets.collectAsState()
    // Feed the reader's body to the VM so saved words appearing in it get a passive review.
    LaunchedEffect(title, blocks) {
        viewModel.setReadingText((listOf(title) + blocks).joinToString("\n\n"))
    }

    Column(Modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { (index + 1f) / total },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            SelectableReader(
                blocks = blocks,
                highlightTargets = highlightTargets,
                translate = translate,
                onSelectionTranslate = viewModel::onSelectionTranslate,
                onDismissSelection = viewModel::dismissTranslate,
                onSave = viewModel::saveSelection,
                onDownloadDictionary = viewModel::downloadDictionary,
                title = title.ifBlank { null },
            )
        }
        Button(
            onClick = onComplete,
            modifier = Modifier.fillMaxWidth().padding(20.dp),
        ) { Text(stringResource(buttonLabel)) }
    }
}
