package com.copiosa.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.ui.theme.SerifTitle

/**
 * The shared *monolingual* selectable reading view: target-language [blocks] rendered with
 * saved-word highlighting, tap/drag to translate + define a selection, and a floating
 * [TranslationCard] to save it to vocabulary. The caller supplies the selection callbacks (backed by
 * a [ReadingSelectionController]) and may append a [footer] inside the same scroll list.
 *
 * This is for surfaces that only ever show one language — a routine's reading step. The texts and
 * books readers show a paragraph in either language or both, and are built on `BilingualBlockRow`
 * instead; they drive the same [ReadingSelectionController] underneath.
 */
@Composable
fun SelectableReader(
    blocks: List<String>,
    reading: ReadingSelectionController,
    modifier: Modifier = Modifier,
    title: String? = null,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    onOpenSaved: ((Long) -> Unit)? = null,
    onSendSelectionToAudio: ((String) -> Unit)? = null,
    footer: (LazyListScope.() -> Unit)? = null,
) {
    val highlightTargets by reading.highlightTargets.collectAsStateWithLifecycle()
    val translate by reading.translate.collectAsStateWithLifecycle()
    val savedColor = MaterialTheme.colorScheme.primary
    val colorizer = remember(highlightTargets, savedColor) { WordColorizer(highlightTargets, savedColor) }
    // One selection across the whole passage, so a drag can run from one paragraph into the next.
    val selection = rememberReadingSelectionSpan()

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!title.isNullOrBlank()) item(key = "title") { Text(title, style = SerifTitle) }
        itemsIndexed(blocks, key = { i, _ -> "b$i" }) { i, block ->
            SelectableMessage(
                content = block,
                colorizer = colorizer,
                isActive = translate != null,
                onSelected = { text, y -> reading.onSelectionTranslate(text, y, block) },
                onCleared = reading::dismissTranslate,
                selection = selection,
                block = i,
            )
        }
        footer?.invoke(this)
    }

    translate?.let { t ->
        ReaderTranslationCard(
            state = t,
            selection = reading,
            onOpenVocab = { id -> onOpenSaved?.invoke(id) },
            onSendToAudio = onSendSelectionToAudio?.let { send -> { send(t.original) } },
        )
    }
}
