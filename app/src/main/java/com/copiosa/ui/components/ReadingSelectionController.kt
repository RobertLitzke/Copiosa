package com.copiosa.ui.components

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.translate.DictionaryDownload
import com.copiosa.data.translate.DictionaryResult
import com.copiosa.data.translate.LocalTranslateResult
import com.copiosa.domain.highlight.HighlightTarget
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.domain.vocab.WordSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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

/** The transient translation-card state shown over a reading surface while a selection is active. */
data class TranslateState(
    val original: String,
    val translation: String? = null,
    /** Set when [translation] is the lemma's meaning because the surface form couldn't be translated. */
    val translationOf: String? = null,
    /** The surface form's own translation, kept so picking the selection back restores its meaning. */
    val baseTranslation: String? = null,
    val loading: Boolean = true,
    val definition: DefinitionView = DefinitionView.Hidden,
    /** Vertical center of the selection in window pixels, so the card can avoid covering it. */
    val anchorY: Float = 0f,
    /** The matching saved flashcard when this word/phrase is already in the user's vocabulary. */
    val savedCard: FlashcardEntity? = null,
    /** Base form to offer on save when the selection is a conjugation/inflection (e.g. comía → comer). */
    val saveLemma: String? = null,
    /** Part of speech from the dictionary lookup, stored on the saved card. */
    val savePos: String? = null,
    /**
     * True once a Save has been asked for on a word that resolves to several headwords: the card
     * turns its entries into a picker and waits for a second Save to commit the one chosen. Reading a
     * word is the common case, so the radio buttons stay out of the way until a save is actually meant.
     */
    val picking: Boolean = false,
)

