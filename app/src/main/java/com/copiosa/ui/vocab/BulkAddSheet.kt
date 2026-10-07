package com.copiosa.ui.vocab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.domain.prompt.BulkCards
import com.copiosa.ui.components.llmErrorText
import com.copiosa.ui.theme.SourceSerif
import java.util.Locale

/**
 * Bulk add: type a list of words in your own language, get a drafted card for each, and confirm the
 * ones worth keeping. Nothing is saved until the confirm button — every draft can be unticked or
 * corrected first, and a word the deck already teaches arrives unticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkAddSheet(
    onDismiss: () -> Unit,
    viewModel: BulkAddViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val languages by viewModel.languages.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.bulk_add_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            when (state.step) {
                BulkAddStep.INPUT, BulkAddStep.DRAFTING -> InputStep(
                    state = state,
                    nativeName = languageName(languages.native),
                    targetName = languageName(languages.target),
                    onInputChange = viewModel::setInput,
                    onDraft = viewModel::draft,
                )
                BulkAddStep.REVIEW, BulkAddStep.SAVING -> ReviewStep(
                    state = state,
                    targetLanguage = languages.target,
                    onInclude = viewModel::setIncluded,
                    onEdit = viewModel::edit,
                    onBack = viewModel::backToInput,
                    onConfirm = viewModel::confirm,
                )
            }
        }
    }
}

@Composable
private fun InputStep(
    state: BulkAddState,
    nativeName: String,
    targetName: String,
    onInputChange: (String) -> Unit,
    onDraft: () -> Unit,
) {
    val drafting = state.step == BulkAddStep.DRAFTING
    val count = remember(state.input) { BulkCards.splitInput(state.input).size }

    Text(
        stringResource(R.string.bulk_add_helper, nativeName, targetName),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedTextField(
        value = state.input,
        onValueChange = onInputChange,
        enabled = !drafting,
        modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp),
        placeholder = { Text(stringResource(R.string.bulk_add_placeholder)) },
        shape = RoundedCornerShape(16.dp),
    )
    if (state.failed) {
        Text(
            state.llmError?.let { llmErrorText(it) } ?: stringResource(R.string.bulk_add_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Button(
        onClick = onDraft,
        enabled = count > 0 && !drafting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (drafting) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.width(10.dp))
            Text(stringResource(R.string.bulk_add_progress, state.done, state.total))
        } else {
            Text(
                if (count > 0) pluralStringResource(R.plurals.bulk_add_translate, count, count)
                else stringResource(R.string.bulk_add_translate_none),
            )
        }
    }
}

@Composable
private fun ColumnScope.ReviewStep(
    state: BulkAddState,
    targetLanguage: String,
    onInclude: (Int, Boolean) -> Unit,
    onEdit: (Int, String, String) -> Unit,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
) {
    val saving = state.step == BulkAddStep.SAVING

    Text(
        stringResource(R.string.bulk_add_review_helper),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // What the run couldn't do as asked, said once above the list rather than per row.
    if (state.onDevice > 0) {
        ReviewNotice(
            buildString {
                append(pluralStringResource(R.plurals.bulk_add_on_device, state.onDevice, state.onDevice))
                state.llmError?.let { append(' ').append(llmErrorText(it)) }
            },
        )
    }
    if (state.untranslated.isNotEmpty()) {
        ReviewNotice(stringResource(R.string.bulk_add_untranslated, state.untranslated.joinToString(", ")))
    }

    // weight(fill = false): the list takes what the drafts need and no more, and the buttons under
    // it stay on screen however long it gets.
    LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
        itemsIndexed(state.rows) { index, row ->
            DraftRowItem(
                row = row,
                targetLanguage = targetLanguage,
                enabled = !saving,
                onInclude = { onInclude(index, it) },
                onEdit = { term, translation -> onEdit(index, term, translation) },
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack, enabled = !saving) { Text(stringResource(R.string.action_back)) }
        val included = state.includedCount
        Button(
            onClick = onConfirm,
            enabled = included > 0 && !saving,
            modifier = Modifier.weight(1f),
        ) {
            Text(
                if (included > 0) pluralStringResource(R.plurals.bulk_add_confirm, included, included)
                else stringResource(R.string.bulk_add_confirm_none),
            )
        }
    }
}

@Composable
private fun ReviewNotice(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * One draft: a tick, the card as it will be saved, and a pencil that swaps the two lines for text
 * fields. Editing is per row and opt-in so a list of twenty reads as a list, not as forty inputs.
 */
@Composable
private fun DraftRowItem(
    row: DraftRow,
    targetLanguage: String,
    enabled: Boolean,
    onInclude: (Boolean) -> Unit,
    onEdit: (term: String, translation: String) -> Unit,
) {
    val draft = row.draft
    var editing by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.included, onCheckedChange = onInclude, enabled = enabled)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (editing) {
                OutlinedTextField(
                    value = draft.term,
                    onValueChange = { onEdit(it, draft.translation) },
                    label = { Text(stringResource(R.string.edit_card_front)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.translation,
                    onValueChange = { onEdit(draft.term, it) },
                    label = { Text(stringResource(R.string.edit_card_back)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    GenderChip(draft.gender, targetLanguage)
                    Text(
                        draft.term,
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = SourceSerif),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    draft.pos?.let { PosChip(it) }
                }
                Text(
                    draft.translation,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SourceSerif),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                draft.example?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (draft.duplicate) {
                Text(
                    stringResource(R.string.bulk_add_duplicate),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        IconButton(onClick = { editing = !editing }, enabled = enabled) {
            Icon(
                if (editing) Icons.Filled.Check else Icons.Filled.Edit,
                contentDescription = stringResource(if (editing) R.string.action_done else R.string.action_edit),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A language tag as its name in the UI's own language ("English", "Spanish"). */
private fun languageName(tag: String): String =
    Locale.forLanguageTag(tag).displayLanguage.replaceFirstChar { it.uppercase() }.ifBlank { tag }
