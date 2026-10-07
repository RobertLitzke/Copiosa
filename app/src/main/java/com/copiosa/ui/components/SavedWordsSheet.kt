package com.copiosa.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity

/**
 * "Words saved from this": what the learner has saved from the text, book or routine in front of
 * them, newest first. [words] is one card per word, as [com.copiosa.data.repository.FlashcardRepository.observeSavedFrom]
 * returns it.
 *
 * A row opens the word's detail screen when [onOpen] is given. A reader that cannot leave without
 * losing its place passes null and gets a plain list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedWordsSheet(
    words: List<FlashcardEntity>,
    onOpen: ((cardId: Long) -> Unit)?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = words.size > 6),
    ) {
        Text(
            stringResource(R.string.saved_words_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
        )
        if (words.isEmpty()) {
            Text(
                stringResource(R.string.saved_words_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
            )
            return@ModalBottomSheet
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            items(words, key = { it.id }) { word ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (onOpen != null) Modifier.clickable { onOpen(word.id) } else Modifier)
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                ) {
                    Text(
                        word.front,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (word.back.isNotBlank()) {
                        Text(
                            word.back,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
