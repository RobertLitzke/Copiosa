package com.copiosa.ui.vocab

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.repository.ImageCandidate
import com.copiosa.data.repository.ImageSearchRepository
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.srs.Leech
import com.copiosa.domain.srs.SpacedRepetitionScheduler
import com.copiosa.domain.srs.isLeech
import com.copiosa.domain.vocab.AltForms
import com.copiosa.domain.vocab.BackNoteSplitter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Where each rating would send [current], shown under the rating buttons. */
    val previews: Map<ReviewRating, SpacedRepetitionScheduler.Preview> = emptyMap(),
    /** Whether a grade this session can still be taken back (drives the header's undo button). */
    val canUndo: Boolean = false,
    /**
     * True when the session was built with nothing in it at all. It is what tells "there was nothing
     * to study" apart from "you have worked through everything this session held" — the second says
     * nothing about whether more cards are due, and must not claim it does.
     */
    val nothingToStudy: Boolean = false,
    /** What was graded so far, for the end-of-session recap. Null until the first grade. */
    val summary: ReviewSessionSummary? = null,
    /**
     * How many cards a follow-on session would hold: what is still due, capped at this session's
     * size. Zero when there is nothing due (or for a frequency drill, whose pool isn't the due
     * queue) — the completion screen then offers no "Review N more".
     */
    val nextSessionSize: Int = 0,
    /**
     * Every size the completion screen offers, ascending: [nextSessionSize] plus a shorter or longer
     * alternative or two — see [followOnSessionSizes]. Empty exactly when [nextSessionSize] is zero.
     */
    val nextSessionSizes: List<Int> = emptyList(),
)

/**
 * A word that has just been forgotten often enough to be worth a second look. Shown once, above the
 * next card, and gone at the next grade — it points at the word's own screen and asks for nothing.
 */
data class LeechNotice(val cardId: Long, val word: String, val lapses: Int)

/**
 * The follow-on sessions worth offering once one of [sessionSize] cards ends with [due] still
 * waiting: the same again (or everything left, if that is less), plus up to two alternatives from
 * the usual round sizes — a hundred-card session should be able to end with twenty more rather
 * than only another hundred.
 *
 * Shorter alternatives come first, since topping up is the common wish; longer ones only fill
 * whatever room is left, and never past what is due. A step has to differ from the repeat size by
 * a quarter or more to earn a button, so 23 cards left offers "10" and "23", not "20" and "23".
 */
internal fun followOnSessionSizes(due: Int, sessionSize: Int): List<Int> {
    val same = minOf(due, sessionSize)
    if (same <= 0) return emptyList()
    val alternatives = MAX_FOLLOW_ON_OPTIONS - 1
    val shorter = FOLLOW_ON_STEPS.filter { it * 4 <= same * 3 }.takeLast(alternatives)
    val longer = FOLLOW_ON_STEPS.filter { it <= due && it * 3 >= same * 4 }.take(alternatives - shorter.size)
    return shorter + same + longer
}

/** The completion screen lays its options out on one row; more than three would not fit a phone. */
private const val MAX_FOLLOW_ON_OPTIONS = 3

private val FOLLOW_ON_STEPS = listOf(10, 20, 50, 100)

/**
 * How well-established a card was on the way in, by the interval it was already carrying. The
 * scheduler has no confidence figure, so the interval stands in for one: a card you last saw
 * yesterday is one you barely know, a month-old one is settled.
 */
enum class IntervalBucket {
    /** Never reviewed before. */
    NEW,
    UNDER_DAY,
    UNDER_WEEK,
    UNDER_MONTH,
    MONTH_PLUS,
}

/**
 * End-of-session numbers. [grades] counts every button press (an "Again" card graded twice counts
 * twice); [cards] and everything bucketed counts each distinct card once, at its first showing —
 * failing a card shouldn't also inflate the pile of cards you were asked to recall.
 */
