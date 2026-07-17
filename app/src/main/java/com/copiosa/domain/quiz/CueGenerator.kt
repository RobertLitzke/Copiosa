package com.copiosa.domain.quiz

import kotlin.random.Random

/**
 * One cue subject's generation logic: which values a pool samples, how each is displayed and (when
 * possible) phrased locally, and which near-miss values make confusable answer options. Adding a new
 * quiz subject means adding a [CueType] and an implementation here; the pool seeder, session builder,
 * and UI are generator-agnostic.
 */
interface CueGenerator {
    val type: CueType

    /** Up to [count] distinct values, biased toward the language's known confusion sets. */
    fun generateValues(count: Int, config: QuizPoolConfig, random: Random): List<CueValue>

    /**
     * Up to [count] wrong-answer values deliberately close to [answer] in sound or structure —
     * curated confusion pairs first, then structural mutations, then small offsets. Higher
     * [difficultyLevel] (0..2, from the proficiency bucket) packs in more of the nastiest tier.
     */
    fun distractors(
        answer: CueValue,
        config: QuizPoolConfig,
        random: Random,
        count: Int,
        difficultyLevel: Int = 0,
    ): List<CueValue>

    /**
     * The spoken-words form when it can be produced locally, or null when this cue is best left to the
     * LLM: colloquial time phrasing, or a number past the range the local speller is CLDR-correct for
     * (large cardinals). Under [TimeStyle.MIXED] the time style is coin-flipped per cue here, so callers
     * learn which cues to send to the LLM simply by collecting the nulls. A cue that returns null and
     * gets no usable LLM phrasing is dropped from the pool — never approximated locally.
     */
    fun localSpokenText(value: CueValue, config: QuizPoolConfig, speller: NumberSpeller, random: Random): String?

    /** The digit form shown as an answer option ("73" / "6:15"). */
    fun displayText(value: CueValue): String
}

object CueGenerators {
    val ALL: List<CueGenerator> = listOf(NumberCueGenerator, TimeCueGenerator)

    fun forType(type: CueType): CueGenerator = ALL.first { it.type == type }
}
