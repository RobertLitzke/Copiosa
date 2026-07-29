package com.copiosa.domain.quiz

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * A rendered pool cue, as the session builder sees it — a plain projection of QuizCueEntity so the
 * domain layer never imports Room.
 */
data class ReadyCue(
    val id: Long,
    val type: CueType,
    val valueKey: String,
    val displayText: String,
    val filePath: String,
    // Null for clips adopted from the on-disk cache (playback uses a completion callback anyway).
    val durationMs: Long?,
    // Set only for PHRASE cues: the pre-seeded correct + distractor answers. Null for algorithmic cues.
    val options: PhraseOptions? = null,
)

/** One quiz question: the clip to play and the digit options to reveal once it finishes. */
data class QuizQuestion(
    val cueId: Long,
    val cueType: CueType,
    val audioPath: String,
    val durationMs: Long?,
    val displayAnswer: String,
    val options: List<String>,
    val answerIndex: Int,
)

/**
 * Assembles a session from the rendered pool: draws [count] cues, then generates fresh confusable
 * distractors per cue — at session time, not pool time, so repeat runs over the same audio see varied
 * option sets. Used by both the standalone quiz and routine synthesis.
 */
class QuizSessionBuilder(private val random: Random = Random.Default) {

    /** [difficultyFor] maps a cue type to its proficiency difficulty level (0..2). */
    fun build(
        cues: List<ReadyCue>,
        config: QuizPoolConfig,
        count: Int,
        optionCount: Int = OPTION_COUNT,
        difficultyFor: (CueType) -> Int = { 0 },
    ): List<QuizQuestion> = cues.shuffled(random).take(count).mapNotNull { cue ->
        // Phrase cues (Restaurant, …) carry their answer options pre-seeded — no algorithmic
        // generator, no natural order — so they build from stored options and shuffle.
        if (cue.type.kind == CueKind.PHRASE) return@mapNotNull buildPhraseQuestion(cue, optionCount)
        val value = CueValue.parse(cue.type, cue.valueKey) ?: return@mapNotNull null
        val generator = CueGenerators.forType(cue.type)
        val need = optionCount - 1
        // Over-sample candidates so we can choose how many fall below vs. above the answer — the
        // generator returns them in priority order (curated → mutations → offsets), so a bigger count
        // just yields more of the same good near-misses.
        val pool = generator.distractors(value, config, random, need * CANDIDATE_FACTOR, difficultyFor(cue.type))
        // A cue whose range can't produce a real choice (min one distractor) is skipped.
        if (pool.isEmpty()) return@mapNotNull null
        val distractors = balancedDistractors(pool, value, need)
        // Present the options in natural order (numeric / chronological), not shuffled, so the learner
        // scans a sorted list and the audio parsing — not the layout — is what's being tested.
        val optionValues = (distractors + value).sortedBy { it.sortKey() }
        QuizQuestion(
            cueId = cue.id,
            cueType = cue.type,
            audioPath = cue.filePath,
            durationMs = cue.durationMs,
            displayAnswer = cue.displayText,
            options = optionValues.map { generator.displayText(it) },
            answerIndex = optionValues.indexOf(value),
        )
    }

    /**
     * Builds one phrase question from its stored [PhraseOptions]: the correct answer plus a random
     * [optionCount]-1 of the (up to four) stored distractors, shuffled — phrases have no natural order,
     * so there is nothing to sort. Returns null if the cue is missing options or lacks enough
     * distractors to fill the grid.
     */
    private fun buildPhraseQuestion(cue: ReadyCue, optionCount: Int): QuizQuestion? {
        val opts = cue.options ?: return null
        val need = optionCount - 1
        val distractors = opts.distractors.shuffled(random).take(need)
        if (distractors.size < need) return null
        val options = (distractors + opts.correct).shuffled(random)
        return QuizQuestion(
            cueId = cue.id,
            cueType = cue.type,
            audioPath = cue.filePath,
            durationMs = cue.durationMs,
            displayAnswer = opts.correct,
            options = options,
            answerIndex = options.indexOf(opts.correct),
        )
    }