data class ReviewSessionSummary(
    val cards: Int,
    val grades: Int,
    val ratingCounts: Map<ReviewRating, Int>,
    val intervalCounts: Map<IntervalBucket, Int>,
    /** Distinct cards whose *first* grade this session was anything but "Again". */
    val firstTryCorrect: Int,
    /** Cards graded "Again" at any point this session, in the order first missed. */
    val missedCards: List<Long> = emptyList(),
) {
    val firstTryPercent: Int
        get() = if (cards == 0) 0 else Math.round(firstTryCorrect * 100f / cards)
}

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

    /**
     * Frequency-drill scope: 0 for an ordinary session, otherwise the rank the session is limited to.
     * Set by the Routines tab's frequent-word card; a [start] override wins over it.
     */
    private val navFreqTop: Int = savedStateHandle.get<Int>("freqTop") ?: 0

    private val session = MutableStateFlow<List<FlashcardEntity>>(emptyList())
    private val index = MutableStateFlow(0)
    private val loading = MutableStateFlow(true)
    // Latched at build time rather than derived from the live session, which can also empty out by
    // deleting the last card mid-review — that is a finished session, not an empty one.
    private val builtEmpty = MutableStateFlow(false)
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

    /** One grade as the recap sees it: what was pressed, and what the card looked like going in. */
    private data class GradeRecord(
        val cardId: Long,
        val rating: ReviewRating,
        val bucket: IntervalBucket,
        /** False for a card coming back around after an "Again", so distinct-card stats count it once. */
        val firstShow: Boolean,
    )

    // Every grade taken, in order, kept for the end-of-session recap. Unlike [undoStack] this survives
    // an edit/delete — those invalidate taking a grade back, not the fact that it happened.
    private val results = MutableStateFlow<List<GradeRecord>>(emptyList())

    private val _leechNotice = MutableStateFlow<LeechNotice?>(null)

    /** Set when the last grade tipped a card into being a leech (or further in); see [Leech.crossedInto]. */
    val leechNotice: StateFlow<LeechNotice?> = _leechNotice.asStateFlow()

    fun dismissLeechNotice() { _leechNotice.value = null }

    val state: StateFlow<ReviewSessionState> =
        combine(session, index, loading, undoDepth, builtEmpty) { cards, i, isLoading, undoable, empty ->
            val current = cards.getOrNull(i)
            ReviewSessionState(
                loading = isLoading,
                current = current,
                position = (i + 1).coerceAtMost(cards.size),
                total = cards.size,
                // Recomputed once per card advance (pure, no persistence) for the button hints.
                previews = current?.let { repo.previewDueDates(it) } ?: emptyMap(),
                canUndo = undoable > 0,
                nothingToStudy = empty,
            )
        }
            // Folded in separately because a grade lands in [results] from a coroutine that finishes
            // *after* the index has already advanced — combining it here is what makes the last grade
            // of the session part of the recap the completion screen reads.
            .combine(results) { s, graded -> s.copy(summary = summarize(graded)) }
            // Live rather than latched: a card graded this session leaves the due queue, so by the
            // completion screen the count is exactly what a follow-on session would hold.
            .combine(repo.observeDueCount()) { s, due ->
                val sizes = if (navFreqTop > 0) emptyList() else followOnSessionSizes(due, sessionCap)
                s.copy(nextSessionSize = if (sizes.isEmpty()) 0 else minOf(due, sessionCap), nextSessionSizes = sizes)
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewSessionState())

    /**
     * The size the learner chose on the way in, which the follow-on options are built around. It
     * stays put across follow-on sessions, so topping up with twenty after a hundred still offers
     * the hundred afterwards.
     */
    private val sessionCap: Int = navLimit.takeIf { it > 0 } ?: DEFAULT_LIMIT

    private fun summarize(graded: List<GradeRecord>): ReviewSessionSummary? {
        if (graded.isEmpty()) return null
        val distinct = graded.filter { it.firstShow }
        return ReviewSessionSummary(
            cards = distinct.size,
            grades = graded.size,
            ratingCounts = graded.groupingBy { it.rating }.eachCount(),
            intervalCounts = distinct.groupingBy { it.bucket }.eachCount(),
            firstTryCorrect = distinct.count { it.rating != ReviewRating.AGAIN },
            missedCards = graded.filter { it.rating == ReviewRating.AGAIN }.map { it.cardId }.distinct(),
        )
    }

    private fun bucketOf(card: FlashcardEntity): IntervalBucket = when {
        card.state == FlashcardState.NEW -> IntervalBucket.NEW
        card.intervalDays < 1.0 -> IntervalBucket.UNDER_DAY
        card.intervalDays < 7.0 -> IntervalBucket.UNDER_WEEK
        card.intervalDays < 30.0 -> IntervalBucket.UNDER_MONTH
        else -> IntervalBucket.MONTH_PLUS
    }

    /**
     * Builds the (fixed) session once. The standalone screen calls this with no argument (using the
     * nav-arg limit); an embedded host (a routine's flashcard step) passes its configured card count,
     * and the routine's frequent-word step additionally passes [freqTopOverride] to narrow the pool
     * to words ranked at or above it. Null falls back to the nav arg, which is zero — an ordinary
     * session — for every entry point but the Routines tab's frequent-word card.
     */
    fun start(limitOverride: Int? = null, freqTopOverride: Int? = null) {
        if (started) return
        started = true
        viewModelScope.launch {
            val cards = repo.buildReviewSession(limitOverride ?: navLimit, freqTopOverride ?: navFreqTop)
            builtEmpty.value = cards.isEmpty()
            session.value = cards
            loading.value = false
        }
    }

    /**
     * Leaves the recap and builds a fresh session of [size] cards from what is still due. Only a
     * size the completion screen is offering is accepted, and those never run past the due count —
     * so "Review 7 more" holds those seven and doesn't pad with cards that aren't due yet.
     * Everything session-scoped (undo, recap, position) starts over.
     */
    fun startAnother(size: Int) {
        if (size !in state.value.nextSessionSizes) return
        clearUndo()
        results.value = emptyList()
        session.value = emptyList()
        index.value = 0
        builtEmpty.value = false
        loading.value = true
        started = false
        start(limitOverride = size)
    }

    /**
     * Grades the current card (persisting its updated schedule from the freshest state) and advances.
     * A card marked "Again" is re-appended to the end of the session so it comes back later this
     * session — repeating until it's graded better, the way Anki re-shows lapsed cards.
     */
    fun rate(rating: ReviewRating) {
        val i = index.value
        val card = session.value.getOrNull(i) ?: return
        // A notice is about the grade before this one; the next grade is the learner moving on.
        _leechNotice.value = null
        viewModelScope.launch {
            val fresh = repo.getCard(card.id) ?: card
            val logId = repo.review(fresh, rating)
            // Pushed here (not before the launch) so the entry carries the real log id; launches on
            // viewModelScope start in order, so undo() can't observe a half-pushed grade.
            undoStack.addLast(UndoEntry(i, fresh, logId, wasAgain = rating == ReviewRating.AGAIN))
            undoDepth.value = undoStack.size
            results.value = results.value + GradeRecord(
                cardId = fresh.id,
                rating = rating,
                bucket = bucketOf(fresh),
                firstShow = results.value.none { it.cardId == fresh.id },
            )
            // The session holds pre-grade snapshots, so the copy an "Again" just re-queued still
            // carries the schedule the card had *before* it lapsed. Refresh the copies still ahead of
            // us or, when the card comes back around, its buttons would preview weeks out for a card
            // that relearning will really bring back in minutes.
            repo.getCard(fresh.id)?.let { graded ->
                session.value = session.value.mapIndexed { idx, c ->
                    if (idx > i && c.id == fresh.id) graded else c
                }
                if (graded.isLeech && Leech.crossedInto(fresh.lapses, graded.lapses)) {
                    _leechNotice.value = LeechNotice(graded.id, graded.front, graded.lapses)
                }
            }
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
        _leechNotice.value = null
        viewModelScope.launch {
            val entry = undoStack.removeLastOrNull() ?: return@launch
            undoDepth.value = undoStack.size
            // The stack is only ever as deep as the tail of [results], so the taken-back grade is the
            // last record — drop it so the recap forgets it too.
            results.value = results.value.dropLast(1)
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

    /** Saves edits to the current card's note (text/POS/type/gender/usage-note/marked), updating both store and in-session copy. */
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
                card.id, f, b, edits.altForms, edits.partOfSpeech, edits.cardKind, reviewDirection = null,
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
                    marked = edits.marked,
                )
            } else {
                it
            }
        }
    }

    /**
     * Deletes the current card's note mid-review — the word, in both directions. Removes every copy
     * from the session (an "Again" grade re-appends a duplicate) and persists the delete. The index
     * stays put, so the next card slides in.
     */
    fun deleteCard() {
        val card = session.value.getOrNull(index.value) ?: return
        clearUndo()
        viewModelScope.launch { repo.delete(card.id) }
        session.value = session.value.filter { it.id != card.id }
        if (index.value > session.value.size) index.value = session.value.size
    }

    /**
     * Re-reads the current card from the database. The session holds snapshots, and the card's detail
     * screen — reachable from the reveal — can edit it, explain it or delete it, so coming back
     * would otherwise show the card as it was before. A card deleted there leaves the session, the
     * same way deleting it from the review does.
     */
    fun refreshCurrent() {
        val card = session.value.getOrNull(index.value) ?: return
        viewModelScope.launch {
            val fresh = repo.getCard(card.id)
            if (fresh == null) {
                session.value = session.value.filter { it.id != card.id }
                if (index.value > session.value.size) index.value = session.value.size
            } else {
                session.value = session.value.map { if (it.id == card.id) fresh else it }
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
