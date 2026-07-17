package com.copiosa.ui.components

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.translate.DictionaryResult
import com.copiosa.data.translate.LocalTranslateResult
import com.copiosa.domain.highlight.HighlightTarget
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.ui.conversation.TranslateState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The selectable reader's tap/drag-to-translate + define + save-to-vocabulary engine, shared by the
 * Texts reader and a routine's reading step so the two behave identically. It owns the transient
 * translation-card state ([translate]), exposes saved words as [highlightTargets] for tinting, and
 * passively reviews saved words that turn up in the body being read.
 *
 * Constructed by the owning ViewModel with its [scope]; [sourceConversationId] is stamped on saved
 * cards (null when there's no conversation, e.g. a routine's generated paragraphs), and [readingText]
 * is the body scanned for passive review.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSelectionController(
    private val scope: CoroutineScope,
    private val conversationRepo: ConversationRepository,
    private val flashcardRepo: FlashcardRepository,
    private val highlightProvider: HighlightTargetProvider,
    private val sourceConversationId: Long?,
    readingText: Flow<String>,
) {
    private val allCards = flashcardRepo.observeAll()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Saved flashcards as highlight targets (each carrying its plural/conjugated forms, with gender for
     * article highlighting), to tint already-saved words in the reading text. Only words due for review
     * soon are highlighted (see [HighlightTargetProvider]) so well-known words don't clutter it. A
     * [MutableStateFlow] rather than a derived flow so [addImmediateHighlight] can light up a just-saved
     * word at once, instead of waiting for the Room re-emit + rebuild.
     */
    private val _highlightTargets = MutableStateFlow<List<HighlightTarget>>(emptyList())
    val highlightTargets: StateFlow<List<HighlightTarget>> = _highlightTargets.asStateFlow()

    private val _translate = MutableStateFlow<TranslateState?>(null)
    val translate: StateFlow<TranslateState?> = _translate.asStateFlow()

    private val _savedNotice = MutableStateFlow(false)
    val savedNotice: StateFlow<Boolean> = _savedNotice.asStateFlow()

    /** Card ids already passively reviewed this screen session, so re-renders don't re-push them. */
    private val passivelyReviewed = mutableSetOf<Long>()

    // The sentence the current selection came from, captured so a saved card keeps its context.
    private var selectionSentence: String? = null

    init {
        // Rebuild highlight targets from the saved deck whenever it changes (fast: reads cached forms).
        scope.launch {
            allCards.collect { cards -> _highlightTargets.value = highlightProvider.build(cards, System.currentTimeMillis()) }
        }
        // One-time: cache plural/conjugated forms on cards saved before the column existed.
        scope.launch { flashcardRepo.ensureFormsBackfilled() }
        // Reading a saved word in the text counts as a light review: push its next review back.
        scope.launch {
            combine(readingText, allCards) { text, cards -> text to cards }
                .collect { (text, cards) -> applyPassiveReviews(text, cards) }
        }
    }

    /** Tints [target] immediately so a just-saved word lights up without waiting for the DB rebuild. */
    private fun addImmediateHighlight(target: HighlightTarget) {
        _highlightTargets.update { current -> listOf(target) + current.filterNot { it.forms == target.forms } }
    }

    /** Passively reviews every eligible saved word that appears in [text] (once each per session). */
    private suspend fun applyPassiveReviews(text: String, cards: List<FlashcardEntity>) {
        if (text.isBlank() || cards.isEmpty()) return
        // The body can be a long article and this collector re-fires as it grows, so scan off the main
        // thread; the processed-set keeps it idempotent.
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            // Only cards close enough to get a passive push are worth expanding (see passiveReview).
            val candidates = cards.filter {
                it.id !in passivelyReviewed && !it.suspended && it.dueAt <= now + ReviewWindows.PASSIVE_WINDOW_MS
            }
            if (candidates.isEmpty()) return@withContext
            val seen = WORD_REGEX.findAll(text).mapTo(hashSetOf()) { it.value.lowercase() }
            if (seen.isEmpty()) return@withContext
            // Match against each card's inflected forms, so seeing "gatos" reviews a "gato" card too.
            val formsByCard = highlightProvider.formsByCard(candidates)
            for (card in candidates) {
                val forms = formsByCard[card.id] ?: setOf(WordColorizer.normalize(card.front))
                if (forms.none { it.isNotEmpty() && it.none(Char::isWhitespace) && it in seen }) continue
                passivelyReviewed += card.id
                flashcardRepo.passiveReview(card, now)
            }
        }
    }

    /**
     * Translates the selected phrase on-device for the card. [sourceText] is the body the selection was
     * made in, so we can keep the sentence it appeared in for the saved card.
     */
    fun onSelectionTranslate(text: String, anchorY: Float, sourceText: String) {
        val normalized = WordColorizer.normalize(text)
        val saved = allCards.value.firstOrNull { WordColorizer.normalize(it.front) == normalized }
        selectionSentence = sentenceAround(sourceText, text)
        _translate.value = TranslateState(original = text, loading = true, anchorY = anchorY, savedCard = saved)

        scope.launch {
            val translation = when (val r = conversationRepo.localTranslate(text)) {
                is LocalTranslateResult.Success -> r.text
                LocalTranslateResult.Unsupported, LocalTranslateResult.Unavailable -> null
            }
            update(text) { it.copy(translation = translation, loading = false) }
        }

        val word = text.trim()
        if (word.isNotEmpty() && word.none { it.isWhitespace() } && conversationRepo.dictionaryConfigured()) {
            scope.launch {
                if (!conversationRepo.dictionaryDownloaded()) {
                    update(text) { it.copy(definition = DefinitionView.NeedsDownload) }
                    return@launch
                }
                update(text) { it.copy(definition = DefinitionView.Loading) }
                val result = conversationRepo.defineSelection(text)
                val (lemma, pos) = lemmaSuggestion(text, result)
                update(text) { it.copy(definition = result.toView(), saveLemma = lemma, savePos = pos) }
            }
        }
    }

    /** Base form to offer on save (when the selection is an inflection of a different lemma) + POS. */
    private fun lemmaSuggestion(word: String, result: DictionaryResult): Pair<String?, String?> {
        val entries = (result as? DictionaryResult.Found)?.entries ?: return null to null
        val inflection = entries.firstOrNull { it.inflectionOf != null && it.pos?.contains("verb", true) == true }
            ?: entries.firstOrNull { it.inflectionOf != null }
        val lemma = inflection?.lemma?.takeIf { !it.equals(word.trim(), ignoreCase = true) }
        return lemma to (inflection?.pos ?: entries.firstOrNull()?.pos)
    }

    fun downloadDictionary() {
        val word = _translate.value?.original ?: return
        update(word) { it.copy(definition = DefinitionView.Downloading) }
        scope.launch {
            if (!conversationRepo.downloadDictionary()) {
                update(word) { it.copy(definition = DefinitionView.NeedsDownload) }
                return@launch
            }
            update(word) { it.copy(definition = DefinitionView.Loading) }
            val view = conversationRepo.defineSelection(word).toView()
            update(word) { it.copy(definition = view) }
        }
    }

    fun dismissTranslate() { _translate.value = null }

    fun saveCurrentTranslation(headword: String? = null) {
        val t = _translate.value ?: return
        val back = t.translation ?: return
        val term = headword?.takeIf { it.isNotBlank() } ?: t.original
        val kind = if (term.trim().any { it.isWhitespace() }) CardKind.PHRASE else CardKind.VOCAB
        val pos = t.savePos
        val sentence = selectionSentence
        // Dismiss the card and confirm immediately, before the (first-time slow) dictionary-backed save
        // runs — otherwise the popup lingers with no feedback and a second tap saves a duplicate. This
        // also guards re-entrancy: a second tap now hits the `_translate.value == null` early-return above.
        _translate.value = null
        _savedNotice.value = true
        scope.launch {
            val newId = flashcardRepo.add(
                front = term,
                back = back,
                contextSentence = sentence,
                sourceConversationId = sourceConversationId,
                partOfSpeech = pos,
                cardKind = kind,
            )
            // Light it up at once; the Room re-emit will rebuild an equivalent list, so no flicker.
            flashcardRepo.getCard(newId)?.let { card -> highlightProvider.targetFor(card)?.let(::addImmediateHighlight) }
        }
    }

    fun consumeSavedNotice() { _savedNotice.value = false }

    private fun update(original: String, transform: (TranslateState) -> TranslateState) {
        val current = _translate.value ?: return
        if (current.original != original) return
        _translate.value = transform(current)
    }

    private fun DictionaryResult.toView(): DefinitionView = when (this) {
        is DictionaryResult.Found -> DefinitionView.Ready(entries)
        DictionaryResult.NotFound -> DefinitionView.NotFound
        DictionaryResult.Unavailable -> DefinitionView.NeedsDownload
    }

    /** Best-effort: the sentence in [content] containing [phrase], trimmed; null when not found. */
    private fun sentenceAround(content: String, phrase: String): String? {
        val idx = content.indexOf(phrase)
        if (idx < 0) return null
        val breaks = charArrayOf('.', '!', '?', '\n', '。', '！', '？')
        var start = idx
        while (start > 0 && content[start - 1] !in breaks) start--
        var end = idx + phrase.length
        while (end < content.length && content[end] !in breaks) end++
        if (end < content.length) end++
        return content.substring(start, end).trim().ifBlank { null }
    }

    private companion object {
        val WORD_REGEX = Regex("[\\p{L}\\p{N}]+")
    }
}
