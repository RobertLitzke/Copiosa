package com.copiosa.domain.maintenance

/**
 * How long background maintenance waits before retrying a chat that failed to get a reply.
 *
 * Quadratic-exponential so a chat failing for a structural reason (a revoked key, a model that no
 * longer exists) stops costing calls quickly: 1h, then 4h, then 16h, then never again.
 */
object RetryBackoff {
    /** Attempts after which a chat is left alone; the user can still retry it by hand. */
    const val MAX_ATTEMPTS = 3

    private const val HOUR_MS = 60L * 60 * 1000

    /**
     * The ceiling [readyCapped] clamps to: a job that keeps failing still comes back once a day.
     */
    const val MAX_DELAY_MS = 24L * HOUR_MS

    /** Whether a chat with [attempts] prior tries, last attempted at [lastAt], may be retried at [now]. */
    fun ready(attempts: Int, lastAt: Long?, now: Long): Boolean {
        if (attempts >= MAX_ATTEMPTS) return false
        if (lastAt == null) return true
        return (now - lastAt) >= delayMs(attempts)
    }

    /**
     * The same ladder with no attempt cap, clamped at [maxDelayMs].
     *
     * For work that is a *standing instruction* rather than a single request — a book set to
     * download in the background is an open-ended "keep filling this in", and a whole novel is far
     * too much to abandon over one bad evening. Giving up permanently is the worse failure there:
     * nothing asks again, and the only signal is a progress count that stopped moving. Backing off
     * to a call a day costs almost nothing and heals a transient failure on its own.
     */
    fun readyCapped(
        attempts: Int,
        lastAt: Long?,
        now: Long,
        maxDelayMs: Long = MAX_DELAY_MS,
    ): Boolean {
        if (lastAt == null) return true
        return (now - lastAt) >= minOf(delayMs(attempts), maxDelayMs)
    }

    /**
     * 1h, 4h, 16h, 64h, then flat.
     *
     * [attempts] is clamped *before* it is doubled, and that ordering is the point: `shl` on a Long
     * takes its shift count mod 64, and doubling a large Int overflows to a negative one. Either
     * would fold a long-failing job's delay back down towards an hour — turning the far end of the
     * backoff, where the caller most wants it quiet, into a tight retry loop.
     */
    private fun delayMs(attempts: Int): Long = HOUR_MS shl (2 * attempts.coerceIn(0, MAX_RUNGS))

    /** 1h shl (2 * 3) = 64h, comfortably past any [maxDelayMs] a caller would pass. */
    private const val MAX_RUNGS = 3
}
