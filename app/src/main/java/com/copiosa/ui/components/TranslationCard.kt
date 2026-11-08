package com.copiosa.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.copiosa.R
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.translate.DictionaryDownload
import com.copiosa.data.translate.DictionaryEntry
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.ui.audio.PronounceButton
import com.copiosa.ui.vocab.PosChip
import com.copiosa.ui.theme.SerifTitle

/** State of the offline-dictionary section of the translation card. */
sealed interface DefinitionView {
    /** Not shown (multi-word selection, or no dictionary configured). */
    data object Hidden : DefinitionView
    /** Single word, but the language pack hasn't been downloaded yet — offer to fetch it. */
    /** [approxMb] is the pack's download size when known, so the button can say what it will cost. */
    data class NeedsDownload(val approxMb: Int? = null) : DefinitionView

    /**
     * The fetch failed. [reason] is what to tell the reader — a pack that isn't published and a
     * dropped connection want different responses, and a button that silently redraws itself tells
     * them apart for neither.
     */
    data class DownloadFailed(
        val reason: DictionaryDownload,
        val approxMb: Int? = null,
    ) : DefinitionView

    data object Downloading : DefinitionView
    data object Loading : DefinitionView
    data class Ready(val entries: List<DictionaryEntry>) : DefinitionView
    data object NotFound : DefinitionView
}

