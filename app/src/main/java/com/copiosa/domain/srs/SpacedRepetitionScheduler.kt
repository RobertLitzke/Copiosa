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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FSRS-backed scheduler. The memory-state math is delegated to the FSRS engine — the same algorithm
 * modern Anki uses — so a card's stability/difficulty/due round-trip cleanly to and from Anki.
 *
 * Pure given (card, rating, now, retention): engines are cached per retention and only read, so the
 * result is deterministic (FSRS fuzzing uses a fixed seed). Returns the updated card plus the
 * [ReviewLogEntity] for this grade; the caller persists both.
 */
@Singleton
class SpacedRepetitionScheduler @Inject constructor() {

    /**
     * One FSRS engine per desired-retention value. Retention is a per-language setting, so a single
     * baked-in engine won't do; engines are immutable and cheap, so they're built on first use and
     * cached rather than reconstructed per review. Learning steps 1m/10m and fuzzing (fixed seed) are
     * library defaults; the interval ceiling is ours — see [MAX_INTERVAL_DAYS].
     */
    private val engines = ConcurrentHashMap<Int, Scheduler>()

    private fun engineFor(retention: Double): Scheduler {
        val clamped = retention.coerceIn(MIN_RETENTION, MAX_RETENTION)
        // Key on permille so float noise in the stored setting can't fragment the cache.
        return engines.getOrPut(Math.round(clamped * 1000).toInt()) {
            Scheduler.builder()
                .desiredRetention(clamped)
                .maximumInterval(MAX_INTERVAL_DAYS)
                .build()
        }
    }

    data class Result(val card: FlashcardEntity, val log: ReviewLogEntity)

    fun schedule(
        card: FlashcardEntity,
        rating: ReviewRating,
        now: Long,
        retention: Double = DEFAULT_RETENTION,
    ): Result {
        val seeded = seedFromSm2(card)
        val reviewed = engineFor(retention)
            .reviewCard(toFsrsCard(seeded, now), rating.toFsrs(), Instant.ofEpochMilli(now)).card()

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
    fun previewDue(
        card: FlashcardEntity,
        rating: ReviewRating,
        now: Long,
        retention: Double = DEFAULT_RETENTION,
    ): Long =
        engineFor(retention)
            .reviewCard(toFsrsCard(seedFromSm2(card), now), rating.toFsrs(), Instant.ofEpochMilli(now))
            .card().due.toEpochMilli()

    /** Projected next-due epoch-ms for every rating (Again/Hard/Good/Easy). */
    fun previewAll(
        card: FlashcardEntity,
        now: Long,
        retention: Double = DEFAULT_RETENTION,
    ): Map<ReviewRating, Long> =
        ReviewRating.entries.associateWith { previewDue(card, it, now, retention) }

    /**
     * How much [retention] stretches or compresses every interval, relative to the FSRS baseline of
     * 0.90. Retention scales all intervals by this one constant factor (it does not compress long
     * intervals more than short ones), so `daysAt(r2) = daysAt(r1) * ratio(r2) / ratio(r1)`. Exposed
     * so Settings can show the user what moving the slider actually does to their cards.
     */
    fun retentionRatio(retention: Double): Double {
        val engine = engineFor(retention)
        val decay = engine.getDECAY()
        // FACTOR is defined against 0.9, so this comes out as 1.0 at the FSRS default retention.
        return (Math.pow(retention.coerceIn(MIN_RETENTION, MAX_RETENTION), 1.0 / decay) - 1) / engine.getFACTOR()
    }

    /**
     * The interval a card currently sitting at [currentIntervalDays] under [from] retention would get
     * under [to], including the [MAX_INTERVAL_DAYS] ceiling. For the Settings preview only.
     */
    fun projectInterval(currentIntervalDays: Double, from: Double, to: Double): Double =
        (currentIntervalDays * retentionRatio(to) / retentionRatio(from))
            .coerceAtMost(MAX_INTERVAL_DAYS.toDouble())

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
        /**
         * Fallback when no per-language setting is supplied. Above the FSRS default of 0.90, which was
         * scheduling cards a year or more out while they still felt shaky. Retention scales *every*
         * interval by a constant factor — with the default DECAY of -0.2, 0.94 works out to ≈0.52x, so
         * a 1-year card comes back in ~6 months and a 30-day card in ~16 days.
         */
        const val DEFAULT_RETENTION = 0.94

        /**
         * Slider bounds. Below ~0.85 the schedule gets long enough that recall genuinely fails; above
         * ~0.97 review load climbs steeply for very little retention gained.
         */
        const val MIN_RETENTION = 0.85
        const val MAX_RETENTION = 0.97

        /**
         * Ceiling on a single interval, well below the library default of ~100 years. Retention alone
         * compresses long and short intervals equally, so this is the only thing bounding the far
         * tail: it stops a very stable card from disappearing for a decade, while still letting
         * genuinely well-known material go quiet for years.
         */
        private const val MAX_INTERVAL_DAYS = 5 * 365

        private const val DAY_MILLIS = 86_400_000.0
        // Floor so a sub-day interval still seeds a usable stability; mid-scale difficulty (1..10).
        private const val MIN_STABILITY = 0.5
        private const val SEED_DIFFICULTY = 5.0
    }
}
