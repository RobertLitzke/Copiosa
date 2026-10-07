package com.copiosa.ui.components

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * Keeps the second half of a double tap on a reveal button from answering the card.
 *
 * The buttons that follow a reveal sit where the reveal button was — that is the point, the thumb
 * need not move — so a tap that bounces lands on a grade the learner never chose. The reveal tap
 * calls [mark]; whatever replaces it checks [settled] and drops taps that come too soon after to
 * have been aimed.
 */
@Stable
class RevealTapGuard internal constructor(
    /** Monotonic milliseconds; a parameter so the timing can be tested off a device. */
    private val now: () -> Long = SystemClock::uptimeMillis,
) {
    private var revealedAt: Long? = null

    fun mark() {
        revealedAt = now()
    }

    /** False for the moment after [mark] in which a tap is still the reveal's own bounce. */
    fun settled(): Boolean = revealedAt?.let { now() - it >= SETTLE_MS } ?: true

    internal companion object {
        /** Past the system's double-tap window, short of how fast anyone reads an answer. */
        const val SETTLE_MS = 350L
    }
}

/** A guard for the card identified by [key]; a new card starts with nothing to wait out. */
@Composable
fun rememberRevealTapGuard(key: Any?): RevealTapGuard = remember(key) { RevealTapGuard() }
