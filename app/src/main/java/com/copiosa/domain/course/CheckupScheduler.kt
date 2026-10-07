package com.copiosa.domain.course

/**
 * The post-graduation retention ladder: expanding intervals that stretch while the material holds
 * and pull back in when it slips.
 *
 * This is deliberately *not* FSRS. FSRS schedules an individual card against its own recall
 * probability, and the graduation flashcards get exactly that treatment. What a checkup measures is
 * different — whether the *skill* survived, across a whole course's paradigm — so it gets a simple,
 * legible ladder, and its failures buy something FSRS can't offer: the lesson that taught the
 * failing material is reopened.
 *
 * Pure and time-injected, so `CheckupSchedulerTest` can walk months in milliseconds.
 */
object CheckupScheduler {

    /** Days after graduation (or after the previous checkup) that each stage waits. */
    val LADDER_DAYS = listOf(3, 7, 14, 30, 60)

    const val DAY_MS = 24 * 60 * 60 * 1000L

    /** A checkup at or below this accuracy has found real decay. */
    const val PASS_ACCURACY = 0.8

    /** A lesson whose items fall below this in a checkup is flagged for its micro-lesson. */
    const val LESSON_FLAG_ACCURACY = 0.6

    /** The first checkup, scheduled the moment a course graduates. */
    fun firstCheckup(graduatedAt: Long): Long = graduatedAt + LADDER_DAYS.first() * DAY_MS

    /** What a finished checkup does to the ladder. */
    data class Outcome(val stage: Int, val nextCheckupAt: Long)

    /**
     * Advances on a pass, steps back one rung on a failure. Stepping back rather than resetting is
     * the point: one bad evening after a month of retention should not send the learner back to
     * three-day checkups, but it should shorten the leash.
     */
    fun afterCheckup(stage: Int, accuracy: Double, now: Long): Outcome {
        val nextStage = if (accuracy >= PASS_ACCURACY) {
            (stage + 1).coerceAtMost(LADDER_DAYS.lastIndex)
        } else {
            (stage - 1).coerceAtLeast(0)
        }
        return Outcome(nextStage, now + LADDER_DAYS[nextStage] * DAY_MS)
    }

    /**
     * Which lessons a checkup's results implicate. [accuracyByLesson] comes from bucketing each
     * answered item against the lesson scopes it falls inside — an item can implicate more than one
     * lesson, which is correct: a strong-preterite item missed in the contrast course is evidence
     * about both.
     */
    fun lessonsToFlag(accuracyByLesson: Map<String, Double>): Set<String> =
        accuracyByLesson.filterValues { it < LESSON_FLAG_ACCURACY }.keys
}
