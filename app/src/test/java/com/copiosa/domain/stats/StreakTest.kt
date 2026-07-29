package com.copiosa.domain.stats

import org.junit.Assert.assertEquals
import org.junit.Test

class StreakTest {

    private val today = 20_000L

    @Test
    fun `no activity means no streak`() {
        assertEquals(0, Streak.compute(emptyList(), today))
    }

    @Test
    fun `activity today alone is a one-day streak`() {
        assertEquals(1, Streak.compute(listOf(today), today))
    }

    @Test
    fun `consecutive days ending today all count`() {
        assertEquals(3, Streak.compute(listOf(today, today - 1, today - 2), today))
    }

    @Test
    fun `no activity yet today does not break yesterday's streak`() {
        assertEquals(2, Streak.compute(listOf(today - 1, today - 2), today))
    }

    @Test
    fun `a run that ended before yesterday is no streak`() {
        assertEquals(0, Streak.compute(listOf(today - 2, today - 3, today - 4), today))
    }

    @Test
    fun `a gap cuts the streak at the newest run`() {
        assertEquals(2, Streak.compute(listOf(today, today - 1, today - 3, today - 4, today - 5), today))
    }

    @Test
    fun `duplicate and unsorted days are tolerated`() {
        assertEquals(2, Streak.compute(listOf(today - 1, today, today, today - 1), today))
    }

    @Test
    fun `longest is zero with no activity`() {
        assertEquals(0, Streak.longest(emptyList()))
    }

    @Test
    fun `longest picks the biggest run, not the most recent`() {
        // A 4-day run in the past beats a 2-day run ending today.
        val days = listOf(today, today - 1, today - 10, today - 11, today - 12, today - 13)
        assertEquals(4, Streak.longest(days))
    }

    @Test
    fun `longest tolerates duplicates and unsorted input`() {
        assertEquals(3, Streak.longest(listOf(today - 2, today, today - 1, today, today - 2)))
    }
}
