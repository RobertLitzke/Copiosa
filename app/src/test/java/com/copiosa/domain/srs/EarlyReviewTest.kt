package com.copiosa.domain.srs

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewRating
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the scheduling behaviour the frequent-word drill relies on.
 *
 * That drill deliberately serves cards before they are due, and adds no grading rules of its own —
 * it leans entirely on FSRS doing something sensible with an early answer. Measured, it does:
 * memory stability always rises, and it rises less the earlier the answer comes, which is exactly
 * "partial credit" and is what stops the drill being farmable.
 *
 * One thing it does *not* do is guarantee a later due date. The new date is re-anchored from the
 * moment of the answer, so a card drilled very early (one day into a twenty-day interval) can come
 * due marginally sooner than it would have. That is a fair trade rather than a bug — the card's
 * memory state genuinely improved, so the review after it will be longer — but it is asserted below
 * so nobody later "fixes" the drill on the assumption that early reviews only ever defer.
 */
class EarlyReviewTest {

    private val scheduler = SpacedRepetitionScheduler()
    private val now = 1_000_000_000_000L
    private val day = 86_400_000L

    /** A settled card on a 20-day interval, last seen 20 days ago and due right now. */
    private fun matureCard() = FlashcardEntity(
        front = "gato",
        back = "cat",
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = now - 60 * day,
        dueAt = now,
        state = FlashcardState.REVIEW,
        intervalDays = 20.0,
        stability = 20.0,
        difficulty = 5.0,
        repetitions = 5,
        lastReviewedAt = now - 20 * day,
    )

    @Test
    fun `an early good answer always strengthens the card`() {
        // Across the whole range the drill can produce, from barely early to almost a full interval.
        for (daysEarly in listOf(1L, 5L, 10L, 15L, 19L)) {
            val early = matureCard().copy(
                dueAt = now + daysEarly * day,
                lastReviewedAt = now - (20 - daysEarly) * day,
            )

            val result = scheduler.schedule(early, ReviewRating.GOOD, now)

            assertTrue(
                "$daysEarly days early: stability must rise, or studying ahead would be wasted",
                requireNotNull(result.card.stability) > 20.0,
            )
            // And it must not land the card back in the queue immediately, which would turn one
            // drill session into a pile of reviews the next day.
            assertTrue(
                "$daysEarly days early: card should still be scheduled well ahead",
                result.card.dueAt >= now + 14 * day,
            )
        }
    }

    @Test
    fun `answering on time defers the card further than answering early`() {
        val onTime = scheduler.schedule(matureCard(), ReviewRating.GOOD, now)
        val early = scheduler.schedule(
            matureCard().copy(dueAt = now + 15 * day, lastReviewedAt = now - 5 * day),
            ReviewRating.GOOD,
            now,
        )

        assertTrue(
            "waiting until a card is due should always buy more time than drilling it early",
            onTime.card.dueAt > early.card.dueAt,
        )
    }

    @Test
    fun `answering early earns less than answering on time`() {
        val onTime = scheduler.schedule(matureCard(), ReviewRating.GOOD, now)
        val early = scheduler.schedule(
            matureCard().copy(dueAt = now + 15 * day, lastReviewedAt = now - 5 * day),
            ReviewRating.GOOD,
            now,
        )

        val onTimeGain = requireNotNull(onTime.card.stability) - 20.0
        val earlyGain = requireNotNull(early.card.stability) - 20.0
        assertTrue("both answers should strengthen the card", earlyGain > 0.0)
        assertTrue(
            "an early answer should gain less stability ($earlyGain) than an on-time one ($onTimeGain)",
            earlyGain < onTimeGain,
        )
    }

    @Test
    fun `the earlier the answer, the smaller the gain`() {
        // The anti-farming property: repeatedly drilling a fresh card cannot inflate its schedule,
        // because each answer taken well before the due date is worth progressively less.
        val gains = listOf(19L, 15L, 10L, 5L, 1L).map { daysEarly ->
            val card = matureCard().copy(
                dueAt = now + daysEarly * day,
                lastReviewedAt = now - (20 - daysEarly) * day,
            )
            requireNotNull(scheduler.schedule(card, ReviewRating.GOOD, now).card.stability) - 20.0
        }

        gains.zipWithNext { earlier, later ->
            assertTrue("gain should rise as the card gets closer to due: $gains", later > earlier)
        }
        assertTrue("even the earliest answer is worth something: $gains", gains.first() > 0.0)
    }
}
