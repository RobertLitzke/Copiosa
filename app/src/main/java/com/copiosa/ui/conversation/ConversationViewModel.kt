package com.copiosa.ui.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.generation.AudioGenerationManager
import com.copiosa.data.generation.GenPhase
import com.copiosa.data.generation.GenerationManager
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.local.MessageEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.translate.DictionaryResult
import com.copiosa.data.translate.LocalTranslateResult
import com.copiosa.ui.components.DefinitionView
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import com.copiosa.domain.highlight.HighlightTarget
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.prompt.StructuredReplyParser
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.ui.components.WordColorizer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class TranslateState(
    val original: String,
    val translation: String? = null,
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
)

data class ConversationUiState(
    val input: String = "",
    val sending: Boolean = false,
    val error: LlmError? = null,
    val translate: TranslateState? = null,
    /** Id of the message whose selection is currently highlighted, so only one shows at a time. */
    val selectionMessageId: Long? = null,
    val savedNotice: Boolean = false,
)

/** Level + model + provider + variant currently in effect, whether a draft or a stored conversation. */
data class ChatConfig(
    val level: Cefr = Cefr.B2,
    val model: String = AppSettings.DEFAULT_GEMINI_MODEL,
    val provider: ProviderId = ProviderId.GEMINI,
    val country: String = "Mexico",
    val targetLanguage: String = "es",
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val conversationRepo: ConversationRepository,
    private val flashcardRepo: FlashcardRepository,
    private val settingsRepo: SettingsRepository,
    private val generation: GenerationManager,
    private val audioGeneration: AudioGenerationManager,
    private val ttsModelStore: SupertonicModelStore,
    private val highlightProvider: HighlightTargetProvider,
) : ViewModel() {

    // A real id opens that conversation; the draft sentinel (0) starts a fresh chat.
    private val navArg: Long = savedStateHandle.get<Long>("conversationId") ?: DRAFT

    private val _activeId = MutableStateFlow(navArg)
    val activeId: StateFlow<Long> = _activeId.asStateFlow()

    val messages: StateFlow<List<MessageEntity>> =
        _activeId.flatMapLatest { id ->
            if (id == DRAFT) flowOf(emptyList()) else conversationRepo.observeMessages(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val conversation: StateFlow<ConversationEntity?> =
        _activeId.flatMapLatest { id ->
            if (id == DRAFT) flowOf(null) else conversationRepo.observeConversation(id)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** True for an imported text: its leading seed user turn is hidden in the chat (see screen). */
    val isTextMode: StateFlow<Boolean> =
        conversation.map { it?.mode == Mode.URL_TRANSLATION }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Whether the open conversation is starred, for the overflow menu's star toggle. */
    val starred: StateFlow<Boolean> =
        conversation.map { it?.starred == true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The open conversation's title, shown in the chat header; blank for a fresh draft. */
    val title: StateFlow<String> =
        conversation.map { it?.title.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // Draft (pre-creation) overrides, seeded from settings; ignored once a conversation exists.
    private val _draftLevel = MutableStateFlow(Cefr.B2)
    private val _draftProvider = MutableStateFlow(ProviderId.GEMINI)

    /** The level/model/provider/variant the UI shows and the in-chat model menu edits. */
    val config: StateFlow<ChatConfig> =
        combine(conversation, _draftLevel, _draftProvider, settingsRepo.settings) { conv, dLevel, dProvider, settings ->
            if (conv != null) {
                ChatConfig(conv.level, conv.model, conv.provider, conv.country, conv.targetLanguage)
            } else {
                ChatConfig(dLevel, settings.modelFor(dProvider), dProvider, settings.defaultCountry, settings.targetLanguage)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ChatConfig())

    private val _ui = MutableStateFlow(ConversationUiState())

    // True only during the brief window where a draft chat's row is being created (before the
    // generation manager takes over and reports GENERATING). Keeps "Thinking…" from flickering off.
    private val _starting = MutableStateFlow(false)

    /**
     * The chat UI state. [ConversationUiState.sending] is *derived* from the generation manager (and
     * the draft-start window) rather than stored, so the in-flight indicator reflects a fetch that
     * continues in the background and is still correct after navigating away and back.
     */
    val ui: StateFlow<ConversationUiState> =
        combine(_ui, _activeId, generation.active, _starting) { base, id, active, starting ->
            base.copy(sending = starting || (id != DRAFT && active[id] == GenPhase.GENERATING))
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConversationUiState())

    /** Every saved flashcard, kept hot so a tapped word can be matched to its full entry. */
    private val allCards: StateFlow<List<FlashcardEntity>> =
        flashcardRepo.observeAll()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Saved flashcards as highlight targets (each carrying its plural/conjugated forms, with gender for
     * article highlighting), used to tint already-saved words in replies. Locked-in words (well
     * understood, due far out) are excluded so they don't clutter the text. A [MutableStateFlow] rather
     * than a derived flow so [addImmediateHighlight] can light up a just-saved word at once, instead of
     * waiting for the Room re-emit + rebuild.
     */
    private val _highlightTargets = MutableStateFlow<List<HighlightTarget>>(emptyList())
    val highlightTargets: StateFlow<List<HighlightTarget>> = _highlightTargets.asStateFlow()

    /**
     * Suggested next subjects, shown as tappable chips below the latest reply. Tracks the freshest
     * reply that actually carries topics (see [observeTopics]): "Explain" lookups omit them, so the
     * chips stay put on the last main-line suggestions across an explanation and across navigation.
     */
    private val _nextTopics = MutableStateFlow<List<String>>(emptyList())
    val nextTopics: StateFlow<List<String>> = _nextTopics.asStateFlow()

    /** Card ids already passively reviewed this screen session, so re-renders don't re-push them. */
    private val passivelyReviewed = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            _draftLevel.value = settings.defaultLevel
            _draftProvider.value = settings.provider
        }
        // Surface a background generation failure for whichever conversation is currently open — so a
        // fetch that failed while the user was elsewhere shows its reason when they return to the chat.
        viewModelScope.launch {
            combine(_activeId, generation.errors) { id, errs -> errs[id] }
                .collect { err -> if (err != null) _ui.value = _ui.value.copy(error = err) }
        }
        // Opening a conversation marks it read, clearing its unread highlight in the chat list.
        viewModelScope.launch {
            _activeId.collect { id -> if (id != DRAFT) conversationRepo.setRead(id, true) }
        }
        observeTopics()
        // Rebuild highlight targets from the saved deck whenever it changes (fast: reads cached forms).
        viewModelScope.launch {
            allCards.collect { cards -> _highlightTargets.value = highlightProvider.build(cards, System.currentTimeMillis()) }
        }
        // One-time: cache plural/conjugated forms on cards saved before the column existed.
        viewModelScope.launch { flashcardRepo.ensureFormsBackfilled() }
        // Seeing a saved word in a reply counts as a light review: push its next review back.
        viewModelScope.launch {
            combine(messages, allCards) { msgs, cards -> msgs to cards }
                .collect { (msgs, cards) -> applyPassiveReviews(msgs.filter { it.role == Role.ASSISTANT }, cards) }
        }
    }

    /** Tints [target] immediately so a just-saved word lights up without waiting for the DB rebuild. */
    private fun addImmediateHighlight(target: HighlightTarget) {
        _highlightTargets.update { current -> listOf(target) + current.filterNot { it.forms == target.forms } }
    }

    /**
     * Keeps [_nextTopics] in step with the conversation. Scans from the newest reply backward for the
     * first that actually carries next-topic suggestions, so a main-line reply replaces the chips while
     * an "Explain" reply (which no longer requests topics) is skipped and the prior suggestions persist.
     * Empty only when no reply in the chat has any topics.
     */
    private fun observeTopics() {
        viewModelScope.launch {
            messages.collect { msgs ->
                _nextTopics.value = msgs.asReversed().firstNotNullOfOrNull { msg ->
                    if (msg.role != Role.ASSISTANT) null
                    else StructuredReplyParser.decode(msg.metadata).topicPhrases.takeIf { it.isNotEmpty() }
                }.orEmpty()
            }
        }
    }

    /** Passively reviews every eligible saved word that appears in [assistantMessages] (once each). */
    private suspend fun applyPassiveReviews(assistantMessages: List<MessageEntity>, cards: List<FlashcardEntity>) {
        if (cards.isEmpty() || assistantMessages.isEmpty()) return
        // Tokenizing reply text runs off the main thread (the collector fires on every message/card
        // change); the processed-set keeps it idempotent.
        withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            // Only cards close enough to get a passive push are worth expanding (see passiveReview).
            val candidates = cards.filter {
                it.id !in passivelyReviewed && !it.suspended && it.dueAt <= now + ReviewWindows.PASSIVE_WINDOW_MS
            }
            if (candidates.isEmpty()) return@withContext
            val seen = assistantMessages.flatMapTo(hashSetOf()) { tokenize(it.content) }
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

    /** Lowercased alphanumeric word tokens, matching [WordColorizer.normalize] for single words. */
    private fun tokenize(text: String): List<String> =
        WORD_REGEX.findAll(text).map { it.value.lowercase() }.toList()

    /** Whether the open (non-draft) conversation currently has a model call in flight. */
    private fun generatingNow(): Boolean {
        val id = _activeId.value
        return id != DRAFT && generation.active.value[id] == GenPhase.GENERATING
    }

    fun setInput(text: String) { _ui.value = _ui.value.copy(input = text) }

    /** Resets to a fresh blank chat in place. No-op if already an empty draft. */
    fun newChat() {
        if (_activeId.value == DRAFT) return
        _activeId.value = DRAFT
        _ui.value = ConversationUiState()
        _nextTopics.value = emptyList()
        viewModelScope.launch {
            val settings = settingsRepo.settings.first()
            _draftLevel.value = settings.defaultLevel
            _draftProvider.value = settings.provider
        }
    }

    fun setLevel(level: Cefr) {
        val id = _activeId.value
        if (id == DRAFT) _draftLevel.value = level
        else viewModelScope.launch { conversationRepo.setConversationLevel(id, level) }
    }

    fun setProvider(provider: ProviderId) {
        val id = _activeId.value
        if (id == DRAFT) _draftProvider.value = provider
        else viewModelScope.launch { conversationRepo.setConversationProvider(id, provider) }
    }

    fun send() {
        val text = _ui.value.input.trim()
        if (text.isEmpty()) return
        if (_activeId.value == DRAFT) sendFirst(text)
        else {
            if (generatingNow()) return
            _ui.value = _ui.value.copy(input = "", error = null)
            // Runs on the app-scoped manager, so it survives leaving the chat.
            generation.sendUserTurn(_activeId.value, text)
        }
    }

    /** Sends a tapped "next topic" chip as the next user turn immediately. */
    fun sendTopic(topic: String) {
        val text = topic.trim()
        if (text.isEmpty()) return
        setInput(text)
        send()
    }

    /** Aborts the in-flight send/explain, leaving any already-sent learner message in place. */
    fun cancelSend() {
        val id = _activeId.value
        if (id != DRAFT) generation.cancel(id)
    }

    /** Regenerates the reply for a chat whose last turn is a dangling (failed) user message. */
    fun retry() {
        val id = _activeId.value
        if (id != DRAFT && !generatingNow()) {
            _ui.value = _ui.value.copy(error = null)
            generation.generateReply(id)
        }
    }

    /** Toggles the open conversation's starred flag (overflow menu). */
    fun toggleStar() {
        val id = _activeId.value.takeIf { it != DRAFT } ?: return
        val next = !starred.value
        viewModelScope.launch { conversationRepo.setStarred(id, next) }
    }

    /** Marks the open conversation unread so it stands out again in the list (overflow menu). */
    fun markUnread() {
        val id = _activeId.value.takeIf { it != DRAFT } ?: return
        viewModelScope.launch { conversationRepo.setRead(id, false) }
    }

    /** Deletes the open conversation; the screen pops back to the list afterwards. */
    fun delete() {
        val id = _activeId.value.takeIf { it != DRAFT } ?: return
        viewModelScope.launch { conversationRepo.delete(id) }
    }

    /** Whether the on-device voice is installed, so the screen can prompt to download it if not. */
    fun isTtsReady(): Boolean = ttsModelStore.isReady()

    /** Starts the (opt-in) voice download — only ever called from the "Send to audio" prompt. */
    fun startTtsDownload() = ttsModelStore.download()

    /** Queues an assistant reply for on-device TTS; it renders once the voice is ready. */
    fun sendToAudio(messageId: Long) {
        val msg = messages.value.firstOrNull { it.id == messageId } ?: return
        if (msg.role != Role.ASSISTANT || msg.content.isBlank()) return
        val conv = conversation.value
        // Use the list title (target-language), not the native-language subject, so the audio item
        // matches what the chat list shows.
        val title = conv?.title?.takeIf { it.isNotBlank() }
            ?: msg.content.lineSequence().firstOrNull { it.isNotBlank() }?.take(60).orEmpty()
        audioGeneration.enqueue(
            text = msg.content,
            title = title,
            langCode = config.value.targetLanguage,
            sourceConversationId = _activeId.value.takeIf { it != DRAFT },
            sourceMessageId = messageId,
        )
    }

    /** Queues a highlighted selection (a word, phrase, or sentence) for on-device TTS. */
    fun sendSelectionToAudio(text: String) {
        val clean = text.trim()
        if (clean.isBlank()) return
        audioGeneration.enqueue(
            text = clean,
            title = clean.take(60),
            langCode = config.value.targetLanguage,
            sourceConversationId = _activeId.value.takeIf { it != DRAFT },
        )
    }

    /**
     * First turn of a fresh chat: create the conversation row (so the learner's message shows at
     * once), then hand off to the manager to fetch the opening reply in the background. A failed reply
     * keeps the chat (with its subject) so it can be retried from the list — it isn't rolled back.
     */
    private fun sendFirst(text: String) {
        if (_starting.value) return
        _starting.value = true
        _ui.value = _ui.value.copy(input = "", error = null)
        viewModelScope.launch {
            try {
                val settings = settingsRepo.settings.first()
                val provider = _draftProvider.value
                val id = conversationRepo.createDraftConversation(
                    level = _draftLevel.value,
                    country = settings.defaultCountry,
                    subject = text,
                    provider = provider,
                    model = settings.modelFor(provider),
                )
                _activeId.value = id // surfaces the learner's message immediately
                generation.generateReply(id)
            } catch (e: Exception) {
                // Only reached if creating the row itself fails (e.g. DB) — restore the text.
                _ui.value = _ui.value.copy(input = text, error = LlmError.Unknown(e.message ?: "error"))
            } finally {
                _starting.value = false
            }
        }
    }

    /** Sends [text] as a user turn (used by the popover's "Explain" action). Needs a saved chat. */
    fun explain(text: String) {
        val id = _activeId.value.takeIf { it != DRAFT } ?: return
        if (generatingNow()) return
        // An explanation is a vocabulary aside: it omits next-topics (so the chips persist) and doesn't
        // seed the routine topic table — see ConversationRepository.sendMessage.
        _ui.value = _ui.value.copy(error = null, translate = null, selectionMessageId = null)
        generation.sendUserTurn(id, text, isExplanation = true)
    }

    /**
     * Looks up the selected phrase on-device and shows it in the translation card. No LLM/network
     * call; when on-device translation isn't available the card falls back to nudging Explain.
     */
    fun onSelectionTranslate(messageId: Long, text: String, anchorY: Float) {
        // If the word/phrase is already saved, surface its full entry alongside the translation.
        val normalized = WordColorizer.normalize(text)
        val saved = allCards.value.firstOrNull { WordColorizer.normalize(it.front) == normalized }
        _ui.value = _ui.value.copy(
            translate = TranslateState(original = text, loading = true, anchorY = anchorY, savedCard = saved),
            selectionMessageId = messageId,
        )
        // Quick on-device translation (ML Kit).
        viewModelScope.launch {
            val translation = when (val r = conversationRepo.localTranslate(text)) {
                is LocalTranslateResult.Success -> r.text
                LocalTranslateResult.Unsupported, LocalTranslateResult.Unavailable -> null
            }
            updateTranslate(text) { it.copy(translation = translation, loading = false) }
        }
        // Offline definition (single words only; multi-word phrases skip the dictionary).
        val word = text.trim()
        if (word.isNotEmpty() && word.none { it.isWhitespace() } && conversationRepo.dictionaryConfigured()) {
            viewModelScope.launch {
                if (!conversationRepo.dictionaryDownloaded()) {
                    updateTranslate(text) { it.copy(definition = DefinitionView.NeedsDownload) }
                    return@launch
                }
                updateTranslate(text) { it.copy(definition = DefinitionView.Loading) }
                val result = conversationRepo.defineSelection(text)
                val (lemma, pos) = lemmaSuggestion(text, result)
                updateTranslate(text) { it.copy(definition = result.toView(), saveLemma = lemma, savePos = pos) }
            }
        }
    }

    /**
     * From a dictionary lookup, the base form to offer on save (when the selection is an inflection of
     * a different lemma — e.g. "comía" → "comer") and the part of speech. Prefers a verb inflection.
     */
    private fun lemmaSuggestion(word: String, result: DictionaryResult): Pair<String?, String?> {
        val entries = (result as? DictionaryResult.Found)?.entries ?: return null to null
        val inflection = entries.firstOrNull { it.inflectionOf != null && it.pos?.contains("verb", true) == true }
            ?: entries.firstOrNull { it.inflectionOf != null }
        val lemma = inflection?.lemma?.takeIf { !it.equals(word.trim(), ignoreCase = true) }
        return lemma to (inflection?.pos ?: entries.firstOrNull()?.pos)
    }

    /** Triggered by the card's "download dictionary" button; fetches the pack then re-runs lookup. */
    fun downloadDictionary() {
        val word = _ui.value.translate?.original ?: return
        updateTranslate(word) { it.copy(definition = DefinitionView.Downloading) }
        viewModelScope.launch {
            if (!conversationRepo.downloadDictionary()) {
                updateTranslate(word) { it.copy(definition = DefinitionView.NeedsDownload) }
                return@launch
            }
            updateTranslate(word) { it.copy(definition = DefinitionView.Loading) }
            val view = conversationRepo.defineSelection(word).toView()
            updateTranslate(word) { it.copy(definition = view) }
        }
    }

    /** Updates the current translation card iff it's still showing [original] (drops stale results). */
    private fun updateTranslate(original: String, transform: (TranslateState) -> TranslateState) {
        val current = _ui.value.translate ?: return
        if (current.original != original) return
        _ui.value = _ui.value.copy(translate = transform(current))
    }

    private fun DictionaryResult.toView(): DefinitionView = when (this) {
        is DictionaryResult.Found -> DefinitionView.Ready(entries)
        DictionaryResult.NotFound -> DefinitionView.NotFound
        DictionaryResult.Unavailable -> DefinitionView.NeedsDownload
    }

    fun dismissTranslate() { _ui.value = _ui.value.copy(translate = null, selectionMessageId = null) }

    /**
     * Saves the selection as a card. [headword] is the term to store — the caller passes the
     * suggested infinitive (for a conjugated verb) or the surface form; defaults to the surface form.
     * Multi-word selections are saved as [CardKind.PHRASE].
     */
    fun saveCurrentTranslation(headword: String? = null) {
        val t = _ui.value.translate ?: return
        val back = t.translation ?: return
        val id = _activeId.value.takeIf { it != DRAFT } ?: return
        val term = headword?.takeIf { it.isNotBlank() } ?: t.original
        // Capture the sentence the word was selected from, so its vocab entry can show it in context.
        val sentence = _ui.value.selectionMessageId?.let { msgId ->
            messages.value.firstOrNull { it.id == msgId }?.let { sentenceAround(it.content, t.original) }
        }
        val kind = if (term.trim().any { it.isWhitespace() }) CardKind.PHRASE else CardKind.VOCAB
        val pos = t.savePos
        // Dismiss the card and confirm immediately, before the (first-time slow) dictionary-backed save
        // runs — otherwise the popup lingers with no feedback and a second tap saves a duplicate. This
        // also guards re-entrancy: a second tap now hits the `translate == null` early-return above.
        _ui.value = _ui.value.copy(translate = null, selectionMessageId = null, savedNotice = true)
        viewModelScope.launch {
            val newId = flashcardRepo.add(
                front = term,
                back = back,
                contextSentence = sentence,
                sourceConversationId = id,
                partOfSpeech = pos,
                cardKind = kind,
            )
            // Light it up at once; the Room re-emit will rebuild an equivalent list, so no flicker.
            flashcardRepo.getCard(newId)?.let { card -> highlightProvider.targetFor(card)?.let(::addImmediateHighlight) }
        }
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
        if (end < content.length) end++ // keep the terminal punctuation
        return content.substring(start, end).trim().ifBlank { null }
    }

    fun consumeError() {
        _ui.value = _ui.value.copy(error = null)
        val id = _activeId.value
        if (id != DRAFT) generation.consumeError(id)
    }
    fun consumeSavedNotice() { _ui.value = _ui.value.copy(savedNotice = false) }

    private companion object {
        const val DRAFT = 0L
        val WORD_REGEX = Regex("[\\p{L}\\p{N}]+")
    }
}
