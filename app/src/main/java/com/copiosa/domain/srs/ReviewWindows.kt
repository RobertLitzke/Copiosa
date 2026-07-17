package com.copiosa.domain.srs

/**
 * Review-distance windows shared by word highlighting, the LLM vocab-hint pool, and passive review.
 * Kept in one place so the "how well does the learner know this word" thresholds stay consistent
 * across the renderer, the prompt builder, and the scheduler.
 */
object ReviewWindows {
    const val DAY_MS: Long = 86_400_000L

    /**
     * A card due more than this far out is "locked in" — well understood with a long upcoming review
     * period. Locked-in words are hidden from in-text highlighting and never sent to the model as
     * vocabulary hints, so they don't clutter reading material the learner already knows.
     */
    const val LOCKED_IN_DAYS: Long = 90L
    const val LOCKED_IN_MS: Long = LOCKED_IN_DAYS * DAY_MS

    /**
     * Every reader (Texts, chat, routines) highlights only saved words due within this window — i.e.
     * words the learner needs to review now or in the coming week — so reading material spotlights
     * what's worth restudying rather than every saved word.
     */
    const val HIGHLIGHT_DUE_DAYS: Long = 7L
    const val HIGHLIGHT_DUE_MS: Long = HIGHLIGHT_DUE_DAYS * DAY_MS

    /**
     * A card due within this window gets a passive push-back when it turns up in a text or chat —
     * seeing the word counts as a light review.
     */
    const val PASSIVE_WINDOW_DAYS: Long = 14L
    const val PASSIVE_WINDOW_MS: Long = PASSIVE_WINDOW_DAYS * DAY_MS

    /** The longest a single passive sighting may push a card's review out. */
    const val PASSIVE_MAX_PUSH_DAYS: Double = 30.0

    /**
     * The LLM vocab-hint pool prefers words due within this tight window (review-now/soon), but only if
     * there are at least [HINT_FALLBACK_MIN_CARDS] such cards; otherwise it widens to the locked-in
     * window ([LOCKED_IN_MS]) so the model still has enough vocabulary to weave in.
     */
    const val HINT_DUE_DAYS: Long = 7L
    const val HINT_DUE_MS: Long = HINT_DUE_DAYS * DAY_MS
    const val HINT_FALLBACK_MIN_CARDS: Int = 25

    /**
     * The due-date bound for the vocab-hint pool: the tight [HINT_DUE_MS] window when at least
     * [HINT_FALLBACK_MIN_CARDS] cards fall inside it ([dueSoonCount]), else the wider [LOCKED_IN_MS].
     */
    fun hintMaxDue(now: Long, dueSoonCount: Int): Long =
        now + if (dueSoonCount >= HINT_FALLBACK_MIN_CARDS) HINT_DUE_MS else LOCKED_IN_MS
}
