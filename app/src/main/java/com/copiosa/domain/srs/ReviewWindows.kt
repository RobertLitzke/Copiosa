package com.copiosa.domain.srs

import java.time.Instant
import java.time.ZoneId

/**
 * Review-distance windows shared by word highlighting, the LLM vocab-hint pool, and passive review.
 * Kept in one place so the "how well does the learner know this word" thresholds stay consistent
 * across the renderer, the prompt builder, and the scheduler.
 */
object ReviewWindows {
    const val DAY_MS: Long = 86_400_000L

    /** The first instant of the calendar day [now] falls in, in [zone]. */
    fun startOfDay(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /**
     * The first instant of the day after [now], in [zone] — where a card goes when its note has
     * already been reviewed today. A calendar day rather than twenty-four hours, so an evening
     * review doesn't hold the sibling back until the following evening.
     */
    fun startOfNextDay(now: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

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
     * The most words that highlight at once: of those inside [HIGHLIGHT_DUE_MS], the ones due
     * soonest. With a backlog of a couple of thousand overdue cards the window alone tints a third of
     * a page, common words included, and a highlight on everything points at nothing.
     */
    const val HIGHLIGHT_MAX_WORDS: Int = 1000

    /**
     * A card due within this window gets a passive push-back when it turns up in a text or chat —
     * seeing the word counts as a light review.
     */
    const val PASSIVE_WINDOW_DAYS: Long = 14L
    const val PASSIVE_WINDOW_MS: Long = PASSIVE_WINDOW_DAYS * DAY_MS

    /** The longest a single passive sighting may push a card's review out. */
    const val PASSIVE_MAX_PUSH_DAYS: Double = 30.0

    /**
     * A correct multiple-choice cloze on a sentence a card teaches is worth a quarter of a review;
     * a miss pulls the card back by the same amount. Symmetric on purpose — a word missed in context
     * is worth resurfacing sooner, and the magnitude is bounded by the caller answering at most one
     * cloze per card per session.
     */
    const val CLOZE_REVIEW_FRACTION: Double = 4.0

    /** Half [PASSIVE_MAX_PUSH_DAYS], keeping the ratio to a passive sighting the same at both ends. */
    const val CLOZE_MAX_PUSH_DAYS: Double = 15.0

    /**
     * Where a passive sighting moves a card's review to, or null when it shouldn't move at all —
     * out of the [PASSIVE_WINDOW_MS] window, or a card with no interval yet to extend.
     *
     * Pure so the arithmetic can be tested without a database; the caller applies the gate on
     * suspension and does the write.
     */
    fun passiveNudgeDueAt(dueAt: Long, intervalDays: Double, now: Long): Long? {
        if (dueAt > now + PASSIVE_WINDOW_MS) return null
        val pushDays = minOf(PASSIVE_MAX_PUSH_DAYS, intervalDays / 2.0)
        if (pushDays <= 0.0) return null
        return dueAt + (pushDays * DAY_MS).toLong()
    }

    /**
     * Where a graded cloze answer moves the linked card's review to, or null when it shouldn't move.
     *
     * A [correct] answer pushes the due date out, a miss pulls it back — never past [now], so a run
     * of misses can't drag a card into the past. A card that has never been reviewed
     * (`intervalDays == 0`) doesn't move either way: there is no interval to take a quarter of.
     */
    fun clozeNudgeDueAt(dueAt: Long, intervalDays: Double, now: Long, correct: Boolean): Long? {
        if (dueAt > now + PASSIVE_WINDOW_MS) return null
        val days = minOf(CLOZE_MAX_PUSH_DAYS, intervalDays / CLOZE_REVIEW_FRACTION)
        if (days <= 0.0) return null
        val deltaMs = (days * DAY_MS).toLong()
        return if (correct) dueAt + deltaMs else maxOf(now, dueAt - deltaMs)
    }

    /**
     * Interval at which a card counts as "mature" — Anki's cutoff, and the line between having a
     * word in the deck and actually knowing it. Read by the vocab list's status dot and by the
     * frequency-coverage figures, which report deck membership and maturity separately.
     */
    const val MATURE_THRESHOLD_DAYS: Double = 21.0

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
