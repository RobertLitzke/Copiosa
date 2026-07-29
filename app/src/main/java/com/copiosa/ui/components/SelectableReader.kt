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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.copiosa.domain.highlight.HighlightTarget
import com.copiosa.ui.theme.SerifTitle

/**
 * The shared selectable reading view: target-language [blocks] rendered with saved-word highlighting,
 * tap/drag to translate + define a selection, and a floating [TranslationCard] to save it to
 * vocabulary. Used by the Texts reader and a routine's reading step alike — the caller supplies the
 * selection callbacks (backed by a [ReadingSelectionController]) and may append a [footer] (e.g. the
 * Texts reader's translation-progress / partial-error row, which lives inside the same scroll list).
 */
@Composable
fun SelectableReader(
    blocks: List<String>,
    highlightTargets: List<HighlightTarget>,
    translate: TranslateState?,
    onSelectionTranslate: (text: String, anchorY: Float, sourceText: String) -> Unit,
    onDismissSelection: () -> Unit,
    onSave: (headword: String) -> Unit,
    onSelectSaveEntry: (lemma: String, pos: String?) -> Unit,
    onDownloadDictionary: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    listState: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    onOpenSaved: ((Long) -> Unit)? = null,
    onSendSelectionToAudio: ((String) -> Unit)? = null,
    footer: (LazyListScope.() -> Unit)? = null,
) {
    val savedColor = MaterialTheme.colorScheme.primary
    val colorizer = remember(highlightTargets, savedColor) { WordColorizer(highlightTargets, savedColor) }
    // Which block currently owns the highlight, so only the tapped paragraph shows the selection span.
    var selectedBlock by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(translate) { if (translate == null) selectedBlock = null }

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
                isActive = translate != null && selectedBlock == i,
                onSelected = { text, y -> selectedBlock = i; onSelectionTranslate(text, y, block) },
                onCleared = onDismissSelection,
            )
        }
        footer?.invoke(this)
    }

    translate?.let { t ->
        TranslationCard(
            original = t.original,
            translation = t.translation,
            translationOf = t.translationOf,
            loading = t.loading,
            definition = t.definition,
            canExplain = false,
            anchorYPx = t.anchorY,
            savedCard = t.savedCard,
            lemmaSuggestion = t.saveLemma,
            savePos = t.savePos,
            onSave = onSave,
            onSelectEntry = onSelectSaveEntry,
            onExplain = {},
            onDownloadDictionary = onDownloadDictionary,
            onOpenSaved = { t.savedCard?.let { c -> onOpenSaved?.invoke(c.id) } },
            onDismiss = onDismissSelection,
            onSendToAudio = onSendSelectionToAudio?.let { send -> { send(t.original) } },
        )
    }
}
