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
 * Side-effect free: engines are cached per retention and only ever read. The memory state it derives
 * (stability, difficulty, state, step) is deterministic given (card, rating, now, retention), but the
 * **due date is not** — FSRS fuzzes intervals past a couple of days, so two identical grades return
 * schedules a few percent apart. Nothing may assume a reproducible due date; see
 * `SpacedRepetitionMemoryTest`.
 *
 * Returns the updated card plus the [ReviewLogEntity] for this grade; the caller persists both.
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

    /**
     * The FSRS memory state of one schedulable item, independent of which table it lives in — so the
     * cloze bank rides the same engine as the deck rather than growing a second scheduler.
     *
     * [FlashcardEntity.easeFactor] is deliberately absent: [toFsrsCard] never reads it and
     * [schedule] never writes it. It exists only so an .apkg export has a classic-scheduler column
     * to fill, which is a property of flashcards, not of FSRS.
     */
    data class Memory(
        val state: FlashcardState = FlashcardState.NEW,
        val dueAt: Long = 0L,
        val intervalDays: Double = 0.0,
        val stability: Double? = null,
        val difficulty: Double? = null,
        val fsrsStep: Int? = null,
        val lastReviewedAt: Long? = null,
        val repetitions: Int = 0,
        val lapses: Int = 0,
    )

    /**
     * Grades [memory] and returns its new state. The whole of the FSRS math lives here; the
     * [FlashcardEntity] overload is this plus a [ReviewLogEntity].
     */
    fun schedule(
        memory: Memory,
        rating: ReviewRating,
        now: Long,
        retention: Double = DEFAULT_RETENTION,
        cardId: Int = 0,
    ): Memory {
        val seeded = seedFromSm2(memory)
        val reviewed = engineFor(retention)
            .reviewCard(toFsrsCard(seeded, now, cardId), rating.toFsrs(), Instant.ofEpochMilli(now)).card()

        val dueMs = reviewed.due.toEpochMilli()
        val intervalDays = ((dueMs - now).toDouble() / DAY_MILLIS).coerceAtLeast(0.0)
        val lapsed = rating == ReviewRating.AGAIN && seeded.state == FlashcardState.REVIEW

        return seeded.copy(
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
    }

    fun schedule(
        card: FlashcardEntity,
        rating: ReviewRating,
        now: Long,
        retention: Double = DEFAULT_RETENTION,
    ): Result {
        val after = schedule(card.memory(), rating, now, retention, card.id.toInt())
        val updated = card.applying(after)
        val log = ReviewLogEntity(
            cardId = card.id,
            reviewedAt = now,
            rating = rating,
            // Seeding only ever fills stability/difficulty and scheduling never touches easeFactor,
            // so the pre-review interval and the ease are the card's own — no need to seed to read them.
            lastIntervalDays = card.intervalDays,
            intervalDays = updated.intervalDays,
            stabilityAfter = updated.stability,
            difficultyAfter = updated.difficulty,
            easeFactor = card.easeFactor,
            state = updated.state,
        )
        return Result(updated, log)
    }

    /**
     * Where one grade sends a card: when it next comes up, and — while the grade leaves it stepping
     * through (re)learning — when it would come up after that, assuming the step is then passed.
     *
     * The second number is what a learner actually wants from a lapsed card's buttons: "10 minutes"
     * alone says nothing about whether the card is being knocked back to square one or merely nudged.
     * Null once the grade graduates the card outright, where there is no intermediate step to report.
     */
    data class Preview(val dueAt: Long, val followUpAt: Long? = null)

    /**
     * [Preview] of every rating (Again/Hard/Good/Easy), without persisting anything — for showing the
     * consequence of each grade on the review buttons. Side-effect free (the engine is only read).
     *
     * An estimate, not a promise: FSRS fuzzing means the grade the learner then presses lands a few
     * percent either side of what was shown. That is invisible at the granularity the buttons are
     * rendered at ("2 weeks"), which is the only reason it is fine to show.
     */
    fun previewAll(
        card: FlashcardEntity,
        now: Long,
        retention: Double = DEFAULT_RETENTION,
    ): Map<ReviewRating, Preview> {
        val memory = card.memory()
        val cardId = card.id.toInt()
        return ReviewRating.entries.associateWith { rating ->
            val graded = schedule(memory, rating, now, retention, cardId)
            // A card still in (re)learning has at least one more short step before its real interval
            // resumes; project that step being passed to get the interval on the far side of it.
            val followUpAt = if (graded.state == FlashcardState.REVIEW) {
                null
            } else {
                schedule(graded, ReviewRating.GOOD, graded.dueAt, retention, cardId).dueAt
            }
            Preview(graded.dueAt, followUpAt)
        }
    }

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
    private fun seedFromSm2(memory: Memory): Memory {
        if (memory.stability != null) return memory
        if (memory.state != FlashcardState.REVIEW || memory.intervalDays <= 0.0) return memory
        return memory.copy(
            stability = memory.intervalDays.coerceAtLeast(MIN_STABILITY),
            difficulty = SEED_DIFFICULTY,
        )
    }

    private fun toFsrsCard(memory: Memory, now: Long, cardId: Int): Card =
        Card.builder()
            .cardId(cardId)
            .state(memory.state.toFsrs())
            // FSRS step is only meaningful while (re)learning; review cards carry none.
            .step(if (memory.state == FlashcardState.REVIEW) null else (memory.fsrsStep ?: 0))
            .stability(memory.stability)
            .difficulty(memory.difficulty)
            .due(Instant.ofEpochMilli(if (memory.dueAt > 0) memory.dueAt else now))
            .lastReview(memory.lastReviewedAt?.let { Instant.ofEpochMilli(it) })
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

/** The scheduler's view of a flashcard. Internal so the equivalence test can compare both paths. */
internal fun FlashcardEntity.memory(): SpacedRepetitionScheduler.Memory =
    SpacedRepetitionScheduler.Memory(
        state = state,
        dueAt = dueAt,
        intervalDays = intervalDays,
        stability = stability,
        difficulty = difficulty,
        fsrsStep = fsrsStep,
        lastReviewedAt = lastReviewedAt,
        repetitions = repetitions,
        lapses = lapses,
    )

/** Writes a graded [SpacedRepetitionScheduler.Memory] back onto the card it came from. */
private fun FlashcardEntity.applying(m: SpacedRepetitionScheduler.Memory): FlashcardEntity = copy(
    state = m.state,
    dueAt = m.dueAt,
    intervalDays = m.intervalDays,
    stability = m.stability,
    difficulty = m.difficulty,
    fsrsStep = m.fsrsStep,
    lastReviewedAt = m.lastReviewedAt,
    repetitions = m.repetitions,
    lapses = m.lapses,
)
