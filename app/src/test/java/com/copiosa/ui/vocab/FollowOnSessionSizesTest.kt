package com.copiosa.ui.vocab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sizes offered when a review session ends. The point of the row is that a long session can be
 * followed by a short one, so the cases that matter are the ones where the repeat size alone would
 * have been the only choice.
 */
class FollowOnSessionSizesTest {

    @Test
    fun `a long session offers shorter top-ups beside the repeat`() {
        assertEquals(listOf(20, 50, 100), followOnSessionSizes(due = 300, sessionSize = 100))
        assertEquals(listOf(10, 20, 50), followOnSessionSizes(due = 300, sessionSize = 50))
    }

    @Test
    fun `a short session fills the row with longer ones`() {
        assertEquals(listOf(10, 20, 50), followOnSessionSizes(due = 300, sessionSize = 20))
        assertEquals(listOf(10, 20, 50), followOnSessionSizes(due = 300, sessionSize = 10))
    }

    @Test
    fun `nothing offered runs past what is due`() {
        // Twenty were asked for, thirty are left: fifty would pad with cards that aren't due.
        assertEquals(listOf(10, 20), followOnSessionSizes(due = 30, sessionSize = 20))
        assertEquals(listOf(10, 20, 35), followOnSessionSizes(due = 35, sessionSize = 100))
    }

    @Test
    fun `a step too close to the repeat size is dropped`() {
        // "20" beside "23" is a choice between two of the same thing.
        assertEquals(listOf(10, 23), followOnSessionSizes(due = 23, sessionSize = 100))
        assertEquals(listOf(12), followOnSessionSizes(due = 12, sessionSize = 100))
    }

    @Test
    fun `a handful left is offered as the one option`() {
        assertEquals(listOf(7), followOnSessionSizes(due = 7, sessionSize = 100))
    }

    @Test
    fun `nothing due offers nothing`() {
        assertTrue(followOnSessionSizes(due = 0, sessionSize = 100).isEmpty())
    }

    @Test
    fun `an all-due session still offers the usual sizes below it`() {
        assertEquals(listOf(50, 100, 340), followOnSessionSizes(due = 400, sessionSize = 340))
    }

    @Test
    fun `the repeat size is always among the options and they ascend`() {
        for (due in 1..250) {
            for (size in listOf(5, 10, 20, 50, 100, 180)) {
                val sizes = followOnSessionSizes(due, size)
                assertTrue("due=$due size=$size", minOf(due, size) in sizes)
                assertEquals("due=$due size=$size", sizes.sorted().distinct(), sizes)
                assertTrue("due=$due size=$size", sizes.size <= 3 && sizes.all { it <= due })
            }
        }
    }
}
