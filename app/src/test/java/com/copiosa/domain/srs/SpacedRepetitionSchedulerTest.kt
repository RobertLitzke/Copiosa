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
    fun longIntervalCard_isCappedAtFiveYears() {
        // Left alone, FSRS will schedule an extremely stable card a decade or more out. The ceiling
        // keeps it in rotation; five years is loose enough that ordinary mature cards never hit it.
        val veryStable = reviewCard().copy(intervalDays = 9_000.0, stability = 9_000.0)
        val result = scheduler.schedule(veryStable, ReviewRating.EASY, now)
        assertTrue(
            "interval should be capped at five years, was ${result.card.intervalDays}",
            result.card.intervalDays <= 5 * 365.0 + 1,
        )
    }

    @Test
    fun ordinaryMatureCard_isNotClippedByTheCeiling() {
        // Guards the cap being loose rather than load-bearing: a two-year-stable card must come back
        // on the retention curve, not flattened onto the ceiling.
        val mature = reviewCard().copy(intervalDays = 730.0, stability = 730.0)
        val result = scheduler.schedule(mature, ReviewRating.GOOD, now)
        assertTrue(
            "interval should follow the curve, not the cap, was ${result.card.intervalDays}",
            result.card.intervalDays < 5 * 365.0,
        )
    }

    @Test
    fun retentionCurve_schedulesWellInsideTheFsrsDefault() {
        // Guards the tightened default retention: at the stock 0.90 a card with stability S comes back
        // in ≈S days, so a GOOD review here would land near 200. Ours must be meaningfully sooner.
        val stable = reviewCard().copy(intervalDays = 200.0, stability = 200.0)
        val result = scheduler.schedule(stable, ReviewRating.GOOD, now)
        assertTrue(
            "interval should be well under the 0.90-retention schedule, was ${result.card.intervalDays}",
            result.card.intervalDays < 200.0,
        )
    }

    @Test
    fun higherRetention_schedulesSoonerThanLower() {
        // The per-language setting has to actually reach the engine: same card, same grade, two
        // retentions -> the stricter one must come back first.
        val card = reviewCard().copy(intervalDays = 100.0, stability = 100.0)
        val lax = scheduler.schedule(card, ReviewRating.GOOD, now, retention = 0.88).card.intervalDays
        val strict = scheduler.schedule(card, ReviewRating.GOOD, now, retention = 0.96).card.intervalDays
        assertTrue("0.96 ($strict) should schedule sooner than 0.88 ($lax)", strict < lax)
    }

    @Test
    fun retentionOutOfRange_isClampedRatherThanRejected() {
        // A corrupt stored value must not blow up or reach FSRS unclamped.
        val card = reviewCard()
        val absurd = scheduler.schedule(card, ReviewRating.GOOD, now, retention = 5.0).card.intervalDays
        val atMax = scheduler.schedule(
            card, ReviewRating.GOOD, now, retention = SpacedRepetitionScheduler.MAX_RETENTION,
        ).card.intervalDays
        assertEquals(atMax, absurd, 0.001)
    }

    @Test
    fun retentionRatio_isOneAtTheFsrsBaseline() {
        // projectInterval's arithmetic leans on this being normalised to 0.90.
        assertEquals(1.0, scheduler.retentionRatio(0.90), 0.001)
    }

    @Test
    fun projectInterval_shortensWhenRetentionRises_andRespectsTheCap() {
        assertTrue(scheduler.projectInterval(365.0, from = 0.90, to = 0.94) < 365.0)
        // Relaxing retention stretches the interval, but never past the ceiling.
        assertTrue(scheduler.projectInterval(365.0, from = 0.94, to = 0.90) > 365.0)
        assertTrue(scheduler.projectInterval(4_000.0, from = 0.94, to = 0.85) <= 5 * 365.0 + 1)
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
