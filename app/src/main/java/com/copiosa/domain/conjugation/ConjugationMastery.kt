package com.copiosa.domain.conjugation

/** How the learner scored themselves once the answer was revealed. */
enum class DrillGrade(val slug: String, val correct: Boolean) {
    MISSED("missed", false),
    GOT_IT("got_it", true),
    ;

    companion object {
        fun fromSlug(slug: String?): DrillGrade? = entries.firstOrNull { it.slug == slug }
    }
}

/** One graded answer, in memory for the length of a session. */
data class DrillAnswer(
    val item: DrillItem,
    val form: ConjugatedForm,
    val grade: DrillGrade,
    val latencyMs: Long,
) {
    val correct: Boolean get() = grade.correct
}

/**
 * The drill's own mastery EMA.
 *
 * Shaped like [com.copiosa.domain.routine.AdaptationMath] but with a **much** tighter fast threshold.
 * The routine engine calls anything under eight seconds fast, which is right for comprehension but
 * useless here: a conjugation you have to think about for six seconds is not one you know, and at
 * eight seconds essentially every correct answer would score 1.0 and mastery would saturate on
 * contact. Recall speed *is* the signal for this drill.
 *
 * Raw latency is stored unmodified on every attempt, so this threshold can be retuned later and the
 * history re-scored.
 */
object ConjugationMastery {

    const val ALPHA = 0.3

    /** Answered from memory rather than worked out. */
    const val FAST_MS = 2_750L

    /** Beyond this a correct answer was reconstructed, not recalled. */
    const val SLOW_MS = 9_000L

    const val FAST_SAMPLE = 1.0
    const val MEDIUM_SAMPLE = 0.6
    const val SLOW_SAMPLE = 0.25

    fun sample(grade: DrillGrade, latencyMs: Long): Double = when {
        !grade.correct -> 0.0
        latencyMs <= FAST_MS -> FAST_SAMPLE
        latencyMs <= SLOW_MS -> MEDIUM_SAMPLE
        else -> SLOW_SAMPLE
    }

    fun next(old: Double, grade: DrillGrade, latencyMs: Long): Double =
        (old * (1 - ALPHA) + ALPHA * sample(grade, latencyMs)).coerceIn(0.0, 1.0)

    /**
     * Rescales a drill latency before it is handed to the routine adaptation engine, whose own fast
     * threshold is far looser. Without this a two-second conjugation and a seven-second one would be
     * indistinguishable to the planner.
     */
    fun scaleForRoutineAdaptation(latencyMs: Long, routineFastMs: Long): Long =
        (latencyMs.toDouble() * routineFastMs / FAST_MS).toLong()
}

/** End-of-session numbers. Mirrors the quiz's `SessionStats` in shape. */
data class ConjugationSessionStats(
    val answered: Int,
    val correct: Int,
    val avgLatencyMs: Long,
    val byTense: Map<Tense, Pair<Int, Int>>,
    val byFamily: Map<IrregularityFamily, Pair<Int, Int>>,
    val byDirection: Map<DrillDirection, Pair<Int, Int>>,
) {
    val accuracyPercent: Int get() = if (answered == 0) 0 else (correct * 100) / answered

    companion object {
        fun from(answers: List<DrillAnswer>): ConjugationSessionStats {
            fun <K> split(key: (DrillAnswer) -> K): Map<K, Pair<Int, Int>> =
                answers.groupBy(key).mapValues { (_, group) ->
                    group.size to group.count { it.correct }
                }
            return ConjugationSessionStats(
                answered = answers.size,
                correct = answers.count { it.correct },
                avgLatencyMs = if (answers.isEmpty()) 0 else answers.sumOf { it.latencyMs } / answers.size,
                byTense = split { it.item.target.tense },
                byFamily = split { it.form.family },
                byDirection = split { it.item.direction },
            )
        }
    }
}
