package com.copiosa.domain.maintenance

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryBackoffTest {

    private val hour = 60L * 60 * 1000
    private val now = 1_700_000_000_000L

    @Test
    fun `a chat that has never been retried is ready immediately`() {
        assertTrue(RetryBackoff.ready(attempts = 0, lastAt = null, now = now))
    }

    @Test
    fun `first retry waits an hour`() {
        assertFalse(RetryBackoff.ready(attempts = 0, lastAt = now - hour + 1, now = now))
        assertTrue(RetryBackoff.ready(attempts = 0, lastAt = now - hour, now = now))
    }

    @Test
    fun `second retry waits four hours`() {
        assertFalse(RetryBackoff.ready(attempts = 1, lastAt = now - 4 * hour + 1, now = now))
        assertTrue(RetryBackoff.ready(attempts = 1, lastAt = now - 4 * hour, now = now))
    }

    @Test
    fun `third retry waits sixteen hours`() {
        assertFalse(RetryBackoff.ready(attempts = 2, lastAt = now - 16 * hour + 1, now = now))
        assertTrue(RetryBackoff.ready(attempts = 2, lastAt = now - 16 * hour, now = now))
    }

    @Test
    fun `a chat that exhausted its attempts is never retried again`() {
        // However long ago the last try was, and even with no recorded timestamp.
        assertFalse(RetryBackoff.ready(RetryBackoff.MAX_ATTEMPTS, lastAt = now - 365 * 24 * hour, now = now))
        assertFalse(RetryBackoff.ready(RetryBackoff.MAX_ATTEMPTS, lastAt = null, now = now))
        assertFalse(RetryBackoff.ready(RetryBackoff.MAX_ATTEMPTS + 5, lastAt = null, now = now))
    }

    // --- readyCapped: the standing-instruction ladder ------------------------

    @Test
    fun `the capped ladder climbs like the bounded one until it hits the cap`() {
        assertTrue(RetryBackoff.readyCapped(attempts = 0, lastAt = null, now = now))
        assertFalse(RetryBackoff.readyCapped(attempts = 0, lastAt = now - hour + 1, now = now))
        assertTrue(RetryBackoff.readyCapped(attempts = 0, lastAt = now - hour, now = now))
        assertFalse(RetryBackoff.readyCapped(attempts = 1, lastAt = now - 4 * hour + 1, now = now))
        assertTrue(RetryBackoff.readyCapped(attempts = 1, lastAt = now - 4 * hour, now = now))
        assertFalse(RetryBackoff.readyCapped(attempts = 2, lastAt = now - 16 * hour + 1, now = now))
        assertTrue(RetryBackoff.readyCapped(attempts = 2, lastAt = now - 16 * hour, now = now))
    }

    @Test
    fun `past the cap the wait stays a day, however many attempts have gone by`() {
        val day = 24 * hour
        // 64h unclamped would be the next rung; the cap is what the caller actually waits.
        assertTrue(RetryBackoff.readyCapped(attempts = 3, lastAt = now - day, now = now))
        assertFalse(RetryBackoff.readyCapped(attempts = 3, lastAt = now - day + 1, now = now))
        for (attempts in intArrayOf(4, 10, 100, 1_000, Int.MAX_VALUE)) {
            assertTrue(
                "attempts=$attempts should still retry after a day",
                RetryBackoff.readyCapped(attempts, lastAt = now - day, now = now),
            )
            // The `shl` wrap this clamp exists to prevent would let a huge attempt count fold the
            // delay back to an hour and retry in a loop.
            assertFalse(
                "attempts=$attempts must not retry after only an hour",
                RetryBackoff.readyCapped(attempts, lastAt = now - hour, now = now),
            )
        }
    }

    @Test
    fun `a book set to download in the background is never given up on`() {
        // The whole point of the capped ladder: a year of failures still leaves it eligible, where
        // ready() would have written the book off after three.
        val ago = now - 365 * 24 * hour
        assertFalse(RetryBackoff.ready(attempts = 500, lastAt = ago, now = now))
        assertTrue(RetryBackoff.readyCapped(attempts = 500, lastAt = ago, now = now))
    }
}
