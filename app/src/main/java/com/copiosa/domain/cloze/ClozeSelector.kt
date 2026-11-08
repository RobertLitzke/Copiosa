package com.copiosa.domain.cloze

import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.srs.Leech
import com.copiosa.domain.srs.ReviewWindows
import kotlin.random.Random

/**
 * One banked sentence as the selector sees it: what it is, how its own practice has gone, and — the
 * part that matters most — how the word it practises is doing in the deck.
 *
 * The `card*` fields are null for a sentence with no card behind it (a frequent word the learner
 * never saved, or a card since deleted).
 */
data class ClozePoolEntry(
    val sentenceId: Long,
    val lemma: String,
    val wordCount: Int,
    val pinned: Boolean = false,
    /** When the sentence's own memory estimate says it is worth asking again. */
    val sentenceDueAt: Long = 0,
    val sentenceRepetitions: Int = 0,
    val lastShownAt: Long? = null,
    val noteId: Long? = null,
    val cardState: FlashcardState? = null,
    val cardDueAt: Long? = null,
    val cardIntervalDays: Double? = null,
    val cardLapses: Int? = null,
    val cardSuspended: Boolean? = null,
    /** The answer's place in the frequency list, for the common-words track. */
    val rank: Int? = null,
)

/**
 * How much the word behind a sentence needs practice right now, most urgent first. The order of the
 * constants is the order a sitting is filled in.
 */
enum class ClozeNeed {
    /** Asked for by name — "practise these words". */
    FOCUS,

    /** Forgotten in a review today. */
    MISSED_TODAY,

    /** Being learned or relearned: the deck is still showing it within the day. */
    LEARNING,

    /** Forgotten again and again; see [Leech]. */
    LEECH,

    /** Due for review now, or overdue. */
    DUE,

    /** Coming up within the week. */
    DUE_SOON,

    /** In the deck and not yet settled. */
    ACTIVE,

    /** No card behind it. */
    UNLINKED,

    /** A mature card not due for a while: it has stopped needing this. */
    SETTLED,

    /** The learner set the card aside. */
    FROZEN,
}

/**
 * Chooses what a cloze sitting holds.
 *
 * The bank used to be worked through as a schedule of its own — soonest-due sentence first — which
 * made it a second queue beside the deck, and one with no idea what the learner was studying. This
 * turns it round: **the deck says which words need practice, and the bank supplies sentences for
 * them.** A word missed this morning, one in relearning, a leech, a word due today: those are what a
 * sitting is for. The sentence's own memory estimate still counts, but only to break ties — a
 * sentence answered easily last week waits behind one never seen.
 *
 * Pure: the caller reads the pool and the day's misses, this decides.
 */
object ClozeSelector {

    /** A sentence shown this recently is not shown again unless there is nothing else. */
    const val RECENT_MS: Long = 20 * 60_000L

    /** The length that works in every mode — short enough to hear, long enough to constrain. */
    val PREFERRED_WORDS = 6..12

    /**
     * Whether a card with this schedule has stopped needing sentence practice: mature, and not due
     * for at least a week. The miner uses the same line to decide which sentences may be rotated out
     * and which cards are not worth mining for, so the two can never disagree and churn.
     */
    fun isSettled(intervalDays: Double, dueAt: Long, now: Long): Boolean =
        intervalDays >= ReviewWindows.MATURE_THRESHOLD_DAYS && dueAt > now + ReviewWindows.HIGHLIGHT_DUE_MS

