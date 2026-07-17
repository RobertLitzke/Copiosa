package com.copiosa.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptationMathTest {

    @Test
    fun fastCorrect_raisesMastery_slowerThanWrongLowersIt() {
        val start = 0.5
        val afterFast = AdaptationMath.nextMastery(start, correct = true, latencyMs = 2_000)
        val afterWrong = AdaptationMath.nextMastery(start, correct = false, latencyMs = 2_000)
        assertTrue("fast correct should raise mastery", afterFast > start)
        assertTrue("wrong should lower mastery", afterWrong < start)
    }

    @Test
    fun slowCorrect_isBetterThanWrong_butWorseThanFastCorrect() {
        val start = 0.5
        val fast = AdaptationMath.nextMastery(start, correct = true, latencyMs = 1_000)
        val slow = AdaptationMath.nextMastery(start, correct = true, latencyMs = 30_000)
        val wrong = AdaptationMath.nextMastery(start, correct = false, latencyMs = 1_000)
        assertTrue(slow < fast)
        assertTrue(slow > wrong)
    }

    @Test
    fun fasterListening_earnsMoreMasteryCredit() {
        val start = 0.5
        val fast = AdaptationMath.nextMastery(start, correct = true, latencyMs = 1_000, speed = 1.2f)
        val slow = AdaptationMath.nextMastery(start, correct = true, latencyMs = 1_000, speed = 0.8f)
        assertTrue("comprehension at 1.2x should out-credit 0.8x", fast > slow)
        // Speed never rescues a wrong answer.
        assertEquals(
            AdaptationMath.nextMastery(start, correct = false, latencyMs = 1_000, speed = 0.8f),
            AdaptationMath.nextMastery(start, correct = false, latencyMs = 1_000, speed = 1.2f),
            1e-9,
        )
    }

    @Test
    fun speedFor_mapsMasteryIntoTheAutoBand() {
        assertEquals(AdaptationMath.AUTO_SPEED_MIN, AdaptationMath.speedFor(0.0), 1e-6f)
        assertEquals(AdaptationMath.AUTO_SPEED_MAX, AdaptationMath.speedFor(1.0), 1e-6f)
        assertEquals(1.0f, AdaptationMath.speedFor(0.5), 1e-6f)
        // Struggling → slower than mastered.
        assertTrue(AdaptationMath.speedFor(0.2) < AdaptationMath.speedFor(0.9))
        // Always inside the ±20% band.
        listOf(0.0, 0.3, 0.55, 0.85, 1.0).forEach {
            val s = AdaptationMath.speedFor(it)
            assertTrue("speed $s out of band", s in AdaptationMath.AUTO_SPEED_MIN..AdaptationMath.AUTO_SPEED_MAX)
        }
    }

    @Test
    fun masteryStaysInRange() {
        var m = 1.0
        repeat(20) { m = AdaptationMath.nextMastery(m, correct = true, latencyMs = 100) }
        assertTrue(m in 0.0..1.0)
        var low = 0.0
        repeat(20) { low = AdaptationMath.nextMastery(low, correct = false, latencyMs = 100) }
        assertTrue(low in 0.0..1.0)
    }

    @Test
    fun highMastery_raisesDifficulty_andLowersSelectionWeight() {
        assertEquals(0, AdaptationMath.difficultyFor(0.3))
        assertEquals(1, AdaptationMath.difficultyFor(0.7))
        assertEquals(2, AdaptationMath.difficultyFor(0.9))
        // Mastered material should be drawn less often than weak material.
        assertTrue(AdaptationMath.weightFor(0.95) < AdaptationMath.weightFor(0.2))
    }

    @Test
    fun selectionWeightIsBounded() {
        assertTrue(AdaptationMath.weightFor(1.0) >= 0.2)
        assertTrue(AdaptationMath.weightFor(0.0) <= 1.2)
    }
}
