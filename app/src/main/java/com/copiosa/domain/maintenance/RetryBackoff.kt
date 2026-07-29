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

    /** Whether a chat with [attempts] prior tries, last attempted at [lastAt], may be retried at [now]. */
    fun ready(attempts: Int, lastAt: Long?, now: Long): Boolean {
        if (attempts >= MAX_ATTEMPTS) return false
        if (lastAt == null) return true
        return (now - lastAt) >= (HOUR_MS shl (2 * attempts))
    }
}
