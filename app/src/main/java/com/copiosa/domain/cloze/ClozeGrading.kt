package com.copiosa.domain.cloze

import com.copiosa.domain.model.ReviewRating

/**
 * Turns a multiple-choice answer into an FSRS grade.
 *
 * Two things make this more than `correct -> GOOD`. A four-option item has a **25% floor from pure
 * guessing**, so a lucky fast answer must not earn [ReviewRating.EASY] — FSRS's largest interval
 * jump — on an item never seen before. And without [ReviewRating.HARD], an answer the learner had to
 * work out inflates the schedule exactly as fast as an instant one.
 *
 * Thresholds scale with sentence length because the latency the runner reports starts when the item
 * is shown, so it includes *reading* a 4–18 word sentence. A flat "under three seconds" is
 * comfortably reachable at four words and unreachable at eighteen.
 */
object ClozeGrading {

    /** Reading-and-deciding budget before length is accounted for. */
    const val FAST_BASE_MS = 1_500L

    /** Added to the fast threshold per word of the sentence. */
    const val PER_WORD_MS = 250L

    /** Past this multiple of the fast threshold, a correct answer was worked out rather than known. */
    const val SLOW_MULTIPLE = 4

    /** Under this, a correct answer counts as instant recall. */
    fun fastThresholdMs(wordCount: Int): Long = FAST_BASE_MS + PER_WORD_MS * wordCount.coerceAtLeast(0)

    /** Past this, a correct answer counts as laboured. */
    fun slowThresholdMs(wordCount: Int): Long = fastThresholdMs(wordCount) * SLOW_MULTIPLE

    /**
     * The grade for a *typed* answer. Latency says nothing here — it is mostly typing — and there is
     * no guessing floor to guard against, so a word produced from memory is simply GOOD.
     */
    fun gradeForTyped(correct: Boolean): ReviewRating = if (correct) ReviewRating.GOOD else ReviewRating.AGAIN

    /**
     * [repetitions] is the sentence's own review count: on a first sighting a fast correct answer is
     * indistinguishable from a lucky guess, so it earns GOOD rather than EASY.
     */
    fun gradeFor(correct: Boolean, latencyMs: Long, wordCount: Int, repetitions: Int): ReviewRating {
        if (!correct) return ReviewRating.AGAIN
        return when {
            latencyMs > slowThresholdMs(wordCount) -> ReviewRating.HARD
            latencyMs < fastThresholdMs(wordCount) && repetitions > 0 -> ReviewRating.EASY
            else -> ReviewRating.GOOD
        }
    }
}