    fun needOf(
        entry: ClozePoolEntry,
        now: Long,
        missedToday: Set<Long> = emptySet(),
        focus: Set<Long> = emptySet(),
    ): ClozeNeed {
        val noteId = entry.noteId
        if (noteId != null && noteId in focus) return ClozeNeed.FOCUS
        val state = entry.cardState ?: return ClozeNeed.UNLINKED
        if (noteId == null) return ClozeNeed.UNLINKED
        if (entry.cardSuspended == true) return ClozeNeed.FROZEN
        val interval = entry.cardIntervalDays ?: 0.0
        val dueAt = entry.cardDueAt ?: now
        return when {
            noteId in missedToday -> ClozeNeed.MISSED_TODAY
            state == FlashcardState.LEARNING || state == FlashcardState.RELEARNING -> ClozeNeed.LEARNING
            Leech.isLeech(entry.cardLapses ?: 0, interval, suspended = false) -> ClozeNeed.LEECH
            // A card never studied is not "due" in any useful sense: there is nothing to reinforce.
            state == FlashcardState.NEW -> ClozeNeed.ACTIVE
            dueAt <= now -> ClozeNeed.DUE
            dueAt <= now + ReviewWindows.HIGHLIGHT_DUE_MS -> ClozeNeed.DUE_SOON
            isSettled(interval, dueAt, now) -> ClozeNeed.SETTLED
            else -> ClozeNeed.ACTIVE
        }
    }

    /**
     * Up to [size] sentence ids, most needed first, **one per word** — two sentences for one word in
     * a sitting hand the second its answer.
     *
     * Within a tier of need: a sentence the learner pinned, then one not seen in the last
     * [RECENT_MS], then one its own estimate says is due, then one of a comfortable length. [random]
     * settles the rest, so two sittings in a row are not the same fifteen.
     */
    fun select(
        pool: List<ClozePoolEntry>,
        size: Int,
        now: Long,
        missedToday: Set<Long> = emptySet(),
        focus: Set<Long> = emptySet(),
        random: Random = Random.Default,
    ): List<Long> {
        if (size <= 0) return emptyList()
        val ranked = pool
            .map { Scored(it, needOf(it, now, missedToday, focus), random.nextInt()) }
            // A frozen card's sentences are never offered: the learner said to stop asking.
            .filter { it.need != ClozeNeed.FROZEN }
            .sortedWith(
                compareBy<Scored> { it.need.ordinal }
                    .thenBy { !it.entry.pinned }
                    .thenBy { recentlyShown(it.entry, now) }
                    .thenBy { it.entry.sentenceDueAt > now }
                    .thenBy { it.entry.wordCount !in PREFERRED_WORDS }
                    .thenBy { it.tieBreak },
            )
        return onePerWord(ranked.map { it.entry }, size)
    }

    /**
     * The common-words track: sentences for the frequency list's words in rank order, skipping any
     * word already [passed]. Unlike [select] it does not care whether the learner has a card — the
     * point is to walk the commonest words of the language, carded or not, and find the gaps.
     */
    fun selectTrack(pool: List<ClozePoolEntry>, size: Int, passed: Set<String>, now: Long): List<Long> {
        if (size <= 0) return emptyList()
        val ranked = pool
            .filter { it.rank != null && it.lemma !in passed && it.cardSuspended != true }
            .sortedWith(
                compareBy<ClozePoolEntry> { it.rank }
                    .thenBy { recentlyShown(it, now) }
                    .thenBy { it.wordCount !in PREFERRED_WORDS }
                    .thenBy { it.sentenceId },
            )
        return onePerWord(ranked, size)
    }

    private fun recentlyShown(entry: ClozePoolEntry, now: Long): Boolean =
        entry.lastShownAt?.let { now - it < RECENT_MS } ?: false

    private fun onePerWord(ranked: List<ClozePoolEntry>, size: Int): List<Long> {
        val seenLemmas = HashSet<String>()
        val seenNotes = HashSet<Long>()
        val out = ArrayList<Long>(size)
        for (entry in ranked) {
            if (out.size >= size) break
            if (!seenLemmas.add(entry.lemma)) continue
            if (entry.noteId != null && !seenNotes.add(entry.noteId)) continue
            out += entry.sentenceId
        }
        return out
    }

    private class Scored(val entry: ClozePoolEntry, val need: ClozeNeed, val tieBreak: Int)
}
