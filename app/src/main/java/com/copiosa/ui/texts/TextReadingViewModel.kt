package com.copiosa.ui.texts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.generation.AudioGenerationManager
import com.copiosa.data.generation.GenerationManager
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.model.Role
import com.copiosa.domain.reading.ReadMode
import com.copiosa.domain.vocab.WordSource
import com.copiosa.ui.components.ReadingSelectionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the reader is showing: an in-flight import/translation, the finished text, or a failure. */
sealed interface ReadingState {
    data object Importing : ReadingState
    data class Translating(val done: Int, val total: Int) : ReadingState
    data object Ready : ReadingState
    data class Failed(val error: LlmError?) : ReadingState
}

/**
 * Backs the text reading screen for one imported text (a URL_TRANSLATION conversation). It surfaces
 * the translated body (the first assistant message), the stored original, the import/translation
 * status, and the same tap-to-translate + save-to-vocabulary affordance used in chats — the selection
 * machinery itself is the shared [ReadingSelectionController], so a routine's reading step behaves
 * identically.
 */
@HiltViewModel
class TextReadingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val conversationRepo: ConversationRepository,
    private val flashcardRepo: FlashcardRepository,
    private val generation: GenerationManager,
    private val audioGeneration: AudioGenerationManager,
    private val ttsModelStore: SupertonicModelStore,
    private val highlightProvider: HighlightTargetProvider,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    private val id: Long = savedStateHandle.get<Long>("conversationId") ?: 0L

    val conversation: StateFlow<ConversationEntity?> =
        conversationRepo.observeConversation(id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether this text is starred, for the reader's overflow menu toggle. */
    val starred: StateFlow<Boolean> =
        conversation.map { it?.starred == true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Saved reading position to restore on open: (first-visible block index, pixel offset). */
    val savedScroll: StateFlow<Pair<Int, Int>?> =
        conversation.map { c -> c?.let { it.scrollIndex to it.scrollOffset } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The language version this text was last read in. Null only means the row hasn't loaded yet —
     * the reader restores on the first non-null value and leaves it alone after that, so a tap that
     * lands before the load can't be overwritten by it.
     */
    val savedMode: StateFlow<ReadMode?> =
        conversation.map { it?.readMode }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val messages = conversationRepo.observeMessages(id)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The translated body so far (grows as chunks land). */
    val translation: StateFlow<String> =
        messages.map { msgs -> msgs.firstOrNull { it.role == Role.ASSISTANT }?.content.orEmpty() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val state: StateFlow<ReadingState> =
        combine(messages, conversation, generation.active, generation.errors, generation.progress) { msgs, conv, active, errors, progress ->
            val content = msgs.firstOrNull { it.role == Role.ASSISTANT }?.content.orEmpty()
            // Completeness is read from persisted chunk counts, so it stays correct even after the
            // in-memory error is gone (e.g. across an app restart) — a stalled partial isn't "Ready".
            val complete = conv != null && conv.totalChunks > 0 && conv.translatedChunks >= conv.totalChunks
            when {
                errors[id] != null -> ReadingState.Failed(errors[id])
                active[id] != null && progress[id] != null ->
                    progress.getValue(id).let { (d, t) -> ReadingState.Translating(d, t) }
                active[id] != null -> ReadingState.Importing
                complete -> ReadingState.Ready
                // Not finished and nothing in flight: a stalled partial (Continue) or a clean failure
                // (Retry); the screen tells them apart by whether any translation text exists.
                else -> ReadingState.Failed(null)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadingState.Importing)

    /**
     * Whether due vocabulary is tinted in this text: its own override if it has one, else the
     * language's setting. Also drives the overflow menu's check mark.
     */
    val highlightWords: StateFlow<Boolean> =
        combine(conversation, settingsRepo.settings) { c, s -> c?.highlightWords ?: s.highlightSavedWords }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /**
     * The selectable-reader engine: tap-to-translate/define + save-to-vocab + saved-word highlighting +
     * passive review (of words appearing in [translation]). Saved cards are stamped with this text's id.
     * The screen drives it directly rather than through passthroughs here.
     */
    val readingSelection = ReadingSelectionController(
        scope = viewModelScope,
        conversationRepo = conversationRepo,
        flashcardRepo = flashcardRepo,
        highlightProvider = highlightProvider,
        source = { WordSource.conversation(id) },
        readingText = translation,
        highlightEnabled = highlightWords,
    )

    /** The words saved while reading this text, newest first. */
    val savedWords: StateFlow<List<FlashcardEntity>> = flashcardRepo.observeSavedFrom(WordSource.conversation(id))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Re-runs the import/translation for this text after a failure. */
    fun retry() { generation.translate(id) }

    /** Persists the reading position so the reader resumes here next time it's opened. */
    fun saveScroll(index: Int, offset: Int) {
        viewModelScope.launch { conversationRepo.setScroll(id, index, offset) }
    }

    /** Persists the language version being read, so reopening the text resumes in it. */
    fun saveReadMode(mode: ReadMode) {
        viewModelScope.launch { conversationRepo.setReadMode(id, mode) }
    }

    /** Marks the text finished once the reader reaches the bottom (drives the list coloring). */
    fun markFinishedReading() {
        if (conversation.value?.finishedReading == true) return
        viewModelScope.launch { conversationRepo.setFinishedReading(id, true) }
    }

    /**
     * Flips highlighting for this text, writing an explicit override: having turned it off here, the
     * learner would not expect a later change to the language default to switch it back on.
     */
    fun toggleHighlightWords() {
        val next = !highlightWords.value
        viewModelScope.launch { conversationRepo.setHighlightWords(id, next) }
    }

    /** Toggles this text's starred flag (overflow menu). */
    fun toggleStar() {
        val next = !(conversation.value?.starred ?: false)
        viewModelScope.launch { conversationRepo.setStarred(id, next) }
    }

    /** Deletes this text; the screen pops back to the list afterwards. */
    fun delete() {
        viewModelScope.launch { conversationRepo.delete(id) }
    }

    /** Whether the on-device voice is installed, so the screen can prompt to download it if not. */
    fun isTtsReady(): Boolean = ttsModelStore.isReady()

    /** Starts the (opt-in) voice download — only ever called from the "Send to audio" prompt. */
    fun startTtsDownload() = ttsModelStore.download()

    /**
     * Queues the translated body for on-device TTS; it renders once the voice is ready. False when
     * there was nothing to queue (the translation has not arrived), so the screen can say so.
     */
    fun sendToAudio(): Boolean {
        val conv = conversation.value ?: return false
        val body = translation.value
        if (body.isBlank()) return false
        audioGeneration.enqueue(
            text = body,
            // The list title is the target-language title; originalTitle is the source language.
            title = conv.title,
            langCode = conv.targetLanguage,
            sourceConversationId = id,
        )
        return true
    }

    /** Queues a highlighted selection (a word, phrase, or sentence) for on-device TTS; false as above. */
    fun sendSelectionToAudio(text: String): Boolean {
        val clean = text.trim()
        val lang = conversation.value?.targetLanguage ?: return false
        if (clean.isBlank()) return false
        audioGeneration.enqueue(
            text = clean,
            title = clean.take(60),
            langCode = lang,
            sourceConversationId = id,
        )
        return true
    }
}
