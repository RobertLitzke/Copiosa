package com.copiosa.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Test

/** The arithmetic behind "today's reviews": what the limit admits, and what it holds for later. */
class DueTodayTest {

    @Test
    fun `the allowance is what the limit leaves after today's first reviews`() {
        assertEquals(20, NewCardLimit.remaining(perDay = 20, introducedToday = 0))
        assertEquals(7, NewCardLimit.remaining(perDay = 20, introducedToday = 13))
        // Lowering the limit mid-day below what has already been met leaves nothing, not a debt.
        assertEquals(0, NewCardLimit.remaining(perDay = 5, introducedToday = 13))
        assertEquals(0, NewCardLimit.remaining(perDay = 0, introducedToday = 0))
    }

    @Test
    fun `no limit admits everything however much has been met`() {
        assertEquals(Int.MAX_VALUE, NewCardLimit.remaining(NewCardLimit.UNLIMITED, introducedToday = 500))
    }

    @Test
    fun `reviews are never held back and new words only as far as the allowance`() {
        val today = DueToday.of(reviewNotes = 700, newNotes = 800, allowance = 20)

        assertEquals(700, today.reviews)
        assertEquals(20, today.newToday)
        assertEquals(780, today.newHeld)
        assertEquals(720, today.total)
    }

    @Test
    fun `with nothing held back the total is simply everything due`() {
        val today = DueToday.of(reviewNotes = 12, newNotes = 3, allowance = Int.MAX_VALUE)

        assertEquals(15, today.total)
        assertEquals(0, today.newHeld)
    }

    @Test
    fun `a spent allowance leaves the reviews and holds every new word`() {
        val today = DueToday.of(reviewNotes = 4, newNotes = 30, allowance = 0)

        assertEquals(4, today.total)
        assertEquals(30, today.newHeld)
    }
}
