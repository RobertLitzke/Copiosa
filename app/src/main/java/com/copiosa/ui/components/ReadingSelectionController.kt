package com.copiosa.ui.components

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
    /** True when [savedCard] was found through its cached forms rather than its front. */
    val savedByForm: Boolean = false,
    /**
     * A saved card whose forms include the selection but which the dictionary says is another word
     * (*tratados* is treaties, not *tratar*). Offered as a link beside the entries, not in their place.
     */
    val relatedCard: FlashcardEntity? = null,
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
    /** The model's account of the selection where it was read, once "Explain" has been asked for. */
    val explanation: String? = null,
    /** True while that request is in flight. */
    val explaining: Boolean = false,
    /** Why the last "Explain" failed; cleared when it is asked for again. */
    val explainError: LlmError? = null,
)

/**
 * The selectable reader's tap/drag-to-translate + define + save-to-vocabulary engine, shared by the
 * Texts reader, chat replies, and a routine's reading step so all behave identically. It owns the
 * transient translation-card state ([translate]), exposes saved words as [highlightTargets] for
 * tinting, passively reviews saved words that turn up in the body being read, and answers the
 * card's "Explain" ([explain]) for the readers that have no chat to ask in.
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

    // The in-flight "Explain", so dismissing the card or moving the selection stops paying for it.
    private var explainJob: Job? = null

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
        // Headword first, then any inflected form (so tapping "gatos" still resolves to "gato"). A
        // forms match is provisional: the dictionary pass below drops it when the selection is
        // another word spelled like one of the card's forms.
        val byFront = cardWithTerm(text)
        val saved = byFront
            ?: allCards.value.firstOrNull {
                normalized in HighlightTargetProvider.decode(it.inflectedForms, it.targetTerm)
            }
        selectionSentence = sentenceAround(sourceText, text)
        explainJob?.cancel()
        _translate.value = TranslateState(
            original = text,
            loading = true,
            anchorY = anchorY,
            savedCard = saved,
            savedByForm = byFront == null && saved != null,
        )

        // Shared with the dictionary pass below, which needs to know how the translation turned out.
        val translated = scope.async { translateOrNull(text) }
        scope.launch {
            val result = translated.await()
            update(text) {
                // Not over a base form's meaning that got here first (see [translateBaseForm]).
                val shown = if (it.translationOf == null) result else it.translation
                it.copy(translation = shown, baseTranslation = result, loading = false)
            }
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
                val entries = (result as? DictionaryResult.Found)?.entries.orEmpty()
                val (lemma, pos) = SaveHeadword.suggest(text, entries)
                update(text) {
                    val saved = it.savedCard
                    val misread = saved != null && it.savedByForm &&
                        !SaveHeadword.formMatchHolds(saved.targetTerm, text, lemma, entries)
                    it.copy(
                        definition = result.toView(),
                        saveLemma = lemma,
                        savePos = pos,
                        savedCard = if (misread) null else saved,
                        relatedCard = if (misread) saved else null,
                    )
                }
                if (lemma != null) translateBaseForm(text, lemma, translated)
            }
        }
    }

    /**
     * The card is offering to save [lemma] rather than the selection, so the meaning it shows — which
     * is the back a save writes — has to be [lemma]'s. Left as the translation of the form in the
     * sentence, "madrigueras" is saved as *madriguera* / "burrows" and "catapultó" as *catapultar* /
     * "catapulted": a front and a back that disagree in number or tense.
     *
     * The dictionary's own English for [lemma] already is that meaning, and wins at save time. This
     * covers the headwords it has none for, by translating the base form itself and saying so. When
     * that fails too — the translator hands back a word it doesn't know untouched — the suggestion
     * is withdrawn instead, so the save is the selection as written beside its own translation.
     * Either way the two sides are forms of each other.
     */
    private suspend fun translateBaseForm(original: String, lemma: String, primary: Deferred<String?>) {
        // After the selection's own translation has landed, so this one is not overwritten by it.
        primary.await()
        val state = _translate.value?.takeIf { it.original == original && it.saveLemma == lemma } ?: return
        if (dictionaryBack(state) != null) return
        val fromLemma = translateOrNull(lemma)?.takeIf { !it.isMissingFor(lemma) }
        update(original) {
            when {
                // The reader may have picked a different entry while this was in flight; that choice wins.
                it.saveLemma != lemma -> it
                fromLemma != null -> it.copy(translation = fromLemma, translationOf = lemma, loading = false)
                else -> it.copy(saveLemma = null)
            }
        }
    }

    /**
     * Picks which dictionary entry a save should use — [lemma] is the headword to save (the selection
     * itself, to save it as written), [pos] its part of speech.
     *
     * The lookup routinely resolves one token to several headwords ("placas" is both a form of the noun
     * *placa* and of the verb *placar*); [SaveHeadword.suggest] can only guess, so the card lets the
     * reader tap the one they meant. The shown meaning follows the choice, so the saved back is the
     * meaning of the headword on the front rather than of whichever entry we guessed first.
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
                when {
                    it.saveLemma != lemma -> it // a later tap won the race
                    fromLemma != null -> it.copy(translation = fromLemma, translationOf = lemma)
                    // The dictionary's English is this headword's meaning, and is what gets saved.
                    dictionaryBack(it) != null -> it.copy(translation = it.baseTranslation, translationOf = null)
                    // Nothing says what this headword means, and the selection's own translation is
                    // not it: fall back to the selection as written rather than pair the two.
                    else -> it.copy(saveLemma = null, translation = it.baseTranslation, translationOf = null)
                }
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

    /** The English meanings of the headword a save would use, as its back; see [SaveHeadword.englishBack]. */
    private fun dictionaryBack(t: TranslateState): String? {
        val entries = (t.definition as? DefinitionView.Ready)?.entries ?: return null
        return SaveHeadword.englishBack(entries, t.saveLemma ?: t.original, t.savePos)
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

    /**
     * Asks the model what the selection means in the sentence it was read in, and shows the answer in
     * the card. This is "Explain" for the readers that have no chat to send the question to — a
     * text, a book, a routine's reading step; chat sends a turn instead, so the answer joins the
     * conversation. Nothing is saved: the card's own explanation is written for the word in general
     * when it is saved, and this one is about a single sentence.
     */
    fun explain() {
        val t = _translate.value ?: return
        if (t.explaining) return
        val original = t.original
        val sentence = selectionSentence
        _translate.value = t.copy(explaining = true, explainError = null)
        explainJob = scope.launch {
            try {
                val explanation = conversationRepo.explainSelection(original, sentence)
                update(original) { it.copy(explanation = explanation, explaining = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                update(original) { it.copy(explaining = false, explainError = e.error) }
            } catch (e: Exception) {
                val error = LlmError.Unknown(e.message ?: "error")
                update(original) { it.copy(explaining = false, explainError = error) }
            }
        }
    }

    fun dismissTranslate() {
        explainJob?.cancel()
        _translate.value = null
    }

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
        // The selection was checked against the deck when the card opened, but the base form or the
        // headword picked since may be a word already saved under another form's lookup (*pateó* ->
        // *patear*, with *pateó* missing from the saved card's forms). Show that note rather than add
        // a second one beside it.
        cardWithTerm(term)?.let { existing ->
            _translate.value = t.copy(savedCard = existing, picking = false)
            return
        }
        val kind = if (term.trim().any { it.isWhitespace() }) CardKind.PHRASE else CardKind.VOCAB
        val pos = t.savePos
        val sentence = selectionSentence
        // Dismiss the card and confirm immediately, before the (first-time slow) dictionary-backed save
        // runs — otherwise the popup lingers with no feedback and a second tap saves a duplicate. This
        // also guards re-entrancy: a second tap now hits the `_translate.value == null` early-return above.
        explainJob?.cancel()
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

    /** The saved card whose front is [term], compared as [WordColorizer.normalize] compares words. */
    private fun cardWithTerm(term: String): FlashcardEntity? {
        val normalized = WordColorizer.normalize(term)
        return allCards.value.firstOrNull { WordColorizer.normalize(it.targetTerm) == normalized }
    }

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
    }
}
