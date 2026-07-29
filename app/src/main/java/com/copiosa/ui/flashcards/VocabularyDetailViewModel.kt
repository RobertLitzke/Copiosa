package com.copiosa.ui.flashcards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.repository.ImageCandidate
import com.copiosa.data.repository.ImageSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(val explaining: Boolean = false, val error: LlmError? = null)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VocabularyDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: FlashcardRepository,
    private val imageRepo: ImageSearchRepository,
    conversationRepo: ConversationRepository,
) : ViewModel() {

    private val cardId: Long = savedStateHandle.get<Long>("cardId") ?: 0L

    val card: StateFlow<FlashcardEntity?> =
        repo.observe(cardId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The source conversation, or null when it was never set or has since been deleted. */
    val sourceConversation: StateFlow<ConversationEntity?> =
        card.flatMapLatest { c ->
            val sourceId = c?.sourceConversationId
            if (sourceId == null) flowOf(null) else conversationRepo.observeConversation(sourceId)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _ui = MutableStateFlow(DetailUiState())
    val ui: StateFlow<DetailUiState> = _ui.asStateFlow()

    fun explain() {
        val c = card.value ?: return
        if (_ui.value.explaining) return
        _ui.value = DetailUiState(explaining = true)
        viewModelScope.launch {
            try {
                repo.explain(c)
                _ui.value = DetailUiState()
            } catch (e: LlmException) {
                _ui.value = DetailUiState(error = e.error)
            } catch (e: Exception) {
                _ui.value = DetailUiState(error = LlmError.Unknown(e.message ?: "error"))
            }
        }
    }

    /** Saves edits to this card (text/POS/type/direction/marked). The observed card flow re-emits the new values. */
    fun updateCard(edits: CardEdits) {
        val c = card.value ?: return
        if (edits.front.isBlank()) return
        viewModelScope.launch {
            repo.updateCard(
                c.id,
                edits.front,
                edits.back,
                edits.altForms,
                edits.partOfSpeech,
                edits.cardKind,
                edits.reviewDirection,
                edits.contextSentence,
                edits.gender,
                edits.country,
                edits.usageNote,
            )
            repo.setMarked(c.id, edits.marked)
        }
    }

    /** Searches candidate images for the editor's picker. */
    suspend fun searchImages(query: String): List<ImageCandidate> = imageRepo.search(query)

    /** Attaches a chosen image to this card. The observed card flow re-emits with the new image. */
    suspend fun attachImage(candidate: ImageCandidate): String {
        val c = card.value ?: error("No card")
        return imageRepo.attachImage(c.id, candidate)
    }

    /** Clears this card's image. */
    fun removeImage() {
        val c = card.value ?: return
        viewModelScope.launch { repo.setImageFile(c.id, null) }
    }

    /** Freezes/unfreezes this card (Anki "suspend"): drops it from review + the conversation vocab pool. */
    fun toggleSuspended() {
        val c = card.value ?: return
        viewModelScope.launch { repo.setSuspended(c.id, !c.suspended) }
    }

    /** Toggles this card's "marked" flag (Anki "marked"). The observed card flow re-emits the new value. */
    fun toggleMarked() {
        val c = card.value ?: return
        viewModelScope.launch { repo.setMarked(c.id, !c.marked) }
    }

    fun delete(onDeleted: () -> Unit) {
        val c = card.value ?: return
        viewModelScope.launch {
            repo.delete(c.id)
            onDeleted()
        }
    }

    fun consumeError() { _ui.value = _ui.value.copy(error = null) }
}