    /**
     * Picks [need] distractors from [pool] so the answer lands in a uniformly-random sorted slot,
     * countering the middle-bias a fixed sorted display otherwise shows: distractors cluster
     * symmetrically around the answer, so it almost always sorts into a middle slot. We choose a target
     * slot `r` (how many distractors sit below the answer) uniformly over the range the pool can
     * actually field, keeping each side's priority order. A range too tight to balance (few candidates
     * on one side) just takes the best available.
     */
    private fun balancedDistractors(pool: List<CueValue>, answer: CueValue, need: Int): List<CueValue> {
        val key = answer.sortKey()
        val below = pool.filter { it.sortKey() < key }
        val above = pool.filter { it.sortKey() > key }
        val lo = maxOf(0, need - above.size)
        val hi = minOf(need, below.size)
        if (lo > hi) return pool.take(need) // Not enough on one side to hit any balanced slot.
        val r = random.nextInt(lo, hi + 1)
        return below.take(r) + above.take(need - r)
    }

    companion object {
        const val OPTION_COUNT = 4

        /** Candidate distractors requested per needed slot, so [balancedDistractors] has room to pick. */
        const val CANDIDATE_FACTOR = 4

        /** Manual listening-speed bounds (±50% of normal), for the standalone selector / routine override. */
        const val MANUAL_SPEED_MIN = 0.5f
        const val MANUAL_SPEED_MAX = 1.5f
    }
}

/**
 * Soft time pressure: a correct answer's points decay exponentially with response latency (measured
 * from options-reveal, not audio start) down to a floor — slow still counts, fast counts more — and a
 * running correct streak multiplies the result.
 */
object QuizScoring {
    private const val BASE_POINTS = 1000.0
    private const val DECAY_MS = 3000.0
    private const val FLOOR_POINTS = 100
    private const val STREAK_BONUS_PER = 0.1
    private const val STREAK_CAP = 5

    /**
     * [streak] is the count of consecutive correct answers BEFORE this one. [speed] is the playback
     * rate the clip was heard at (1.0 = normal): faster listening is harder, so points scale with it —
     * a correct answer at 1.5× is worth 50% more, at 0.5× half.
     */
    fun points(correct: Boolean, latencyMs: Long, streak: Int, speed: Float = 1.0f): Int {
        if (!correct) return 0
        val decayed = max(FLOOR_POINTS, (BASE_POINTS * exp(-latencyMs / DECAY_MS)).roundToInt())
        return (decayed * streakMultiplier(streak) * speed).roundToInt()
    }

    fun streakMultiplier(streak: Int): Double = 1.0 + STREAK_BONUS_PER * min(streak, STREAK_CAP)
}

/** One answered question in a session, for the results screen and per-topic stats. */
data class QuizAnswer(
    val question: QuizQuestion,
    val selectedIndex: Int,
    val latencyMs: Long,
    val points: Int,
) {
    val correct: Boolean get() = selectedIndex == question.answerIndex
}

/** End-of-session summary. */
data class SessionStats(
    val answered: Int,
    val correct: Int,
    val avgLatencyMs: Long,
    val bestStreak: Int,
    val score: Int,
) {
    companion object {
        fun from(answers: List<QuizAnswer>): SessionStats {
            var streak = 0
            var best = 0
            answers.forEach {
                streak = if (it.correct) streak + 1 else 0
                best = max(best, streak)
            }
            return SessionStats(
                answered = answers.size,
                correct = answers.count { it.correct },
                avgLatencyMs = if (answers.isEmpty()) 0 else answers.map { it.latencyMs }.average().roundToLong(),
                bestStreak = best,
                score = answers.sumOf { it.points },
            )
        }
    }
}
