package com.copiosa.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoutineComfortTest {

    @Test
    fun unpracticedBucket_hasNoBandAndNoScore() {
        assertEquals(ComfortBand.NOT_PRACTICED, RoutineComfort.band(mastery = 0.3, attempts = 0))
        assertNull(RoutineComfort.scorePercent(mastery = 0.3, attempts = 0))
    }

    @Test
    fun bandThresholds_matchAdaptationDifficulty() {
        // Aligns with AdaptationMath.difficultyFor's 0.6 / 0.85 cut-points.
        assertEquals(ComfortBand.BUILDING, RoutineComfort.band(0.4, attempts = 3))
        assertEquals(ComfortBand.COMFORTABLE, RoutineComfort.band(0.7, attempts = 3))
        assertEquals(ComfortBand.MASTERED, RoutineComfort.band(0.9, attempts = 3))
    }

    @Test
    fun scorePercent_isRoundedAndBounded() {
        assertEquals(72, RoutineComfort.scorePercent(0.715, attempts = 1))
        assertEquals(100, RoutineComfort.scorePercent(1.0, attempts = 5))
        assertEquals(0, RoutineComfort.scorePercent(0.0, attempts = 5))
    }

    @Test
    fun tier_mapsDifficultyLevel() {
        assertEquals(DifficultyTier.BASELINE, RoutineComfort.tier(0))
        assertEquals(DifficultyTier.HARDER, RoutineComfort.tier(1))
        assertEquals(DifficultyTier.HARDEST, RoutineComfort.tier(2))
    }

    @Test
    fun frequency_followsSelectionWeight() {
        // weightFor: low mastery -> ~1.2 (often), high mastery -> ~0.2 (rarely).
        assertEquals(AppearFrequency.OFTEN, RoutineComfort.frequency(AdaptationMath.weightFor(0.1)))
        assertEquals(AppearFrequency.RARELY, RoutineComfort.frequency(AdaptationMath.weightFor(0.95)))
    }
}
