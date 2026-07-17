package com.copiosa.domain.srs

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.ReviewLogEntity
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewRating
import io.github.openspacedrepetition.Card
import io.github.openspacedrepetition.Rating
import io.github.openspacedrepetition.Scheduler
import io.github.openspacedrepetition.State
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FSRS-backed scheduler. The memory-state math is delegated to the FSRS engine — the same algorithm
 * modern Anki uses — so a card's stability/difficulty/due round-trip cleanly to and from Anki.
 *
 * Pure given (card, rating, now): the engine is constructed once and [schedule] only reads it, so the
 * result is deterministic (FSRS fuzzing uses a fixed seed). Returns the updated card plus the
 * [ReviewLogEntity] for this grade; the caller persists both.
 */
@Singleton
class SpacedRepetitionScheduler @Inject constructor() {

    /** Default FSRS config: retention 0.9, learning steps 1m/10m, fuzzing on (fixed seed). */
    private val fsrs: Scheduler = Scheduler.builder().build()

    data class Result(val card: FlashcardEntity, val log: ReviewLogEntity)

    fun schedule(card: FlashcardEntity, rating: ReviewRating, now: Long): Result {
        val seeded = seedFromSm2(card)
        val reviewed = fsrs.reviewCard(toFsrsCard(seeded, now), rating.toFsrs(), Instant.ofEpochMilli(now)).card()

        val dueMs = reviewed.due.toEpochMilli()
        val intervalDays = ((dueMs - now).toDouble() / DAY_MILLIS).coerceAtLeast(0.0)
        val lapsed = rating == ReviewRating.AGAIN && seeded.state == FlashcardState.REVIEW

        val updated = seeded.copy(
            stability = reviewed.stability,
            difficulty = reviewed.difficulty,
            fsrsStep = reviewed.step,
            dueAt = dueMs,
            intervalDays = intervalDays,
            repetitions = seeded.repetitions + 1,
            lapses = seeded.lapses + if (lapsed) 1 else 0,
            lastReviewedAt = now,
            state = reviewed.state.toModel(),
        )
        val log = ReviewLogEntity(
            cardId = card.id,
            reviewedAt = now,
            rating = rating,
            lastIntervalDays = seeded.intervalDays,
            intervalDays = updated.intervalDays,
            stabilityAfter = updated.stability,
            difficultyAfter = updated.difficulty,
            easeFactor = updated.easeFactor,
            state = updated.state,
        )
        return Result(updated, log)
    }

    /**
     * Projected next-due epoch-ms for one rating, without persisting anything — for showing the
     * consequence of each grade on the review buttons. Pure and side-effect free (the engine is only
     * read), so it mirrors what [schedule] would produce for the same `(card, rating, now)`.
     */
    fun previewDue(card: FlashcardEntity, rating: ReviewRating, now: Long): Long =
        fsrs.reviewCard(toFsrsCard(seedFromSm2(card), now), rating.toFsrs(), Instant.ofEpochMilli(now))
            .card().due.toEpochMilli()

    /** Projected next-due epoch-ms for every rating (Again/Hard/Good/Easy). */
    fun previewAll(card: FlashcardEntity, now: Long): Map<ReviewRating, Long> =
        ReviewRating.entries.associateWith { previewDue(card, it, now) }

    /**
     * Existing SM-2 cards carry no FSRS memory state. For a card already in review, seed stability
     * from its current interval (interval ≈ stability at 0.9 retention) and difficulty to a neutral
     * midpoint, so the engine *continues* its schedule instead of resetting it to a brand-new card.
     * New/learning cards keep null memory state and are treated by FSRS as first reviews.
     */
    private fun seedFromSm2(card: FlashcardEntity): FlashcardEntity {
        if (card.stability != null) return card
        if (card.state != FlashcardState.REVIEW || card.intervalDays <= 0.0) return card
        return card.copy(
            stability = card.intervalDays.coerceAtLeast(MIN_STABILITY),
            difficulty = SEED_DIFFICULTY,
        )
    }

    private fun toFsrsCard(card: FlashcardEntity, now: Long): Card =
        Card.builder()
            .cardId(card.id.toInt())
            .state(card.state.toFsrs())
            // FSRS step is only meaningful while (re)learning; review cards carry none.
            .step(if (card.state == FlashcardState.REVIEW) null else (card.fsrsStep ?: 0))
            .stability(card.stability)
            .difficulty(card.difficulty)
            .due(Instant.ofEpochMilli(if (card.dueAt > 0) card.dueAt else now))
            .lastReview(card.lastReviewedAt?.let { Instant.ofEpochMilli(it) })
            .build()

    private fun ReviewRating.toFsrs(): Rating = when (this) {
        ReviewRating.AGAIN -> Rating.AGAIN
        ReviewRating.HARD -> Rating.HARD
        ReviewRating.GOOD -> Rating.GOOD
        ReviewRating.EASY -> Rating.EASY
    }

    // NEW is our pre-FSRS sentinel; FSRS itself has no New, so a new card enters as Learning.
    private fun FlashcardState.toFsrs(): State = when (this) {
        FlashcardState.NEW, FlashcardState.LEARNING -> State.LEARNING
        FlashcardState.REVIEW -> State.REVIEW
        FlashcardState.RELEARNING -> State.RELEARNING
    }

    private fun State.toModel(): FlashcardState = when (this) {
        State.LEARNING -> FlashcardState.LEARNING
        State.REVIEW -> FlashcardState.REVIEW
        State.RELEARNING -> FlashcardState.RELEARNING
    }

    companion object {
        private const val DAY_MILLIS = 86_400_000.0
        // Floor so a sub-day interval still seeds a usable stability; mid-scale difficulty (1..10).
        private const val MIN_STABILITY = 0.5
        private const val SEED_DIFFICULTY = 5.0
    }
}
