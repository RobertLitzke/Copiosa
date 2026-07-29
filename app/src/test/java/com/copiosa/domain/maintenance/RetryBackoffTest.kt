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
}
