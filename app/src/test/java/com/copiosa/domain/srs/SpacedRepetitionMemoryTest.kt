package com.copiosa.domain.srs

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cloze bank schedules through [SpacedRepetitionScheduler.Memory] while the deck schedules
 * through [FlashcardEntity]. This pins that they are the same engine: for every state and every
 * grade, grading a card and grading its memory must produce identical memory state.
 *
 * Without this, the two surfaces could drift apart silently — the failure mode would be cloze
 * intervals quietly diverging from card intervals with nothing to point at.
 */
class SpacedRepetitionMemoryTest {

    private val scheduler = SpacedRepetitionScheduler()
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    private fun card() = FlashcardEntity(
        front = "hola",
        back = "hello",
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = now,
        dueAt = now,
    )

    /** Every shape a schedulable item can be in, including the legacy SM-2 row with no memory state. */
    private fun fixtures(): Map<String, FlashcardEntity> = mapOf(
        "new" to card().copy(state = FlashcardState.NEW),
        "learning" to card().copy(state = FlashcardState.LEARNING, fsrsStep = 1, stability = 1.5, difficulty = 5.0),
        "review" to card().copy(
            state = FlashcardState.REVIEW,
            intervalDays = 10.0, stability = 10.0, difficulty = 5.0,
            repetitions = 3, lastReviewedAt = now - 10 * day,
        ),
        "relearning" to card().copy(
            state = FlashcardState.RELEARNING,
            intervalDays = 1.0, stability = 2.0, difficulty = 7.0,
            repetitions = 5, lapses = 1, fsrsStep = 0, lastReviewedAt = now - day,
        ),
        // No stability: the seedFromSm2 path, which must fire identically on both sides.
        "legacySm2" to card().copy(
            state = FlashcardState.REVIEW,
            intervalDays = 21.0, easeFactor = 2.3,
            repetitions = 7, lastReviewedAt = now - 21 * day,
        ),
    )

    /**
     * FSRS applies a random fuzz to the due date on every call — five identical `EASY` grades on one
     * scheduler return intervals of 113, 117, 108, 107 and 110 days — so the schedule itself cannot
     * be compared for equality. Everything the engine derives deterministically can be, and that is
     * what identifies the two paths as the same engine.
     */
    @Test
    fun bothPathsAgreeForEveryStateAndGrade() {
        for ((name, card) in fixtures()) {
            for (rating in ReviewRating.entries) {
                val viaCard = scheduler.schedule(card, rating, now).card.memory()
                val viaMemory = scheduler.schedule(card.memory(), rating, now, cardId = card.id.toInt())
                assertEquals(
                    "$name / $rating",
                    viaMemory.copy(dueAt = 0L, intervalDays = 0.0),
                    viaCard.copy(dueAt = 0L, intervalDays = 0.0),
                )
                // Wide enough to absorb the fuzz, tight enough to catch a genuinely different
                // schedule (an unfuzzed 40-day GOOD against a 107-day EASY, say).
                val tolerance = 0.3 * maxOf(viaCard.intervalDays, viaMemory.intervalDays) + 1.0
                assertEquals(
                    "$name / $rating interval",
                    viaMemory.intervalDays,
                    viaCard.intervalDays,
                    tolerance,
                )
            }
        }
    }

    /** Sub-day intervals are below the fuzz threshold, so these two paths must match to the millisecond. */
    @Test
    fun bothPathsAgreeExactlyWhereNoFuzzApplies() {
        val card = fixtures().getValue("review")
        for (rating in listOf(ReviewRating.AGAIN, ReviewRating.HARD)) {
            val viaCard = scheduler.schedule(card, rating, now).card.memory()
            val viaMemory = scheduler.schedule(card.memory(), rating, now, cardId = card.id.toInt())
            assertEquals("$rating", viaMemory, viaCard)
        }
    }

    /** The log is the entity path's only extra output; it must still describe the pre-review card. */
    @Test
    fun theLogStillReportsThePreReviewIntervalAndEase() {
        val card = fixtures().getValue("legacySm2")
        val result = scheduler.schedule(card, ReviewRating.GOOD, now)
        assertEquals(card.intervalDays, result.log.lastIntervalDays, 0.0)
        assertEquals(card.easeFactor, result.log.easeFactor, 0.0)
        assertEquals(result.card.intervalDays, result.log.intervalDays, 0.0)
    }

    /** easeFactor is an Anki-export concern, not an FSRS one, so scheduling must leave it alone. */
    @Test
    fun schedulingDoesNotTouchEaseFactor() {
        val card = fixtures().getValue("review").copy(easeFactor = 2.15)
        assertEquals(2.15, scheduler.schedule(card, ReviewRating.AGAIN, now).card.easeFactor, 0.0)
    }
}
