package com.copiosa.domain.cloze

import com.copiosa.domain.model.ReviewRating
import org.junit.Assert.assertEquals
import org.junit.Test

class ClozeGradingTest {

    private fun grade(correct: Boolean, latencyMs: Long, words: Int = 8, reps: Int = 3) =
        ClozeGrading.gradeFor(correct, latencyMs, words, reps)

    @Test
    fun aWrongAnswerIsAlwaysAgain() {
        assertEquals(ReviewRating.AGAIN, grade(correct = false, latencyMs = 200))
        assertEquals(ReviewRating.AGAIN, grade(correct = false, latencyMs = 60_000))
    }

    @Test
    fun instantRecallIsEasy() {
        assertEquals(ReviewRating.EASY, grade(correct = true, latencyMs = 1_000))
    }

    /**
     * A four-option item can be guessed 25% of the time, so the first correct answer on a sentence
     * never earns the biggest interval jump FSRS offers.
     */
    @Test
    fun aFastFirstSightingIsOnlyGoodBecauseItMayBeAGuess() {
        assertEquals(ReviewRating.GOOD, grade(correct = true, latencyMs = 1_000, reps = 0))
        assertEquals(ReviewRating.EASY, grade(correct = true, latencyMs = 1_000, reps = 1))
    }

    @Test
    fun aLabouredAnswerIsHard() {
        assertEquals(ReviewRating.HARD, grade(correct = true, latencyMs = 60_000))
    }

    @Test
    fun anOrdinaryAnswerIsGood() {
        val words = 8
        val mid = (ClozeGrading.fastThresholdMs(words) + ClozeGrading.slowThresholdMs(words)) / 2
        assertEquals(ReviewRating.GOOD, grade(correct = true, latencyMs = mid, words = words))
    }

    /**
     * The reported latency includes reading the sentence, so a flat threshold would call every long
     * sentence "laboured" and every short one "instant".
     */
    @Test
    fun thresholdsScaleWithSentenceLength() {
        assertEquals(ReviewRating.HARD, grade(correct = true, latencyMs = 12_000, words = 4))
        assertEquals(ReviewRating.GOOD, grade(correct = true, latencyMs = 12_000, words = 18))
    }
}