/**
 * The selectable reader's tap/drag-to-translate + define + save-to-vocabulary engine, shared by the
 * Texts reader, chat replies, and a routine's reading step so all behave identically. It owns the
 * transient translation-card state ([translate]), exposes saved words as [highlightTargets] for
 * tinting, and passively reviews saved words that turn up in the body being read.
 *
 * Constructed by the owning ViewModel with its [scope]; [source] is stamped on saved cards (a
 * provider, not a value, because a chat's id changes when a draft becomes real), [readingText] is
 * the body scanned for passive review, and [highlightEnabled] is whether due vocabulary is tinted
 * here at all — [highlightTargets] comes out empty when it is not, so every consumer of a colorizer
 * (the tint, a persona's decoration) goes quiet together instead of each gating at the call site.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReadingSelectionController(
    private val scope: CoroutineScope,
    private val conversationRepo: ConversationRepository,
    private val flashcardRepo: FlashcardRepository,
    private val highlightProvider: HighlightTargetProvider,
    private val source: () -> WordSource,
    readingText: Flow<String>,
    highlightEnabled: Flow<Boolean>,
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
    val highlightTargets: StateFlow<List<HighlightTarget>> =
        combine(_highlightTargets, highlightEnabled) { targets, on -> if (on) targets else emptyList() }
            .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
                val forms = formsByCard[card.id] ?: setOf(WordColorizer.normalize(card.targetTerm))
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
        // Headword first, then any inflected form (so tapping "gatos" still resolves to "gato").
        val saved = allCards.value.firstOrNull { WordColorizer.normalize(it.targetTerm) == normalized }
            ?: allCards.value.firstOrNull {
                normalized in HighlightTargetProvider.decode(it.inflectedForms, it.targetTerm)
            }
        selectionSentence = sentenceAround(sourceText, text)
        _translate.value = TranslateState(original = text, loading = true, anchorY = anchorY, savedCard = saved)

        // Shared with the dictionary pass below, which needs to know how the translation turned out.
        val translated = scope.async { translateOrNull(text) }
        scope.launch {
            val result = translated.await()
            update(text) { it.copy(translation = result, baseTranslation = result, loading = false) }
        }

        val word = text.trim()
        if (word.isNotEmpty() && word.none { it.isWhitespace() } && conversationRepo.dictionaryConfigured()) {
            scope.launch {
                if (!conversationRepo.dictionaryDownloaded()) {
                    val size = conversationRepo.dictionarySizeMb()
                    update(text) { it.copy(definition = DefinitionView.NeedsDownload(size)) }
                    return@launch
                }
                update(text) { it.copy(definition = DefinitionView.Loading) }
                val result = conversationRepo.defineSelection(text)
                val (lemma, pos) = lemmaSuggestion(text, result)
                update(text) { it.copy(definition = result.toView(), saveLemma = lemma, savePos = pos) }
                if (lemma != null) translateLemmaInstead(text, lemma, translated)
            }
        }
    }

    /**
     * The on-device translator hands back a form it doesn't know untouched ("maceran" → "maceran"),
     * which reads as a card that simply failed. A rare conjugation is exactly where it gives up, so
     * when the dictionary has resolved the form to a lemma, translate that instead and say so.
     */
    private suspend fun translateLemmaInstead(original: String, lemma: String, primary: Deferred<String?>) {
        if (!primary.await().isMissingFor(original)) return
        val fromLemma = translateOrNull(lemma) ?: return
        // No better than before: a lemma that also comes back untouched is nothing worth showing.
        if (fromLemma.isMissingFor(lemma)) return
        update(original) {
            // The reader may have picked a different entry while this was in flight; that choice wins.
            if (it.saveLemma != lemma) it
            else it.copy(translation = fromLemma, translationOf = lemma, loading = false)
        }
    }

    /**
     * Picks which dictionary entry a save should use — [lemma] is the headword to save (the selection
     * itself, to save it as written), [pos] its part of speech.
     *
     * The lookup routinely resolves one token to several headwords ("placas" is both a form of the noun
     * *placa* and of the verb *placar*); [lemmaSuggestion] can only guess, so the card lets the reader
     * tap the one they meant. The shown meaning follows the choice, so the saved back is the meaning of
     * the headword on the front rather than of whichever entry we guessed first.
     */
    fun selectSaveEntry(lemma: String, pos: String?) {
        val t = _translate.value ?: return
        val original = t.original
        if (WordColorizer.normalize(lemma) == WordColorizer.normalize(original)) {
            update(original) {
                it.copy(saveLemma = null, savePos = pos, translation = it.baseTranslation, translationOf = null)
            }
            return
        }
        update(original) { it.copy(saveLemma = lemma, savePos = pos) }
        scope.launch {
            val fromLemma = translateOrNull(lemma)?.takeIf { !it.isMissingFor(lemma) }
            update(original) {
                if (it.saveLemma != lemma) it // a later tap won the race
                else it.copy(
                    translation = fromLemma ?: it.baseTranslation,
                    translationOf = lemma.takeIf { _ -> fromLemma != null },
                )
            }
        }
    }

    /** The on-device translation of [text], or null when it failed or came back empty. */
    private suspend fun translateOrNull(text: String): String? =
        when (val r = conversationRepo.localTranslate(text)) {
            is LocalTranslateResult.Success -> r.text.trim().ifBlank { null }
            LocalTranslateResult.Unsupported, LocalTranslateResult.Unavailable -> null
        }

    /** True when this "translation" is absent or just [word] handed back unchanged. */
    private fun String?.isMissingFor(word: String): Boolean =
        this == null || WordColorizer.normalize(this) == WordColorizer.normalize(word)

    /** Base form to offer on save (when the selection is an inflection of a different lemma) + POS. */
    private fun lemmaSuggestion(word: String, result: DictionaryResult): Pair<String?, String?> {
        val entries = (result as? DictionaryResult.Found)?.entries ?: return null to null
        val inflection = entries.firstOrNull { it.inflectionOf != null && it.pos?.contains("verb", true) == true }
            ?: entries.firstOrNull { it.inflectionOf != null }
        val lemma = inflection?.lemma?.takeIf { !it.equals(word.trim(), ignoreCase = true) }
        return lemma to (inflection?.pos ?: entries.firstOrNull()?.pos)
    }

    /**
     * The picked entry's English meanings as a card back, or null when there are none.
     *
     * Capped: a back is a prompt to recall, and four comma-separated meanings is a paragraph to
     * memorise. The rest stay in the dictionary, where they can be looked up again.
     */
    private fun dictionaryBack(t: TranslateState): String? {
        val entries = (t.definition as? DefinitionView.Ready)?.entries ?: return null
        val lemma = t.saveLemma ?: t.original
        val entry = entries.firstOrNull { it.lemma.equals(lemma, ignoreCase = true) && it.pos == t.savePos }
            ?: entries.firstOrNull { it.lemma.equals(lemma, ignoreCase = true) }
            ?: return null
        return entry.englishDefinitions.take(MAX_SAVED_MEANINGS)
            .joinToString("; ")
            .ifBlank { null }
    }

    /**
     * How many headwords a save could mean: the dictionary's entries, plus the selection itself when
     * it isn't one of them (which is how a plural or a conjugation gets saved as written). Mirrors the
     * rows [com.copiosa.ui.components.TranslationCard] lays out, so the picker never opens on a single
     * choice and never stays shut on a real one.
     */
    private fun candidateCount(t: TranslateState): Int {
        val entries = (t.definition as? DefinitionView.Ready)?.entries ?: return 1
        val asWritten = entries.none { it.lemma.equals(t.original.trim(), ignoreCase = true) }
        return entries.size + if (asWritten) 1 else 0
    }

    fun downloadDictionary() {
        val word = _translate.value?.original ?: return
        update(word) { it.copy(definition = DefinitionView.Downloading) }
        scope.launch {
            val outcome = conversationRepo.downloadDictionary()
            if (outcome != DictionaryDownload.Ready) {
                val size = conversationRepo.dictionarySizeMb()
                update(word) { it.copy(definition = DefinitionView.DownloadFailed(outcome, size)) }
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
        // The dictionary's English, when it has any, beats machine-translating the bare word: it is
        // the meanings of *this* headword and part of speech, written by people, and it is what the
        // card was showing. Fall back to the on-device translation for everything else — phrases,
        // words the pack doesn't carry, and a pack that isn't downloaded.
        val back = dictionaryBack(t) ?: t.translation ?: return
        // Ambiguous word, first Save: reveal the picker instead of committing a guess. The second Save
        // (with [TranslateState.picking] set) saves whichever headword was tapped.
        if (!t.picking && candidateCount(t) > 1) {
            _translate.value = t.copy(picking = true)
            return
        }
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
            val from = source()
            val newId = flashcardRepo.add(
                front = term,
                back = back,
                contextSentence = sentence,
                sourceConversationId = from.conversationId,
                sourceBookId = from.bookId,
                sourceRoutineRunId = from.routineRunId,
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
        DictionaryResult.Unavailable -> DefinitionView.NeedsDownload()
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

        /** English meanings kept on a saved card's back; the rest are a lookup, not a thing to recall. */
        const val MAX_SAVED_MEANINGS = 3
    }
}
