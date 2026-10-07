package com.copiosa.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The window in which a tap on a grade is taken for the bounce of the reveal tap before it. The
 * guard is all timing, so a clock the test owns is the whole of what it needs.
 */
class RevealTapGuardTest {

    private var clock = 10_000L
    private val guard = RevealTapGuard(now = { clock })

    @Test
    fun aCardNeverRevealed_hasNothingToWaitOut() {
        // An answer restored already showing (back from the card's detail screen) grades at once.
        assertTrue(guard.settled())
    }

    @Test
    fun theSecondHalfOfADoubleTap_isDropped() {
        guard.mark()
        assertFalse(guard.settled())
        clock += 120
        assertFalse(guard.settled())
        clock += RevealTapGuard.SETTLE_MS - 121
        assertFalse(guard.settled())
    }

    @Test
    fun aTapAfterTheWindow_counts() {
        guard.mark()
        clock += RevealTapGuard.SETTLE_MS
        assertTrue(guard.settled())
    }

    @Test
    fun revealingAgain_restartsTheWindow() {
        guard.mark()
        clock += RevealTapGuard.SETTLE_MS
        guard.mark()
        assertFalse(guard.settled())
    }
}
