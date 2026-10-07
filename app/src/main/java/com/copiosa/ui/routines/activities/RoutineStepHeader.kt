package com.copiosa.ui.routines.activities

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.ui.components.ProgressBar

/**
 * The chrome every routine step sits under: a way out, how far along the routine is, and a way past
 * a step that isn't wanted tonight. Leaving keeps the run — it is offered again from the Routines tab
 * — so neither control asks for confirmation.
 *
 * [showExit] is false for the one step that brings its own way out (the embedded review screen has a
 * back arrow in its header), where a second one a row above would only be a duplicate.
 */
@Composable
internal fun RoutineStepHeader(
    index: Int,
    total: Int,
    onExit: () -> Unit,
    onSkip: () -> Unit,
    showExit: Boolean = true,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(start = if (showExit) 4.dp else 20.dp, end = 4.dp),
    ) {
        if (showExit) {
            IconButton(onClick = onExit) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.routine_exit))
            }
        }
        ProgressBar(progress = { (index + 1f) / total }, modifier = Modifier.weight(1f))
        TextButton(onClick = onSkip) { Text(stringResource(R.string.routine_skip)) }
    }
}
