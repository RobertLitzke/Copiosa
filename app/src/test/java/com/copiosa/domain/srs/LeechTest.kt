package com.copiosa.domain.srs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LeechTest {

    @Test
    fun `a card becomes a leech at the threshold and not before`() {
        assertFalse(Leech.isLeech(lapses = 7, intervalDays = 2.0, suspended = false))
        assertTrue(Leech.isLeech(lapses = 8, intervalDays = 2.0, suspended = false))
        assertTrue(Leech.isLeech(lapses = 30, intervalDays = 0.0, suspended = false))
    }

    @Test
    fun `a card that has settled is no longer one, whatever it cost`() {
        assertTrue(Leech.isLeech(lapses = 12, intervalDays = 20.9, suspended = false))
        assertFalse(Leech.isLeech(lapses = 12, intervalDays = 21.0, suspended = false))
    }

    @Test
    fun `a frozen card has already been dealt with`() {
        assertFalse(Leech.isLeech(lapses = 12, intervalDays = 2.0, suspended = true))
    }

    @Test
    fun `the notice fires on reaching the threshold and every fourth lapse after`() {
        assertFalse(Leech.crossedInto(before = 6, after = 7))
        assertTrue(Leech.crossedInto(before = 7, after = 8))
        assertFalse(Leech.crossedInto(before = 8, after = 9))
        assertFalse(Leech.crossedInto(before = 10, after = 11))
        assertTrue(Leech.crossedInto(before = 11, after = 12))
        assertTrue(Leech.crossedInto(before = 15, after = 16))
    }

    @Test
    fun `a grade that did not add a lapse is never a notice`() {
        assertFalse(Leech.crossedInto(before = 8, after = 8))
        assertFalse(Leech.crossedInto(before = 12, after = 12))
    }
}
