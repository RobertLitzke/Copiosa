package com.copiosa.domain.routine

import kotlin.math.roundToInt

/**
 * The behind-the-scenes adaptation math (kept pure for testing). A bucket's [mastery] is an EMA of
 * per-attempt samples — a fast, correct answer scores 1.0, a slow-but-correct answer 0.7, a wrong
 * answer 0.0. Mastery then drives two knobs the rest of the system reads: the generator's
 * [difficultyFor] level (mastered material gets harder) and the planner's [weightFor] selection
 * weight (mastered material appears less often). None of this is ever shown to the learner as a score.
 */
object AdaptationMath {
    const val ALPHA = 0.3
    const val FAST_MS = 8_000L

    /** Auto listening-speed band a routine drives from mastery (±20% of normal). */
    const val AUTO_SPEED_MIN = 0.8f
    const val AUTO_SPEED_MAX = 1.2f

    /**
     * [speed] is the playback rate the cue was heard at (1.0 = normal). Comprehension at a faster clip
     * is harder, so it earns more mastery credit; the slow crutch earns less — a correct answer's
     * sample is scaled by [speedCredit] before feeding the EMA (a wrong answer is always 0.0).
     */
    fun nextMastery(old: Double, correct: Boolean, latencyMs: Long?, speed: Float = 1.0f): Double {
        val base = when {
            !correct -> 0.0
            latencyMs != null && latencyMs <= FAST_MS -> 1.0
            else -> 0.7
        }
        val sample = (base * speedCredit(speed)).coerceIn(0.0, 1.0)
        return (old * (1 - ALPHA) + ALPHA * sample).coerceIn(0.0, 1.0)
    }

    /** Mastery-credit weight for hearing a cue at [speed]: >1 above normal, <1 below. */
    fun speedCredit(speed: Float): Double = (0.5 + 0.5 * speed).coerceIn(0.5, 1.5)

    /**
     * Auto listening speed for a routine block from its [mastery]: struggling → slower, mastered →
     * faster. Snapped to a 0.1 grid so the in-routine speed readout stays tidy.
     */
    fun speedFor(mastery: Double): Float {
        val raw = AUTO_SPEED_MIN + (AUTO_SPEED_MAX - AUTO_SPEED_MIN) * mastery.toFloat()
        val snapped = (raw / SPEED_QUANTUM).roundToInt() * SPEED_QUANTUM
        return snapped.coerceIn(AUTO_SPEED_MIN, AUTO_SPEED_MAX)
    }

    private const val SPEED_QUANTUM = 0.1f

    /** Generator difficulty (0 = baseline, 2 = hardest) for a given mastery. */
    fun difficultyFor(mastery: Double): Int = when {
        mastery >= 0.85 -> 2
        mastery >= 0.6 -> 1
        else -> 0
    }

    /** Planner selection weight: high mastery → low weight (rarer); low mastery → high weight (more often). */
    fun weightFor(mastery: Double): Double = (1.2 - mastery).coerceIn(0.2, 1.2)
}
