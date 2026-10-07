package com.copiosa.ui.vocab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import com.copiosa.data.repository.BookRepository
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.repository.ImageCandidate
import com.copiosa.data.repository.ImageSearchRepository
import com.copiosa.domain.model.Mode
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.vocab.freq.FrequencyLists
import com.copiosa.domain.vocab.freq.VocabCoverage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DetailUiState(val explaining: Boolean = false, val error: LlmError? = null)

/**
 * Where a saved word came from, as the detail screen shows and opens it. A conversation is a [Chat]
 * or a [Text] by its mode — an imported text has a reader of its own, and opening it in the chat
 * view would show the wrong screen. A routine run has nothing to go back to, so it is only named.
 */
sealed interface WordOrigin {
    data class Chat(val id: Long, val title: String) : WordOrigin
    data class Text(val id: Long, val title: String) : WordOrigin
    data class Book(val id: Long, val title: String) : WordOrigin
    data object Routine : WordOrigin
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VocabDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: FlashcardRepository,
    private val imageRepo: ImageSearchRepository,
    conversationRepo: ConversationRepository,
    bookRepo: BookRepository,
    coverage: VocabCoverage,
) : ViewModel() {

    private val cardId: Long = savedStateHandle.get<Long>("cardId") ?: 0L

    /** The card the screen was opened on. Its content is its note's, so it speaks for the word. */
    val card: StateFlow<FlashcardEntity?> =
        repo.observe(cardId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Every card of the word — one per direction studied — term-first leading, each with its own schedule. */
    val cards: StateFlow<List<FlashcardEntity>> =
        repo.observeNoteCards(cardId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Where this word sits in the bundled frequency list, or null if it isn't in it. */
    val rank: StateFlow<Int?> =
        card.map { c ->
            c?.let { FrequencyLists.forTag(it.targetLanguage)?.let { list -> coverage.rankOf(it, list) } }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Where the word was saved from, or null when it was never recorded or the source has since been deleted. */
    val origin: StateFlow<WordOrigin?> =
        card.flatMapLatest { c ->
            when {
                c == null -> flowOf(null)
                c.sourceConversationId != null -> conversationRepo.observeConversation(c.sourceConversationId)
                    .map { conv ->
                        conv?.let {
                            if (it.mode == Mode.URL_TRANSLATION) WordOrigin.Text(it.id, it.title)
                            else WordOrigin.Chat(it.id, it.title)
                        }
                    }
                c.sourceBookId != null -> bookRepo.observeBook(c.sourceBookId)
                    .map { book -> book?.let { WordOrigin.Book(it.id, it.title) } }
                c.sourceRoutineRunId != null -> flowOf(WordOrigin.Routine)
                else -> flowOf(null)
            }
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

    /** Saves edits to this word (text/POS/type/marked). The observed card flow re-emits the new values. */
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
                reviewDirection = null,
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

    /**
     * Freezes/unfreezes one direction of the word (Anki "suspend"). The other direction carries on:
     * a word can stop being asked for in production while still being recognised.
     */
    fun toggleSuspended(direction: FlashcardEntity) {
        viewModelScope.launch { repo.setSuspended(direction.id, !direction.suspended) }
    }

    /** Starts studying the word in the direction it has no card for yet, as a new card due now. */
    fun studyBothDirections() {
        val c = card.value ?: return
        viewModelScope.launch {
            // Leaves any direction the learner has frozen exactly as it is: this only adds.
            val frozen = cards.value.filter { it.suspended }
            repo.setDirections(c.id, ReviewDirection.BOTH)
            frozen.forEach { repo.setSuspended(it.id, true) }
        }
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
