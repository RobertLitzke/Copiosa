package com.copiosa.ui.flashcards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.repository.ImageCandidate
import com.copiosa.data.repository.ImageSearchRepository
import com.copiosa.domain.model.ReviewRating
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A bounded review session: a fixed snapshot of cards plus the user's position in it. */
data class ReviewSessionState(
    val loading: Boolean = true,
    val current: FlashcardEntity? = null,
    /** 1-based position of the current card, for the "n / total" counter. */
    val position: Int = 0,
    val total: Int = 0,
    /** Projected next-due epoch-ms per rating for [current], shown under the rating buttons. */
    val previews: Map<ReviewRating, Long> = emptyMap(),
)

/**
 * Drives one review session of up to `limit` cards. The session is built once from the repository
 * (due cards, with new words interleaved, studying ahead if short) and held as a fixed list, so the
 * counter is stable and grading a card simply advances the index. Each grade persists the card's new
 * schedule + a review-log entry via [FlashcardRepository.review].
 */
@HiltViewModel
class ReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: FlashcardRepository,
    private val imageRepo: ImageSearchRepository,
) : ViewModel() {

    /** Nav-arg limit for the standalone Léxico review; a [start] override (the routine) wins over it. */
    private val navLimit: Int = savedStateHandle.get<Int>("limit") ?: DEFAULT_LIMIT

    private val session = MutableStateFlow<List<FlashcardEntity>>(emptyList())
    private val index = MutableStateFlow(0)
    private val loading = MutableStateFlow(true)
    private var started = false

    val state: StateFlow<ReviewSessionState> =
        combine(session, index, loading) { cards, i, isLoading ->
            val current = cards.getOrNull(i)
            ReviewSessionState(
                loading = isLoading,
                current = current,
                position = (i + 1).coerceAtMost(cards.size),
                total = cards.size,
                // Recomputed once per card advance (pure, no persistence) for the button hints.
                previews = current?.let { repo.previewDueDates(it) } ?: emptyMap(),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewSessionState())

    /**
     * Builds the (fixed) session once. The standalone screen calls this with no argument (using the
     * nav-arg limit); an embedded host (a routine's flashcard step) passes its configured card count.
     */
    fun start(limitOverride: Int? = null) {
        if (started) return
        started = true
        viewModelScope.launch {
            session.value = repo.buildReviewSession(limitOverride ?: navLimit)
            loading.value = false
        }
    }

    /**
     * Grades the current card (persisting its updated schedule from the freshest state) and advances.
     * A card marked "Again" is re-appended to the end of the session so it comes back later this
     * session — repeating until it's graded better, the way Anki re-shows lapsed cards.
     */
    fun rate(rating: ReviewRating) {
        val card = session.value.getOrNull(index.value) ?: return
        viewModelScope.launch {
            val fresh = repo.getCard(card.id) ?: card
            repo.review(fresh, rating)
        }
        if (rating == ReviewRating.AGAIN) {
            session.value = session.value + card
        }
        index.value = index.value + 1
    }

    /** Toggles the current card's "marked" flag, persisting it and updating the in-session copy. */
    fun toggleMark() {
        val card = session.value.getOrNull(index.value) ?: return
        val marked = !card.marked
        viewModelScope.launch { repo.setMarked(card.id, marked) }
        session.value = session.value.map { if (it.id == card.id) it.copy(marked = marked) else it }
    }

    /** Saves edits to the current card (text/POS/type/direction/marked), updating both store and in-session copy. */
    fun editCard(edits: CardEdits) {
        val card = session.value.getOrNull(index.value) ?: return
        val f = edits.front.trim()
        if (f.isEmpty()) return
        val b = edits.back.trim()
        val context = edits.contextSentence?.trim()?.ifBlank { null }
        viewModelScope.launch {
            repo.updateCard(card.id, f, b, edits.partOfSpeech, edits.cardKind, edits.reviewDirection, context)
            repo.setMarked(card.id, edits.marked)
        }
        session.value = session.value.map {
            if (it.id == card.id) {
                it.copy(
                    front = f,
                    back = b,
                    contextSentence = context,
                    partOfSpeech = edits.partOfSpeech,
                    cardKind = edits.cardKind,
                    reviewDirection = edits.reviewDirection,
                    marked = edits.marked,
                )
            } else {
                it
            }
        }
    }

    /** Searches candidate images for the editor's picker. */
    suspend fun searchImages(query: String): List<ImageCandidate> = imageRepo.search(query)

    /** Attaches a chosen image to the current card and reflects it in the in-session copy. */
    suspend fun attachImage(candidate: ImageCandidate): String {
        val card = session.value.getOrNull(index.value) ?: error("No current card")
        val name = imageRepo.attachImage(card.id, candidate)
        session.value = session.value.map { if (it.id == card.id) it.copy(imageFile = name) else it }
        return name
    }

    /** Clears the current card's image. */
    fun removeImage() {
        val card = session.value.getOrNull(index.value) ?: return
        viewModelScope.launch { repo.setImageFile(card.id, null) }
        session.value = session.value.map { if (it.id == card.id) it.copy(imageFile = null) else it }
    }

    companion object {
        private const val DEFAULT_LIMIT = 20
    }
}
