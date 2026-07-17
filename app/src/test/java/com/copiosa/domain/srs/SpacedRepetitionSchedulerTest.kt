package com.copiosa.domain.srs

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpacedRepetitionSchedulerTest {

    private val scheduler = SpacedRepetitionScheduler()
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    private fun newCard() = FlashcardEntity(
        front = "hola",
        back = "hello",
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = now,
        dueAt = now,
        state = FlashcardState.NEW,
    )

    /** A review-stage card that has already established FSRS memory state. */
    private fun reviewCard() = newCard().copy(
        state = FlashcardState.REVIEW,
        intervalDays = 10.0,
        stability = 10.0,
        difficulty = 5.0,
        repetitions = 3,
        lastReviewedAt = now - 10 * day,
        dueAt = now,
    )

    @Test
    fun goodOnNewCard_initialisesMemoryStateAndSchedulesFuture() {
        val result = scheduler.schedule(newCard(), ReviewRating.GOOD, now)
        assertNotNull("stability should be initialised", result.card.stability)
        assertNotNull("difficulty should be initialised", result.card.difficulty)
        assertTrue("card should be due in the future", result.card.dueAt > now)
        assertEquals(1, result.card.repetitions)
        assertEquals(now, result.card.lastReviewedAt)
    }

    @Test
    fun review_emitsALogRowForTheGrade() {
        val result = scheduler.schedule(newCard(), ReviewRating.GOOD, now)
        assertEquals(ReviewRating.GOOD, result.log.rating)
        assertEquals(now, result.log.reviewedAt)
        assertEquals(result.card.intervalDays, result.log.intervalDays, 0.0)
        assertEquals(result.card.stability, result.log.stabilityAfter)
    }

    @Test
    fun againOnNewCard_staysEarlyWithSubDayStep() {
        val result = scheduler.schedule(newCard(), ReviewRating.AGAIN, now)
        assertEquals(FlashcardState.LEARNING, result.card.state)
        assertTrue("relearn step is sub-day", result.card.dueAt > now && result.card.dueAt < now + day)
    }

    @Test
    fun goodOnReviewCard_extendsTheInterval() {
        val result = scheduler.schedule(reviewCard(), ReviewRating.GOOD, now)
        assertEquals(FlashcardState.REVIEW, result.card.state)
        assertTrue("interval should grow well past a day", result.card.intervalDays > 5.0)
        assertTrue(result.card.dueAt > now)
        assertEquals(4, result.card.repetitions)
    }

    @Test
    fun againOnReviewCard_lapsesIntoRelearning() {
        val result = scheduler.schedule(reviewCard(), ReviewRating.AGAIN, now)
        assertEquals(FlashcardState.RELEARNING, result.card.state)
        assertEquals(1, result.card.lapses)
    }

    @Test
    fun legacySm2Card_isSeededNotResetOnFirstFsrsReview() {
        // A pre-FSRS review card (no stability) with a long interval must keep its progress: seeding
        // sets stability ≈ interval, so a GOOD review schedules far out rather than restarting it.
        val legacy = newCard().copy(
            state = FlashcardState.REVIEW,
            intervalDays = 60.0,
            easeFactor = 2.5,
            stability = null,
            lastReviewedAt = now - 60 * day,
            dueAt = now,
        )
        val result = scheduler.schedule(legacy, ReviewRating.GOOD, now)
        assertNotNull(result.card.stability)
        assertTrue(
            "seeded card should stay on a long interval, not reset to a new-card interval",
            result.card.intervalDays > 30.0,
        )
    }
}
