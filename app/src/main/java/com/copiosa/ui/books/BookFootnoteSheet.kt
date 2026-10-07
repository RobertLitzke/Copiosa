package com.copiosa.ui.books

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.epub.noteBodyOf
import com.copiosa.data.epub.noteLabelOf
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.book.nativeText
import com.copiosa.domain.book.targetText
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.reading.ReadMode
import com.copiosa.ui.components.MarkdownText
import com.copiosa.ui.components.SubordinateGloss

/**
 * The note behind a tapped `[^3]` marker, without leaving the page.
 *
 * The body also sits at the end of its chapter as an ordinary row — that is what gets it translated
 * and exported — but a reader who hits a marker mid-chapter wants the note now, not eighty screens
 * later. Shown in whichever languages the reader's current [mode] is showing, so opening a note
 * doesn't silently switch languages on them.
 *
 * Both the label and the body are read through [noteLabelOf]/[noteBodyOf] rather than sliced off the
 * text directly: the `[^3]: ` prefix survives translation only by the model's goodwill.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BookFootnoteSheet(
    note: BookParagraphEntity,
    direction: BookDirection,
    mode: ReadMode,
    onDismiss: () -> Unit,
) {
    val label = noteLabelOf(note.originalText)
    val target = note.targetText(direction)?.takeIf { it.isNotBlank() }?.let(::noteBodyOf)
    val native = note.nativeText(direction)?.takeIf { it.isNotBlank() }?.let(::noteBodyOf)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                label?.let { stringResource(R.string.book_footnote_label, it) }
                    ?: stringResource(R.string.book_footnote),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val primary = if (mode == ReadMode.NATIVE) native ?: target else target ?: native
            SelectionContainer { MarkdownText(primary.orEmpty()) }
            if (mode == ReadMode.BOTH && native != null && native != primary) SubordinateGloss(native)
        }
    }
}
