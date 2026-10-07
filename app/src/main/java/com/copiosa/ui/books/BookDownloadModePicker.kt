package com.copiosa.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.model.BookDownloadMode

/**
 * The four download modes as a radio group, cheapest first, each with the one line that says what it
 * will actually cost. Shared by the per-book dialog and the import dialog so a book is described the
 * same way whether the choice is being made before or after it exists.
 *
 * [sectionsRemaining] prices the rapid option in the only unit that matters — one section is one LLM
 * call. Null at import time, when nothing has been parsed yet and there is no count to give.
 */
@Composable
fun BookDownloadModePicker(
    selected: BookDownloadMode,
    onSelect: (BookDownloadMode) -> Unit,
    modifier: Modifier = Modifier,
    sectionsRemaining: Int? = null,
) {
    Column(modifier = modifier.selectableGroup()) {
        BookDownloadMode.entries.forEach { mode ->
            ModeRow(
                mode = mode,
                selected = mode == selected,
                note = noteFor(mode, sectionsRemaining),
                onSelect = { onSelect(mode) },
            )
        }
    }
}

@Composable
private fun ModeRow(
    mode: BookDownloadMode,
    selected: Boolean,
    note: String,
    onSelect: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            // The whole row, not just the button: a 20.dp target beside three lines of text is the
            // classic radio-list miss.
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(modifier = Modifier.padding(top = 10.dp)) {
            Text(stringResource(titleOf(mode)), style = MaterialTheme.typography.bodyLarge)
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun titleOf(mode: BookDownloadMode): Int = when (mode) {
    BookDownloadMode.OFF -> R.string.book_download_off
    BookDownloadMode.WHILE_READING -> R.string.book_download_while_reading
    BookDownloadMode.BACKGROUND -> R.string.book_download_background
    BookDownloadMode.RAPID -> R.string.book_download_rapid
}

@Composable
private fun noteFor(mode: BookDownloadMode, sectionsRemaining: Int?): String = when (mode) {
    BookDownloadMode.OFF -> stringResource(R.string.book_download_off_note)
    BookDownloadMode.WHILE_READING -> stringResource(R.string.book_download_while_reading_note)
    BookDownloadMode.BACKGROUND -> stringResource(R.string.book_download_background_note)
    BookDownloadMode.RAPID -> when {
        sectionsRemaining == null -> stringResource(R.string.book_download_rapid_note)
        sectionsRemaining == 0 -> stringResource(R.string.book_download_rapid_done)
        else -> pluralStringResource(
            R.plurals.book_download_rapid_cost,
            sectionsRemaining,
            sectionsRemaining,
        )
    }
}
