package com.copiosa.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.generation.BookDownloadChoice
import com.copiosa.data.generation.BookDownloadState
import com.copiosa.data.settings.AppSettings
import com.copiosa.domain.model.ModelOption
import com.copiosa.domain.usage.UsageFormat
import com.copiosa.ui.components.LabeledDropdown
import com.copiosa.ui.components.usageCostLabel
import com.copiosa.ui.components.usageDateLabel

/**
 * Per-book download settings: how eagerly this book translates, and which model does it.
 *
 * The two belong in one dialog because they are one decision — "spend this much, with this model" —
 * and because changing the model is what raises the only question the learner cannot answer from
 * the reader: what to do about the sections an earlier model already produced. That offer only
 * appears when there is something to redo, so the common case stays a four-option list.
 */
@Composable
fun BookDownloadModeDialog(
    state: BookDownloadState,
    onConfirm: (BookDownloadChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember(state.bookId) { mutableStateOf(state.mode) }
    var model by remember(state.bookId) { mutableStateOf(state.selected) }
    var retranslate by remember(state.bookId) { mutableStateOf(false) }

    val stale = state.staleSections(model.model)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.book_download_title)) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                // Four modes plus a model row overflow a short screen in a dialog's fixed height.
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                BookDownloadModePicker(
                    selected = mode,
                    onSelect = { mode = it },
                    sectionsRemaining = state.sectionsRemaining,
                )
                LabeledDropdown(
                    label = stringResource(R.string.book_download_model),
                    options = state.options,
                    selected = model,
                    optionLabel = { modelLabel(it, state.modelNames) },
                    onSelect = { model = it },
                )
                state.spent?.let { spent ->
                    Text(
                        stringResource(
                            R.string.book_download_spent,
                            usageDateLabel(spent.since),
                            usageCostLabel(spent.totals),
                            UsageFormat.tokens(spent.totals.tokens),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stale > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Checkbox(checked = retranslate, onCheckedChange = { retranslate = it })
                        Text(
                            pluralStringResource(R.plurals.book_download_retranslate, stale, stale),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        BookDownloadChoice(
                            mode = mode,
                            model = model,
                            // The box can be left checked after the model is switched back; only
                            // the model actually being applied decides whether it means anything.
                            retranslate = retranslate && stale > 0,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

/**
 * "Gemini 3.5 Flash" / "openai/gpt-4o-mini · OpenRouter" — the provider matters when both have keys.
 * An OpenRouter model the learner has nicknamed shows under that name instead of its id.
 */
private fun modelLabel(option: ModelOption, names: Map<String, String>): String =
    "${names[option.model] ?: AppSettings.displayName(option.model)} · ${option.provider.displayLabel()}"
