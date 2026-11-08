package com.copiosa.domain.cloze

import com.copiosa.domain.model.FlashcardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ClozeSelectorTest {

    private val now = 10_000_000_000L
    private val day = 86_400_000L

    private fun entry(
        id: Long,
        lemma: String = "w$id",
        note: Long? = id,
        state: FlashcardState? = FlashcardState.REVIEW,
        cardDue: Long = now + 3 * day,
        interval: Double = 5.0,
        lapses: Int = 0,
        frozen: Boolean = false,
        words: Int = 8,
        pinned: Boolean = false,
        sentenceDue: Long = 0,
        shownAt: Long? = null,
        rank: Int? = null,
    ) = ClozePoolEntry(
        sentenceId = id, lemma = lemma, wordCount = words, pinned = pinned, sentenceDueAt = sentenceDue,
        lastShownAt = shownAt, noteId = note, cardState = state, cardDueAt = cardDue,
        cardIntervalDays = interval, cardLapses = lapses, cardSuspended = frozen, rank = rank,
    )

    private fun pick(pool: List<ClozePoolEntry>, size: Int = 10, missed: Set<Long> = emptySet(), focus: Set<Long> = emptySet()) =
        ClozeSelector.select(pool, size, now, missed, focus, Random(1))

    @Test
    fun `need follows what the deck says about the word`() {
        fun need(e: ClozePoolEntry, missed: Set<Long> = emptySet(), focus: Set<Long> = emptySet()) =
            ClozeSelector.needOf(e, now, missed, focus)

        assertEquals(ClozeNeed.FOCUS, need(entry(1), focus = setOf(1L)))
        assertEquals(ClozeNeed.MISSED_TODAY, need(entry(1), missed = setOf(1L)))
        assertEquals(ClozeNeed.LEARNING, need(entry(1, state = FlashcardState.RELEARNING)))
        assertEquals(ClozeNeed.LEECH, need(entry(1, lapses = 9, interval = 3.0)))
        assertEquals(ClozeNeed.DUE, need(entry(1, cardDue = now - day)))
        assertEquals(ClozeNeed.DUE_SOON, need(entry(1, cardDue = now + 3 * day)))
        assertEquals(ClozeNeed.ACTIVE, need(entry(1, cardDue = now + 10 * day, interval = 12.0)))
        assertEquals(ClozeNeed.ACTIVE, need(entry(1, state = FlashcardState.NEW, cardDue = now - day)))
        assertEquals(ClozeNeed.SETTLED, need(entry(1, cardDue = now + 30 * day, interval = 40.0)))
        assertEquals(ClozeNeed.FROZEN, need(entry(1, frozen = true)))
        assertEquals(ClozeNeed.UNLINKED, need(entry(1, note = null, state = null)))
    }

    @Test
    fun `a mature card that is about to come due is not settled`() {
        assertTrue(ClozeSelector.isSettled(40.0, now + 30 * day, now))
        assertFalse(ClozeSelector.isSettled(40.0, now + 2 * day, now))
        assertFalse(ClozeSelector.isSettled(10.0, now + 30 * day, now))
    }

    @Test
    fun `a sitting is filled most needed first`() {
        val pool = listOf(
            entry(1, cardDue = now + 30 * day, interval = 40.0),
            entry(2, cardDue = now + 3 * day),
            entry(3, cardDue = now - day),
            entry(4, state = FlashcardState.RELEARNING),
            entry(5),
            entry(6, lapses = 10, interval = 2.0),
        )
        assertEquals(listOf(5L, 4L, 6L, 3L, 2L, 1L), pick(pool, missed = setOf(5L)))
        assertEquals(listOf(1L, 5L, 4L), pick(pool, size = 3, missed = setOf(5L), focus = setOf(1L)))
    }

    @Test
    fun `frozen words are never offered`() {
        assertEquals(listOf(2L), pick(listOf(entry(1, frozen = true), entry(2))))
    }

    @Test
    fun `one sentence per word, even when the word has several`() {
        val pool = listOf(
            entry(1, lemma = "gato", note = 7),
            entry(2, lemma = "gato", note = 7),
            entry(3, lemma = "gatos", note = 7),
            entry(4, lemma = "perro", note = 8),
        )
        val chosen = pick(pool)
        assertEquals(2, chosen.size)
        assertTrue(4L in chosen)
    }

    @Test
    fun `within a tier pinned, unseen, due and comfortable sentences lead`() {
        val recent = now - 60_000
        assertEquals(1L, pick(listOf(entry(2), entry(1, pinned = true)), size = 1).single())
        assertEquals(1L, pick(listOf(entry(2, shownAt = recent), entry(1)), size = 1).single())
        assertEquals(1L, pick(listOf(entry(2, sentenceDue = now + day), entry(1)), size = 1).single())
        assertEquals(1L, pick(listOf(entry(2, words = 16), entry(1)), size = 1).single())
    }

    @Test
    fun `a recently shown sentence still comes back when nothing else is left`() {
        assertEquals(listOf(1L), pick(listOf(entry(1, shownAt = now - 1_000))))
    }

    @Test
    fun `the track walks the list in rank order and skips what is passed or unranked`() {
        val pool = listOf(
            entry(1, lemma = "tener", rank = 12),
            entry(2, lemma = "casa", rank = 80, note = null, state = null),
            entry(3, lemma = "yermo", rank = null),
            entry(4, lemma = "ser", rank = 1),
            entry(5, lemma = "ser", rank = 1),
            entry(6, lemma = "ir", rank = 5, frozen = true),
        )
        assertEquals(listOf(4L, 1L, 2L), ClozeSelector.selectTrack(pool, 10, passed = emptySet(), now = now))
        assertEquals(listOf(1L, 2L), ClozeSelector.selectTrack(pool, 10, passed = setOf("ser"), now = now))
    }
}