/**
 * Transient, dictionary-style card for a selected word/phrase: the term (with a button to hear it),
 * its on-device translation, an optional offline definition (part of speech + native-language
 * meaning), and actions to save it as a flashcard, ask the model to explain it, or copy it. When
 * the word is already in the user's vocabulary ([savedCard] non-null) it shows the full saved entry instead — meaning, POS/CEFR
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
    /** Non-null when [translation] is the meaning of this base form rather than of the selection. */
    translationOf: String?,
    loading: Boolean,
    definition: DefinitionView,
    canExplain: Boolean,
    anchorYPx: Float,
    savedCard: FlashcardEntity?,
    lemmaSuggestion: String?,
    /** Part of speech of the currently picked entry, to mark it among same-lemma alternatives. */
    savePos: String?,
    /** True once a save has been asked for on an ambiguous word — see [DefinitionSection]. */
    picking: Boolean,
    onSave: (headword: String) -> Unit,
    /** Picks which dictionary entry a save uses; the lemma may be [original] to save it as written. */
    onSelectEntry: (lemma: String, pos: String?) -> Unit,
    onExplain: () -> Unit,
    onDownloadDictionary: () -> Unit,
    onOpenSaved: () -> Unit,
    onDismiss: () -> Unit,
    /** A saved card spelled like the selection but not the word meant; see [TranslateState.relatedCard]. */
    relatedCard: FlashcardEntity? = null,
    onOpenRelated: () -> Unit = {},
    onSendToAudio: (() -> Unit)? = null,
    /** The answer to an inline "Explain", shown in the card; a chat answers in the conversation instead. */
    explanation: String? = null,
    explaining: Boolean = false,
    explainError: LlmError? = null,
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
                // A selection can be several paragraphs long (it is also how text is copied out of a
                // reader); the passage itself is highlighted in the text behind the card, so a few
                // lines here are enough to confirm what was picked without burying the actions.
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        original,
                        style = SerifTitle,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // Says the selection now; the headphones below queue it for the Audio tab.
                    PronounceButton(text = original)
                }

                // Whether the dictionary entries below carry the English, in which case the machine
                // translation above them would only repeat it. The dictionary's meanings belong to this
                // headword and were written by people; the on-device translator is guessing at the same
                // thing from the bare word, and stays for what the pack can't answer — phrases, unknown
                // words, a pack not downloaded. [dictionaryOwnsEnglish] also covers the lookup still
                // being in flight: it is a local query that lands in a moment, and a line that appears
                // only to be replaced reads as a glitch. Not while the *pack* is downloading, which is
                // minutes rather than milliseconds — and not for [savable], which must not enable a
                // button on a meaning that may never arrive.
                val hasEnglishEntry = (definition as? DefinitionView.Ready)
                    ?.entries.orEmpty().any { it.englishDefinitions.isNotEmpty() } == true
                val dictionaryOwnsEnglish = definition is DefinitionView.Loading || hasEnglishEntry

                // The body scrolls, capped so the actions stay visible.
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (savedCard != null) {
                        SavedEntry(savedCard, original)
                    } else {
                        when {
                            loading -> LoadingRow(stringResource(R.string.translating))
                            dictionaryOwnsEnglish -> Unit
                            translation != null -> Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = MaterialTheme.shapes.medium,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    MarkdownText(translation)
                                    // Say so when the meaning came from the base form, not the selection.
                                    translationOf?.let {
                                        Text(
                                            stringResource(R.string.translate_of_lemma, it),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                            else -> Text(
                                stringResource(R.string.translate_unavailable),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    InlineExplanation(explanation, explainError)

                    // The card this selection's spelling matched, kept in reach: the reader may have
                    // meant it after all, and can open it or pick its entry below.
                    if (savedCard == null && relatedCard != null) {
                        TextButton(onClick = onOpenRelated, contentPadding = PaddingValues(0.dp)) {
                            Icon(Icons.Filled.Bookmark, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text(
                                stringResource(R.string.vocab_related_saved, relatedCard.targetTerm),
                                style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 6.dp),
                            )
                        }
                    }

                    DefinitionSection(
                        definition = definition,
                        original = original,
                        // A saved word shows its stored entry; there is nothing left to pick.
                        picking = picking && savedCard == null,
                        selectedLemma = lemmaSuggestion ?: original,
                        selectedPos = savePos,
                        onSelectEntry = onSelectEntry,
                        onDownloadDictionary = onDownloadDictionary,
                    )
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
                        // A selection spanning paragraphs is a passage being read or copied, never a
                        // vocabulary item — saving it would put a wall of text on a flashcard.
                        // Either source of meaning is enough for a back: the on-device translator
                        // is unavailable on some devices and language pairs, and a headword the
                        // dictionary can define is still worth saving there.
                        val savable = (translation != null || hasEnglishEntry) &&
                            !original.contains("\n\n")
                        Button(onClick = { onSave(saveTerm) }, enabled = savable) {
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
                    // Answered once per selection: the answer stays in the card, so asking again
                    // would only pay for the same thing. A failure leaves the button live to retry.
                    OutlinedButton(onClick = onExplain, enabled = canExplain && !explaining && explanation == null) {
                        if (explaining) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Text(stringResource(R.string.action_explain), modifier = Modifier.padding(start = 6.dp))
                    }
                    // Copies the *selection*, not its meaning: the card is the only way to lift text
                    // out of a reader, and what the reader picked out is what they meant to take —
                    // the meaning is already on screen to be read.
                    IconButton(onClick = { clipboard.setText(AnnotatedString(original)) }) {
                        Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.action_copy))
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

/**
 * What "Explain" came back with: the model's account of the selection in the sentence it was read
 * in, or why there isn't one. It arrives a moment after the tap, into a body that may already be
 * taller than its scroll window (a saved word with a note and an example), so it scrolls itself
 * into view rather than landing where the reader has to go looking for it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InlineExplanation(explanation: String?, error: LlmError?) {
    if (explanation == null && error == null) return
    val bringIntoView = remember { BringIntoViewRequester() }
    LaunchedEffect(explanation, error) { bringIntoView.bringIntoView() }
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().bringIntoViewRequester(bringIntoView),
    ) {
        if (explanation != null) {
            // The same mark as the button that asked, so the answer reads as its result.
            Icon(
                Icons.Filled.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 2.dp).size(16.dp),
            )
            MarkdownText(explanation, modifier = Modifier.weight(1f))
        } else if (error != null) {
            Text(
                llmErrorText(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * The full saved-vocabulary entry: chips, meaning, explanation, and example. Names the saved word
 * when it is not [original] itself — a form of it, or the base form a save was about to use.
 */
@Composable
private fun SavedEntry(card: FlashcardEntity, original: String) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val term = card.targetTerm
        SectionLabel(
            if (WordColorizer.normalize(term) == WordColorizer.normalize(original)) {
                stringResource(R.string.vocab_saved_label)
            } else {
                stringResource(R.string.vocab_saved_as_label, term)
            },
        )

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

        card.usageNote?.takeIf { it.isNotBlank() }?.let { note ->
            SectionLabel(stringResource(R.string.vocab_usage_note_label))
            MarkdownText(note)
        }

        card.explanation?.takeIf { it.isNotBlank() }?.let { MarkdownText(it) }

        card.contextSentence?.takeIf { it.isNotBlank() }?.let { sentence ->
            SectionLabel(stringResource(R.string.vocab_example_label))
            Text(
                inlineMarkup(sentence, italicBase = true),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The offline-dictionary block. A word often resolves to several headwords — "placas" is a form of both
 * the noun *placa* and the verb *placar* — so the entries can become a radio group whose tapped row is
 * what the Save button saves. The selection itself gets a row too (it isn't always a headword), which is
 * how you save a plural or a conjugation as written.
 *
 * That group only appears once [picking] is set, i.e. after a first tap on Save. Most lookups are just
 * reading, and radio buttons beside every sense turn a definition into a form to fill in; deferring them
 * keeps the common case to plain text and asks the question only when the answer is about to be used.
 */
@Composable
private fun DefinitionSection(
    definition: DefinitionView,
    original: String,
    picking: Boolean,
    selectedLemma: String,
    selectedPos: String?,
    onSelectEntry: (lemma: String, pos: String?) -> Unit,
    onDownloadDictionary: () -> Unit,
) {
    when (definition) {
        DefinitionView.Hidden, DefinitionView.NotFound -> Unit
        is DefinitionView.NeedsDownload -> OutlinedButton(onClick = onDownloadDictionary) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                definition.approxMb
                    ?.let { stringResource(R.string.dict_download_sized, it) }
                    ?: stringResource(R.string.dict_download),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        is DefinitionView.DownloadFailed -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                dictionaryErrorText(definition.reason),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            OutlinedButton(onClick = onDownloadDictionary) {
                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Text(
                    definition.approxMb
                        ?.let { stringResource(R.string.dict_download_sized, it) }
                        ?: stringResource(R.string.dict_download),
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
        DefinitionView.Downloading -> LoadingRow(stringResource(R.string.dict_downloading))
        DefinitionView.Loading -> LoadingRow(stringResource(R.string.dict_loading))
        is DefinitionView.Ready -> {
            val entries = definition.entries
            // The selection needs its own row only when it isn't itself one of the headwords listed.
            val asWritten = picking &&
                entries.none { it.lemma.equals(original.trim(), ignoreCase = true) }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                HorizontalDivider()
                if (picking) {
                    SectionLabel(stringResource(R.string.dict_pick_headword))
                }
                entries.forEach { entry ->
                    val selected = entry.lemma.equals(selectedLemma, ignoreCase = true) &&
                        entry.pos == selectedPos
                    ChoiceRow(
                        selected = selected,
                        enabled = picking,
                        onSelect = { onSelectEntry(entry.lemma, entry.pos) },
                    ) {
                        // While picking, keep each candidate short enough that the whole choice fits on
                        // screen — a headword with five senses each pushes the other options out of
                        // view, so you can't see what you're choosing between (or what's selected).
                        EntryView(
                            entry,
                            maxDefinitions = if (picking) PICKING_DEFINITIONS else ALL_DEFINITIONS,
                            compact = picking,
                        )
                    }
                }
                if (asWritten) {
                    ChoiceRow(
                        selected = selectedLemma.equals(original.trim(), ignoreCase = true),
                        enabled = true,
                        onSelect = { onSelectEntry(original, selectedPos) },
                    ) {
                        Text(
                            stringResource(R.string.dict_as_written, original),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * One entry of the headword radio group. With [enabled] false (a single candidate, or a word that's
 * already saved) it lays the content out plain, with no radio button and no click target.
 */
@Composable
private fun ChoiceRow(
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    Row(
        // Top, not centre: an entry is several lines tall, and a centred radio floats halfway down its
        // definitions instead of sitting beside the headword it selects.
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
    ) {
        // Stripped of the 48dp touch target Material puts around the dot, which would otherwise centre
        // it 24dp down and leave the headword to be padded down to meet it — a fixed nudge that drifts
        // as soon as the font scale moves. The whole row is the tap target, so nothing is lost.
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
            RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(top = 2.dp))
        }
        Column(modifier = Modifier.weight(1f)) { content() }
    }
}

/**
 * One headword: its English meanings, then its senses in the language being learned.
 *
 * English first and unbulleted, because it is the answer to "what does this word mean" and the rest
 * is elaboration. The two editions of the dictionary number their senses differently, so these are
 * the meanings of the *headword* — joined into one line rather than laid out beside the target-language
 * senses, which would imply a sense-for-sense correspondence that isn't there.
 *
 * [compact] drops the per-sense examples: while the entries are a pick-one list, every extra line
 * pushes the other options off screen (see [ChoiceRow]).
 */
@Composable
private fun EntryView(
    entry: DictionaryEntry,
    maxDefinitions: Int = ALL_DEFINITIONS,
    compact: Boolean = false,
) {
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
        if (entry.englishDefinitions.isNotEmpty()) {
            Text(
                entry.englishDefinitions.take(maxDefinitions).joinToString(SENSE_SEPARATOR),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        entry.definitions.take(maxDefinitions).forEach { sense ->
            Text(
                bulleted(sense.gloss),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            sense.labels?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = SENSE_INDENT),
                )
            }
            if (!compact) {
                sense.example?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = SENSE_INDENT),
                    )
                }
            }
        }
    }
}

/** Between English meanings of one headword — a middot, so they don't read as one run-on gloss. */
private const val SENSE_SEPARATOR = " · "

/** Lines subordinate to a sense (its labels, its example) line up with the sense, not its bullet. */
private val SENSE_INDENT = 14.dp

/**
 * A sense as a bullet whose wrapped lines hang under the text rather than falling back under the
 * bullet — glosses routinely run to two lines, and without this the second one reads as a new item.
 */
private fun bulleted(text: String): AnnotatedString = buildAnnotatedString {
    withStyle(ParagraphStyle(textIndent = TextIndent(restLine = BULLET_HANG))) {
        // An en space, not two spaces: a fixed width that doesn't stretch with the font.
        append("•\u2002")
        append(text)
    }
}

/** Width of "•" plus its en space, so the hanging indent lands on the first line's first character. */
private val BULLET_HANG = 12.sp

/** Senses listed per dictionary entry: fewer while the entries are a pick-one list (see [ChoiceRow]). */
private const val ALL_DEFINITIONS = 5
private const val PICKING_DEFINITIONS = 2

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
