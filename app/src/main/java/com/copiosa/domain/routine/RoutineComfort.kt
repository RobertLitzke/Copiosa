package com.copiosa.domain.routine

import kotlin.math.roundToInt

/** How comfortable the learner is at a scored bucket's *current* difficulty (from its mastery). */
enum class ComfortBand { NOT_PRACTICED, BUILDING, COMFORTABLE, MASTERED }

/** The generator difficulty the bucket is currently pitched at (from its difficultyLevel). */
enum class DifficultyTier { BASELINE, HARDER, HARDEST }

/** How often the planner will surface the bucket (from its selectionWeight). */
enum class AppearFrequency { OFTEN, SOMETIMES, RARELY }

/**
 * Presentational mapping of the adaptation engine's per-bucket estimate into learner-facing "comfort",
 * for the routine status screen. Pure + testable. The band thresholds mirror the ones [AdaptationMath]
 * uses to drive generator difficulty (0.6 / 0.85), so the band shown lines up with how hard the app is
 * actually making each drill. (The engine itself never surfaces a score; this is the read-only window.)
 */
object RoutineComfort {

    /** Mastery → comfort band; a bucket with no scored attempts has no meaningful score yet. */
    fun band(mastery: Double, attempts: Int): ComfortBand = when {
        attempts <= 0 -> ComfortBand.NOT_PRACTICED
        mastery >= 0.85 -> ComfortBand.MASTERED
        mastery >= 0.6 -> ComfortBand.COMFORTABLE
        else -> ComfortBand.BUILDING
    }

    /** Mastery as a 0–100 comfort score, or null until the bucket has been practiced. */
    fun scorePercent(mastery: Double, attempts: Int): Int? =
        if (attempts <= 0) null else (mastery * 100).roundToInt().coerceIn(0, 100)

    /** Generator difficulty tier (matches [AdaptationMath.difficultyFor]'s 0/1/2 output). */
    fun tier(difficultyLevel: Int): DifficultyTier = when {
        difficultyLevel >= 2 -> DifficultyTier.HARDEST
        difficultyLevel >= 1 -> DifficultyTier.HARDER
        else -> DifficultyTier.BASELINE
    }

    /** Selection weight (0.2 rare … 1.2 often, per [AdaptationMath.weightFor]) → coarse frequency. */
    fun frequency(selectionWeight: Double): AppearFrequency = when {
        selectionWeight <= 0.5 -> AppearFrequency.RARELY
        selectionWeight < 1.0 -> AppearFrequency.SOMETIMES
        else -> AppearFrequency.OFTEN
    }
}
