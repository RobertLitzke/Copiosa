package com.copiosa.ui.texts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.generation.AudioGenerationManager
import com.copiosa.data.generation.GenerationManager
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.model.Role
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
     * The selectable-reader engine: tap-to-translate/define + save-to-vocab + saved-word highlighting +
     * passive review (of words appearing in [translation]). Saved cards are stamped with this text's id.
     */
    private val selection = ReadingSelectionController(
        scope = viewModelScope,
        conversationRepo = conversationRepo,
        flashcardRepo = flashcardRepo,
        highlightProvider = highlightProvider,
        sourceConversationId = id,
        readingText = translation,
    )
    val translate = selection.translate
    val savedNotice = selection.savedNotice
    val highlightTargets = selection.highlightTargets

    fun onSelectionTranslate(text: String, anchorY: Float, sourceText: String) =
        selection.onSelectionTranslate(text, anchorY, sourceText)

    fun saveCurrentTranslation(headword: String? = null) = selection.saveCurrentTranslation(headword)

    fun downloadDictionary() = selection.downloadDictionary()

    fun dismissTranslate() = selection.dismissTranslate()

    fun consumeSavedNotice() = selection.consumeSavedNotice()

    /** Re-runs the import/translation for this text after a failure. */
    fun retry() { generation.translate(id) }

    /** Persists the reading position so the reader resumes here next time it's opened. */
    fun saveScroll(index: Int, offset: Int) {
        viewModelScope.launch { conversationRepo.setScroll(id, index, offset) }
    }

    /** Marks the text finished once the reader reaches the bottom (drives the list coloring). */
    fun markFinishedReading() {
        if (conversation.value?.finishedReading == true) return
        viewModelScope.launch { conversationRepo.setFinishedReading(id, true) }
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

    /** Queues the translated body for on-device TTS; it renders once the voice is ready. */
    fun sendToAudio() {
        val conv = conversation.value ?: return
        val body = translation.value
        if (body.isBlank()) return
        audioGeneration.enqueue(
            text = body,
            // The list title is the target-language title; originalTitle is the source language.
            title = conv.title,
            langCode = conv.targetLanguage,
            sourceConversationId = id,
        )
    }

    /** Queues a highlighted selection (a word, phrase, or sentence) for on-device TTS. */
    fun sendSelectionToAudio(text: String) {
        val clean = text.trim()
        val lang = conversation.value?.targetLanguage ?: return
        if (clean.isBlank()) return
        audioGeneration.enqueue(
            text = clean,
            title = clean.take(60),
            langCode = lang,
            sourceConversationId = id,
        )
    }
}
