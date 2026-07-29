package com.copiosa.ui.conversation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.generation.AudioGenerationManager
import com.copiosa.data.generation.GenPhase
import com.copiosa.data.generation.GenerationManager
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.local.MessageEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.prompt.StructuredReplyParser
import com.copiosa.ui.components.ReadingSelectionController
import dagger.hilt.android.lifecycle.HiltViewModel
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
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ConversationUiState(
    val input: String = "",
    val sending: Boolean = false,
    val error: LlmError? = null,
    /** Id of the message whose selection is currently highlighted, so only one shows at a time. */
    val selectionMessageId: Long? = null,
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

    /**
     * The selectable-reader engine shared with the Texts reader and routine reading steps:
     * tap-to-translate/define + save-to-vocab + saved-word highlighting + passive review of saved
     * words appearing in assistant replies. Saved cards are stamped with this chat's id (null while
     * still a draft — though a draft has no replies to select from).
     */
    private val selection = ReadingSelectionController(
        scope = viewModelScope,
        conversationRepo = conversationRepo,
        flashcardRepo = flashcardRepo,
        highlightProvider = highlightProvider,
        sourceConversationId = { _activeId.value.takeIf { it != DRAFT } },
        readingText = messages.map { msgs ->
            msgs.filter { it.role == Role.ASSISTANT }.joinToString("\n") { it.content }
        },
    )
    val translate = selection.translate
    val savedNotice = selection.savedNotice
    val highlightTargets = selection.highlightTargets

    /**
     * Suggested next subjects, shown as tappable chips below the latest reply. Tracks the freshest
     * reply that actually carries topics (see [observeTopics]): "Explain" lookups omit them, so the
     * chips stay put on the last main-line suggestions across an explanation and across navigation.
     */
    private val _nextTopics = MutableStateFlow<List<String>>(emptyList())
    val nextTopics: StateFlow<List<String>> = _nextTopics.asStateFlow()

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
        selection.dismissTranslate()
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
        _ui.value = _ui.value.copy(error = null, selectionMessageId = null)
        selection.dismissTranslate()
        generation.sendUserTurn(id, text, isExplanation = true)
    }

    /**
     * Looks up the selected phrase on-device and shows it in the translation card, remembering which
     * message the selection lives in so only that bubble renders it. The engine is the shared
     * [ReadingSelectionController]; the message body is passed along so the saved card keeps the
     * sentence the word appeared in.
     */
    fun onSelectionTranslate(messageId: Long, text: String, anchorY: Float) {
        val sourceText = messages.value.firstOrNull { it.id == messageId }?.content.orEmpty()
        _ui.value = _ui.value.copy(selectionMessageId = messageId)
        selection.onSelectionTranslate(text, anchorY, sourceText)
    }

    /** Triggered by the card's "download dictionary" button; fetches the pack then re-runs lookup. */
    fun downloadDictionary() = selection.downloadDictionary()

    fun dismissTranslate() {
        selection.dismissTranslate()
        _ui.value = _ui.value.copy(selectionMessageId = null)
    }

    /**
     * Saves the selection as a card. [headword] is the term to store — the caller passes the
     * suggested infinitive (for a conjugated verb) or the surface form; defaults to the surface form.
     * Multi-word selections are saved as phrase cards.
     */
    fun saveCurrentTranslation(headword: String? = null) {
        selection.saveCurrentTranslation(headword)
        _ui.value = _ui.value.copy(selectionMessageId = null)
    }

    /** Which dictionary entry the card's save should use, when the word resolved to several. */
    fun selectSaveEntry(lemma: String, pos: String?) = selection.selectSaveEntry(lemma, pos)

    fun consumeError() {
        _ui.value = _ui.value.copy(error = null)
        val id = _activeId.value
        if (id != DRAFT) generation.consumeError(id)
    }
    fun consumeSavedNotice() = selection.consumeSavedNotice()

    private companion object {
        const val DRAFT = 0L
    }
}
