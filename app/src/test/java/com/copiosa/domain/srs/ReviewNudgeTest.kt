package com.copiosa.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The pure arithmetic behind passive review and the cloze nudge. Both only ever move a due date —
 * the FSRS memory state is not theirs to touch — so these are the whole of their behaviour.
 */
class ReviewNudgeTest {

    private val now = 1_700_000_000_000L
    private fun days(n: Double): Long = (n * ReviewWindows.DAY_MS).toLong()

    @Test
    fun passiveSighting_pushesByHalfTheInterval() {
        val due = now + days(3.0)
        assertEquals(due + days(5.0), ReviewWindows.passiveNudgeDueAt(due, 10.0, now))
    }

    @Test
    fun passiveSighting_isCappedAtAMonth() {
        val due = now + days(1.0)
        assertEquals(due + days(30.0), ReviewWindows.passiveNudgeDueAt(due, 400.0, now))
    }

    @Test
    fun correctCloze_pushesByAQuarterOfTheInterval() {
        val due = now + days(2.0)
        assertEquals(due + days(3.0), ReviewWindows.clozeNudgeDueAt(due, 12.0, now, correct = true))
    }

    @Test
    fun missedCloze_pullsBackByAQuarterOfTheInterval() {
        val due = now + days(10.0)
        assertEquals(due - days(3.0), ReviewWindows.clozeNudgeDueAt(due, 12.0, now, correct = false))
    }

    /** A run of misses must not drag a card into the past — it can only become due now. */
    @Test
    fun missedCloze_neverPullsPastNow() {
        val due = now + days(1.0)
        assertEquals(now, ReviewWindows.clozeNudgeDueAt(due, 40.0, now, correct = false))
        val alreadyDue = now - days(5.0)
        assertEquals(now, ReviewWindows.clozeNudgeDueAt(alreadyDue, 8.0, now, correct = false))
    }

    @Test
    fun clozePush_isCappedAtHalfThePassiveCap() {
        val due = now + days(1.0)
        assertEquals(due + days(15.0), ReviewWindows.clozeNudgeDueAt(due, 400.0, now, correct = true))
    }

    /** A card due beyond the passive window is well enough known that a sighting shouldn't move it. */
    @Test
    fun nothingMovesOutsideThePassiveWindow() {
        val farOut = now + ReviewWindows.PASSIVE_WINDOW_MS + days(1.0)
        assertNull(ReviewWindows.passiveNudgeDueAt(farOut, 60.0, now))
        assertNull(ReviewWindows.clozeNudgeDueAt(farOut, 60.0, now, correct = true))
        assertNull(ReviewWindows.clozeNudgeDueAt(farOut, 60.0, now, correct = false))
    }

    /** A never-reviewed card has no interval to take a quarter of, so it stays put either way. */
    @Test
    fun aNewCardDoesNotMove() {
        assertNull(ReviewWindows.passiveNudgeDueAt(now, 0.0, now))
        assertNull(ReviewWindows.clozeNudgeDueAt(now, 0.0, now, correct = true))
        assertNull(ReviewWindows.clozeNudgeDueAt(now, 0.0, now, correct = false))
    }
}
