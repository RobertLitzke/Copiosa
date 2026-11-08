package com.copiosa.domain.srs

import com.copiosa.data.local.FlashcardEntity

/**
 * A leech is a card that keeps being forgotten: reviews go into it and nothing stays. Anki's term
 * and Anki's threshold, with one difference — here it is only ever *pointed out*. Nothing is frozen
 * on the learner's behalf; what to do about a word that won't stick (rewrite it, give it an image,
 * set it aside) is theirs to decide.
 *
 * Derived from the schedule a card already carries, so there is nothing to migrate or keep in step.
 * Per card, like freezing: a word can be a leech to produce and no trouble at all to recognise.
 */
object Leech {
    /** Lapses at which a card first counts — Anki's default. */
    const val LAPSES: Int = 8

    /** After the first notice, how many more lapses earn another (half the threshold, as in Anki). */
    const val REWARN_EVERY: Int = 4

    /**
     * Whether a card with this schedule is a leech *now*.
     *
     * The lapse count is a lifetime figure and arrives with an imported deck's history, so on its
     * own it would flag words that struggled years ago and have long since settled. A card that has
     * reached a mature interval has stopped being a problem, whatever it cost to get there; a frozen
     * one has already been dealt with.
     */
    fun isLeech(lapses: Int, intervalDays: Double, suspended: Boolean): Boolean =
        !suspended && lapses >= LAPSES && intervalDays < ReviewWindows.MATURE_THRESHOLD_DAYS

    /**
     * Whether going from [before] lapses to [after] is a moment worth mentioning: reaching [LAPSES],
     * and every [REWARN_EVERY] after it. Rare by construction — a note on every miss of a word
     * already known to be difficult would be nagging.
     */
    fun crossedInto(before: Int, after: Int): Boolean =
        after > before && after >= LAPSES && (after - LAPSES) % REWARN_EVERY == 0
}

val FlashcardEntity.isLeech: Boolean get() = Leech.isLeech(lapses, intervalDays, suspended)
