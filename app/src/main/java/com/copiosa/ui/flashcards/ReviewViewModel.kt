package com.copiosa.ui.flashcards

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.repository.ImageCandidate
import com.copiosa.data.repository.ImageSearchRepository
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.vocab.AltForms
import com.copiosa.domain.vocab.BackNoteSplitter
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
    /** Whether a grade this session can still be taken back (drives the header's undo button). */
    val canUndo: Boolean = false,
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

    /** One taken-back-able grade: where it happened, the pre-review card, and its log row. */
    private data class UndoEntry(
        val index: Int,
        val card: FlashcardEntity,
        val reviewLogId: Long,
        val wasAgain: Boolean,
    )

    // LIFO of grades this session. Cleared when an edit/delete would make restoring a stale snapshot
    // wrong. Pushed from the grading coroutine (so the log id is known); popped by [undo].
    private val undoStack = ArrayDeque<UndoEntry>()
    private val undoDepth = MutableStateFlow(0)

    val state: StateFlow<ReviewSessionState> =
        combine(session, index, loading, undoDepth) { cards, i, isLoading, undoable ->
            val current = cards.getOrNull(i)
            ReviewSessionState(
                loading = isLoading,
                current = current,
                position = (i + 1).coerceAtMost(cards.size),
                total = cards.size,
                // Recomputed once per card advance (pure, no persistence) for the button hints.
                previews = current?.let { repo.previewDueDates(it) } ?: emptyMap(),
                canUndo = undoable > 0,
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
        val i = index.value
        val card = session.value.getOrNull(i) ?: return
        viewModelScope.launch {
            val fresh = repo.getCard(card.id) ?: card
            val logId = repo.review(fresh, rating)
            // Pushed here (not before the launch) so the entry carries the real log id; launches on
            // viewModelScope start in order, so undo() can't observe a half-pushed grade.
            undoStack.addLast(UndoEntry(i, fresh, logId, wasAgain = rating == ReviewRating.AGAIN))
            undoDepth.value = undoStack.size
        }
        if (rating == ReviewRating.AGAIN) {
            session.value = session.value + card
        }
        index.value = index.value + 1
    }

    /**
     * Takes back the most recent grade: restores the card's pre-review schedule, deletes its log
     * entry, removes an "Again" re-queue duplicate, and steps the session back to that card.
     */
    fun undo() {
        viewModelScope.launch {
            val entry = undoStack.removeLastOrNull() ?: return@launch
            undoDepth.value = undoStack.size
            repo.undoReview(entry.card, entry.reviewLogId)
            // An "Again" grade appended its card to the session tail; LIFO order means that append is
            // still the last element (edits/deletes clear the stack, so nothing else moved it).
            if (entry.wasAgain) session.value = session.value.dropLast(1)
            session.value = session.value.mapIndexed { idx, c -> if (idx == entry.index) entry.card else c }
            index.value = entry.index
        }
    }

    /** Grades already taken can't be safely restored over an edited/deleted card — drop them. */
    private fun clearUndo() {
        undoStack.clear()
        undoDepth.value = 0
    }

    /** Toggles the current card's "marked" flag, persisting it and updating the in-session copy. */
    fun toggleMark() {
        val card = session.value.getOrNull(index.value) ?: return
        val marked = !card.marked
        viewModelScope.launch { repo.setMarked(card.id, marked) }
        session.value = session.value.map { if (it.id == card.id) it.copy(marked = marked) else it }
    }

    /** Saves edits to the current card (text/POS/type/direction/gender/usage-note/marked), updating both store and in-session copy. */
    fun editCard(edits: CardEdits) {
        val card = session.value.getOrNull(index.value) ?: return
        val f = edits.front.trim()
        if (f.isEmpty()) return
        val b = edits.back.trim()
        val context = edits.contextSentence?.trim()?.ifBlank { null }
        val gender = edits.gender?.trim()?.uppercase()?.ifBlank { null }
        val country = edits.country?.trim()?.ifBlank { null }
        val typedNote = edits.usageNote?.trim()?.ifBlank { null }
        val altForms = AltForms.normalize(edits.altForms)
        // Mirror the repository's split so the in-session card face matches what was persisted.
        val (back, note) = if (typedNote == null) BackNoteSplitter.split(b) else b to typedNote
        clearUndo()
        viewModelScope.launch {
            repo.updateCard(
                card.id, f, b, edits.altForms, edits.partOfSpeech, edits.cardKind, edits.reviewDirection,
                context, gender, country, typedNote,
            )
            repo.setMarked(card.id, edits.marked)
        }
        session.value = session.value.map {
            if (it.id == card.id) {
                it.copy(
                    front = f,
                    back = back,
                    altForms = altForms,
                    contextSentence = context,
                    gender = gender,
                    country = country,
                    usageNote = note,
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

    /**
     * Deletes the current card mid-review: removes every copy from the session (an "Again" grade
     * re-appends a duplicate) and persists the delete. The index stays put, so the next card slides in.
     */
    fun deleteCard() {
        val card = session.value.getOrNull(index.value) ?: return
        clearUndo()
        viewModelScope.launch { repo.delete(card.id) }
        session.value = session.value.filter { it.id != card.id }
        if (index.value > session.value.size) index.value = session.value.size
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
