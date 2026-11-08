package com.copiosa.data.stats

import com.copiosa.data.local.DueCard
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class DueNotesPerDayTest {
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 11, 6).toEpochDay()

    private fun at(day: Long, hour: Int): Long =
        LocalDate.ofEpochDay(day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun overdueCardsLandOnToday() {
        val due = listOf(DueCard(1, at(today - 40, 9)), DueCard(2, at(today - 1, 23)), DueCard(3, at(today, 8)))
        assertEquals(listOf(3, 0, 0), dueNotesPerDay(due, today, 3, zone))
    }

    @Test
    fun siblingsDueTheSameDayCountOnce() {
        val due = listOf(DueCard(1, at(today + 1, 9)), DueCard(1, at(today + 1, 20)), DueCard(2, at(today + 1, 10)))
        assertEquals(listOf(0, 2), dueNotesPerDay(due, today, 2, zone))
    }

    @Test
    fun siblingsOnDifferentDaysCountOnEach() {
        val due = listOf(DueCard(1, at(today - 3, 9)), DueCard(1, at(today + 2, 9)))
        assertEquals(listOf(1, 0, 1), dueNotesPerDay(due, today, 3, zone))
    }

    @Test
    fun cardsPastTheWindowAreDropped() {
        val due = listOf(DueCard(1, at(today + 5, 9)))
        assertEquals(listOf(0, 0, 0), dueNotesPerDay(due, today, 3, zone))
    }
}
