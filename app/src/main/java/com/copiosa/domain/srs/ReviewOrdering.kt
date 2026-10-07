package com.copiosa.domain.srs

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.noteKey
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.vocab.freq.FrequencyBucket
import kotlin.math.pow
import kotlin.random.Random

/**
 * Decides which cards a study session contains and in what order.
 *
 * Pure: given the active deck, each card's frequency rank and the clock, it returns the session.
 * [com.copiosa.data.repository.FlashcardRepository] does the reading and writing and delegates the
 * choosing here, so the ordering rules — which are the part with actual judgement in them — can be
 * exercised without a database or a settings store.
 */
object ReviewOrdering {

    private const val HOUR_MS = 3_600_000L

    /**
     * A session of up to [limit] cards from [active] (already filtered to non-frozen cards).
     *
     * Due cards come first, with newly-saved words interleaved among due reviews so app-created cards
     * surface alongside imported Anki ones instead of sitting behind thousands of them. If fewer than
     * [limit] cards are due, the session studies ahead by appending the soonest-upcoming cards.
     *
     * A note's two directions are separate cards sharing a [FlashcardEntity.noteId]. Meeting both in
     * one sitting isn't a review — the first hands you the answer to the second — so a session holds
     * at most one card of a note, the first the ordering reaches. The other is never added back, not
     * even to fill a short session: a short session is the honest answer.
     *
     * [ranks] tilts the order toward commoner words; it is empty for a language with no bundled
     * frequency list, which leaves every rule below behaving exactly as it did before frequency data
     * existed.
     *
     * [newAllowance] is how many never-studied cards the session may hold — what is left of the
     * learner's daily limit (see [NewCardLimit.remaining]). The default is no limit, which is the
     * ordering exactly as it was before the limit existed. With a limit:
     *  - new cards past the allowance are simply not in the session, however short that leaves it;
     *  - the ones admitted are spread through *this sitting* rather than through the whole due
     *    pile. Proportional to the pile, twenty new cards behind seven hundred overdue reviews
     *    would surface one in thirty-five — the newly saved words waiting behind the backlog,
     *    which is the thing interleaving exists to prevent;
     *  - studying ahead never reaches for a new card. One dated in the future was put there to
     *    arrive later (a frequency seed staggers its words over weeks), and pulling it forward to
     *    fill a sitting would be the limit leaking.
     */
    fun buildSession(
        active: List<FlashcardEntity>,
        ranks: Map<Long, Int>,
        now: Long,
        limit: Int,
        random: Random = Random.Default,
        newAllowance: Int = Int.MAX_VALUE,
    ): List<FlashcardEntity> {
        if (limit <= 0) return emptyList()
        val (due, upcoming) = active.partition { it.dueAt <= now }
        // New cards (app-created + imported) shuffled together so neither source dominates, but with
        // frequent words drawn earlier; due reviews kept in due-date order (most overdue first),
        // nudged by frequency so that among cards due around the same time the commoner one leads.
        val newDue = weightedShuffle(due.filter { it.state == FlashcardState.NEW }, ranks, random)
        val reviewDue = due.filter { it.state != FlashcardState.NEW }.sortedBy(effectiveDue(ranks))
        val ahead = upcoming.sortedBy(effectiveDue(ranks))
        val ordered = if (newAllowance == Int.MAX_VALUE) {
            interleave(reviewDue, newDue) + ahead
        } else {
            // One per note before counting, so a word new in both directions spends one place of
            // the allowance, not two.
            val admitted = onePerNote(newDue).take(minOf(newAllowance.coerceAtLeast(0), limit))
            interleave(reviewDue.take(limit), admitted) + reviewDue.drop(limit) +
                ahead.filter { it.state != FlashcardState.NEW }
        }
        // Shed across the whole order rather than per group, so a note whose due card made the
        // session can't also supply its not-yet-due sibling as study-ahead.
        return onePerNote(ordered).take(limit)
    }

    /**
     * [active] without the cards whose note has already been seen today: a card is dropped when a
     * *sibling* of it was last reviewed at or after [startOfToday].
     *
     * The reviewed card itself stays — one failed this morning is in relearning and is meant to come
     * back — so this bars the other direction only. It is what the due date alone can't do: the
     * sibling was moved to tomorrow when the review happened, but a session short of due cards
     * studies ahead, and tomorrow's first card is exactly where it would look.
     */
    fun withoutSiblingsOfReviewed(active: List<FlashcardEntity>, startOfToday: Long): List<FlashcardEntity> {
        val reviewedToday = active.filter { (it.lastReviewedAt ?: Long.MIN_VALUE) >= startOfToday }
        if (reviewedToday.isEmpty()) return active
        val reviewedByNote = reviewedToday.groupBy({ it.noteKey }, { it.id })
        return active.filterNot { card -> reviewedByNote[card.noteKey]?.any { it != card.id } == true }
    }

