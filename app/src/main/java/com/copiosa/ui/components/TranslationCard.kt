package com.copiosa.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.translate.DictionaryEntry
import com.copiosa.ui.flashcards.PosChip
import com.copiosa.ui.theme.SerifTitle

/** State of the offline-dictionary section of the translation card. */
sealed interface DefinitionView {
    /** Not shown (multi-word selection, or no dictionary configured). */
    data object Hidden : DefinitionView
    /** Single word, but the language pack hasn't been downloaded yet — offer to fetch it. */
    data object NeedsDownload : DefinitionView
    data object Downloading : DefinitionView
    data object Loading : DefinitionView
    data class Ready(val entries: List<DictionaryEntry>) : DefinitionView
    data object NotFound : DefinitionView
}

/**
 * Transient, dictionary-style card for a selected word/phrase: the term, its on-device translation,
 * an optional offline definition (part of speech + native-language meaning), and actions to save it
 * as a flashcard, ask the model to explain it inline, or copy it. When the word is already in the
 * user's vocabulary ([savedCard] non-null) it shows the full saved entry instead — meaning, POS/CEFR
 * chips, example, and any explanation — with an action to open its detail screen.
 *
 * It floats above the chat and dismisses on a tap outside its bounds (or back press). It anchors to
 * whichever half of the screen is *opposite* the selection (via [anchorYPx]) so it never covers the
 * highlighted word.
 */
@Composable
fun TranslationCard(
    original: String,
    translation: String?,
    loading: Boolean,
    definition: DefinitionView,
    canExplain: Boolean,
    anchorYPx: Float,
    savedCard: FlashcardEntity?,
    lemmaSuggestion: String?,
    onSave: (headword: String) -> Unit,
    onExplain: () -> Unit,
    onDownloadDictionary: () -> Unit,
    onOpenSaved: () -> Unit,
    onDismiss: () -> Unit,
    onSendToAudio: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val positionProvider = remember(anchorYPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = ((windowSize.width - popupContentSize.width) / 2).coerceAtLeast(0)
                // Selection in the lower half → card at the top, and vice versa, so it never overlaps.
                val placeTop = anchorYPx > windowSize.height / 2f
                val y = if (placeTop) 0 else (windowSize.height - popupContentSize.height).coerceAtLeast(0)
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        onDismissRequest = onDismiss,
        // focusable: a tap outside the card (and the system back button) dismisses it. The text
        // highlight is owned by SelectableMessage, so losing focus here doesn't disturb it.
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                // Padded on both ends so it clears the system bars whether it lands top or bottom.
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(original, style = SerifTitle)

                // The body scrolls, capped so the actions stay visible.
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (savedCard != null) {
                        SavedEntry(savedCard)
                    } else {
                        when {
                            loading -> LoadingRow(stringResource(R.string.translating))
                            translation != null -> Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) { MarkdownText(translation) }
                            }
                            else -> Text(
                                stringResource(R.string.translate_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    DefinitionSection(definition, onDownloadDictionary)

                    // Conjugated verb selected → offer to save the surface form instead of the lemma.
                    if (savedCard == null && lemmaSuggestion != null) {
                        TextButton(
                            onClick = { onSave(original) },
                            contentPadding = PaddingValues(0.dp),
                        ) {
                            Text(stringResource(R.string.action_save_form_instead, original))
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (savedCard != null) {
                        Button(onClick = onOpenSaved) {
                            Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(R.string.action_view_details), modifier = Modifier.padding(start = 6.dp))
                        }
                    } else {
                        // Default to the lemma (e.g. infinitive) when the selection is an inflection.
                        val saveTerm = lemmaSuggestion ?: original
                        Button(onClick = { onSave(saveTerm) }, enabled = translation != null) {
                            Icon(Icons.Filled.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(
                                if (lemmaSuggestion != null) {
                                    stringResource(R.string.action_save_as, saveTerm)
                                } else {
                                    stringResource(R.string.action_save_word)
                                },
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }
                    OutlinedButton(onClick = onExplain, enabled = canExplain) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.action_explain), modifier = Modifier.padding(start = 6.dp))
                    }
                    val copyText = translation ?: savedCard?.back
                    if (copyText != null) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(copyText)) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
                        }
                    }
                    if (onSendToAudio != null) {
                        IconButton(onClick = onSendToAudio) {
                            Icon(Icons.Filled.Headphones, contentDescription = stringResource(R.string.audio_send_to))
                        }
                    }
                }
            }
        }
    }
}

/** The full saved-vocabulary entry: chips, meaning, explanation, and example. */
@Composable
private fun SavedEntry(card: FlashcardEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.vocab_saved_label))

        if (!card.partOfSpeech.isNullOrBlank() || !card.cefrLevel.isNullOrBlank()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                card.partOfSpeech?.takeIf { it.isNotBlank() }?.let { PosChip(it) }
                card.cefrLevel?.takeIf { it.isNotBlank() }?.let { CefrBadge(it) }
            }
        }

        if (card.back.isNotBlank()) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp)) { MarkdownText(card.back) }
            }
        }

        card.explanation?.takeIf { it.isNotBlank() }?.let { MarkdownText(it) }

        card.contextSentence?.takeIf { it.isNotBlank() }?.let { sentence ->
            SectionLabel(stringResource(R.string.vocab_example_label))
            Text(
                sentence,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DefinitionSection(definition: DefinitionView, onDownloadDictionary: () -> Unit) {
    when (definition) {
        DefinitionView.Hidden, DefinitionView.NotFound -> Unit
        DefinitionView.NeedsDownload -> OutlinedButton(onClick = onDownloadDictionary) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.dict_download), modifier = Modifier.padding(start = 6.dp))
        }
        DefinitionView.Downloading -> LoadingRow(stringResource(R.string.dict_downloading))
        DefinitionView.Loading -> LoadingRow(stringResource(R.string.dict_loading))
        is DefinitionView.Ready -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HorizontalDivider()
            definition.entries.forEach { EntryView(it) }
        }
    }
}

@Composable
private fun EntryView(entry: DictionaryEntry) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.lemma, style = MaterialTheme.typography.titleSmall)
            entry.pos?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium.copy(fontStyle = FontStyle.Italic),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        entry.inflectionOf?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entry.definitions.take(5).forEach { def ->
            Text("•  $def", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
