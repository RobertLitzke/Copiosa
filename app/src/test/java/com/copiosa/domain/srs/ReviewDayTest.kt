package com.copiosa.domain.srs

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A note is reviewed once a day, and "a day" is the learner's calendar day — so the boundary a
 * deferred sibling is moved to has to be local midnight, not twenty-four hours on.
 */
class ReviewDayTest {

    private val denver = ZoneId.of("America/Denver")

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0) =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, denver).toInstant().toEpochMilli()

    @Test
    fun `the next day starts at local midnight, however late the review was`() {
        val midnight = at(2026, 10, 3, 0)
        assertEquals(midnight, ReviewWindows.startOfNextDay(at(2026, 10, 2, 6), denver))
        assertEquals(midnight, ReviewWindows.startOfNextDay(at(2026, 10, 2, 23, 59), denver))
    }

    @Test
    fun `a review just after midnight defers to the following midnight`() {
        assertEquals(at(2026, 10, 4, 0), ReviewWindows.startOfNextDay(at(2026, 10, 3, 0, 1), denver))
    }

    @Test
    fun `today starts at the midnight just gone`() {
        assertEquals(at(2026, 10, 2, 0), ReviewWindows.startOfDay(at(2026, 10, 2, 17), denver))
        assertEquals(at(2026, 10, 2, 0), ReviewWindows.startOfDay(at(2026, 10, 2, 0), denver))
    }

    @Test
    fun `a day that is not twenty-four hours long is still one day`() {
        // Clocks go back on 1 November 2026: that day is 25 hours, and the next still starts at midnight.
        assertEquals(at(2026, 11, 2, 0), ReviewWindows.startOfNextDay(at(2026, 11, 1, 12), denver))
        assertEquals(25 * 3_600_000L, at(2026, 11, 2, 0) - ReviewWindows.startOfDay(at(2026, 11, 1, 12), denver))
    }
}