    /**
     * The "frequent words" drill: only cards for words ranked at or above [maxRank], most common
     * first, and — unlike an ordinary session — not-yet-due cards are fair game.
     *
     * Studying a card early is the point here, and it needs no special grading. FSRS derives how much
     * a review strengthens a card from how due it was: answering one well before its date finds
     * retrievability still high, so it earns a smaller stability gain than the same answer given on
     * time. That is partial credit, already built in — see `EarlyReviewTest`, which also pins the
     * corollary that a card drilled very early can come due marginally *sooner* than its pending
     * date, because the new date is re-anchored from the answer rather than added to the old one.
     *
     * Everything else about the session is an ordinary one: the pool is the same active deck, and
     * it holds one card per note exactly as [buildSession] does, so the drill can't hand you a word
     * one way and then immediately ask for it the other.
     */
    fun buildFrequencySession(
        active: List<FlashcardEntity>,
        ranks: Map<Long, Int>,
        now: Long,
        limit: Int,
        maxRank: Int,
    ): List<FlashcardEntity> {
        if (limit <= 0) return emptyList()
        val scoped = active.mapNotNull { card -> ranks[card.id]?.takeIf { it <= maxRank }?.let { card to it } }
        val (due, ahead) = scoped.partition { (card, _) -> card.dueAt <= now }
        // Due cards first, so the drill never skips real work in favour of studying ahead.
        val ordered = (due.sortedBy { it.second } + ahead.sortedBy { it.second }).map { it.first }
        return onePerNote(ordered).take(limit)
    }

    /** [cards] in order, keeping only the first card met of each note. */
    private fun onePerNote(cards: List<FlashcardEntity>): List<FlashcardEntity> {
        val seenNotes = HashSet<Long>()
        return cards.filter { seenNotes.add(it.noteKey) }
    }

    /**
     * Sort key that pulls common words slightly forward: a card's due date, minus a bonus for how
     * frequent its word is. Deliberately small — hours, not days — so it only reorders cards due at
     * around the same time and can never jump a frequent word ahead of one that is genuinely overdue.
     */
    private fun effectiveDue(ranks: Map<Long, Int>): (FlashcardEntity) -> Long =
        { card -> card.dueAt - freqBonusMs(ranks[card.id]) }

    /** How far forward a word of this rank is pulled in the due order. */
    fun freqBonusMs(rank: Int?): Long = when {
        rank == null -> 0L
        rank <= FrequencyBucket.TOP_500.maxRank -> 12 * HOUR_MS
        rank <= FrequencyBucket.TOP_1K.maxRank -> 8 * HOUR_MS
        rank <= FrequencyBucket.TOP_2K.maxRank -> 4 * HOUR_MS
        else -> 0L
    }

    /**
     * Shuffles new cards, but with frequent words more likely to come out near the front.
     *
     * Efraimidis–Spirakis: give each card the key `u^(1/weight)` for a fresh uniform `u` and sort
     * descending. Sorting by weight alone would make the order deterministic and teach the whole top
     * 500 before anything else; this keeps the draw random, just tilted.
     *
     * Each card's key is drawn **once**, before the sort. `sortedByDescending { random… }` would
     * redraw it on every comparison, handing the sort an order that contradicts itself — which TimSort
     * notices on a list of a few dozen and throws "Comparison method violates its general contract".
     */
    private fun weightedShuffle(
        cards: List<FlashcardEntity>,
        ranks: Map<Long, Int>,
        random: Random,
    ): List<FlashcardEntity> =
        cards.map { card -> card to random.nextDouble().pow(1.0 / freqWeight(ranks[card.id])) }
            .sortedByDescending { it.second }
            .map { it.first }

    /** Relative draw weight for a new card of this rank; 1.0 is neutral. */
    fun freqWeight(rank: Int?): Double = when {
        rank == null -> 1.0
        rank <= FrequencyBucket.TOP_500.maxRank -> 2.0
        rank <= FrequencyBucket.TOP_1K.maxRank -> 1.5
        rank <= FrequencyBucket.TOP_2K.maxRank -> 1.25
        else -> 1.0
    }

    /** Round-robins two lists proportionally to their sizes, so the shorter one is spread throughout. */
    private fun <T> interleave(a: List<T>, b: List<T>): List<T> {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        val out = ArrayList<T>(a.size + b.size)
        var ia = 0
        var ib = 0
        // Emit from whichever list is proportionally "behind", measured by fractional progress.
        while (ia < a.size || ib < b.size) {
            val takeA = ib >= b.size ||
                (ia < a.size && (ia + 1).toDouble() / a.size <= (ib + 1).toDouble() / b.size)
            if (takeA) out.add(a[ia++]) else out.add(b[ib++])
        }
        return out
    }
}
