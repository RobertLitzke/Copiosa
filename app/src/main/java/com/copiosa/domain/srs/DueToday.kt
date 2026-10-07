package com.copiosa.domain.srs

/**
 * The learner's own ceiling on how many cards they meet for the first time in a day.
 *
 * Without one, anything that mints cards in bulk — a frequency seed, an Anki import, a note gaining
 * its second direction — arrives as a single wall of "due" the morning after. The limit turns that
 * into a queue: the cards are all still there, and a fixed number of them join each day's reviews.
 * It is a setting, not a lock: [UNLIMITED] is one of the choices, and so is zero, for clearing a
 * backlog of reviews before taking on anything new.
 */
object NewCardLimit {
    /** Stored value meaning no ceiling; every new card that is due is on offer, as before the setting existed. */
    const val UNLIMITED = -1

    const val DEFAULT = 20

    /** What the settings picker offers. */
    val CHOICES = listOf(UNLIMITED, 0, 5, 10, 20, 30, 50)

    fun isUnlimited(perDay: Int): Boolean = perDay < 0

    /**
     * New cards still allowed today under [perDay], given that [introducedToday] have already had
     * their first review since midnight. [Int.MAX_VALUE] when there is no limit.
     */
    fun remaining(perDay: Int, introducedToday: Int): Int =
        if (isUnlimited(perDay)) Int.MAX_VALUE else (perDay - introducedToday).coerceAtLeast(0)
}

/**
 * What is on offer to review today, as against everything that has come due.
 *
 * Counted in words, not cards — see `FlashcardDao.dueSplit` for why. [reviews] are words with a
 * card that has been studied before and is due again: those are never held back, because putting
 * off a review only makes it harder. [newToday] are the never-studied words today's allowance still
 * admits, and [newHeld] the ones waiting for a later day.
 */
data class DueToday(val reviews: Int, val newToday: Int, val newHeld: Int) {
    /** Today's reviews: the number worth showing, and the most a "review everything" session holds. */
    val total: Int get() = reviews + newToday

    companion object {
        val NONE = DueToday(0, 0, 0)

        /**
         * [reviewNotes] and [newNotes] are the due words split by whether any of their due cards has
         * been studied; [allowance] is [NewCardLimit.remaining].
         */
        fun of(reviewNotes: Int, newNotes: Int, allowance: Int): DueToday {
            val admitted = minOf(newNotes, allowance).coerceAtLeast(0)
            return DueToday(reviews = reviewNotes, newToday = admitted, newHeld = newNotes - admitted)
        }
    }
}
