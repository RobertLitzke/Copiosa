package com.copiosa.domain.srs

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.FlashcardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Covers session composition: the pre-existing due/new interleave, the one-card-per-note rules, and the
 * frequency tilt layered on top of them.
 *
 * The tilt is meant to be a nudge, so the tests that matter most are the negative ones — that a
 * common word cannot jump ahead of a genuinely overdue card, and that a deck in a language with no
 * frequency list behaves exactly as it did before any of this existed.
 */
class ReviewOrderingTest {

    private val now = 1_000_000_000_000L
    private val day = 86_400_000L
    private val hour = 3_600_000L

    // --- the frequency tilt --------------------------------------------------

    @Test
    fun `among cards due at the same moment, the commoner word comes first`() {
        val rare = card(id = 1, dueAt = now)
        val common = card(id = 2, dueAt = now)

        val session = ReviewOrdering.buildSession(
            active = listOf(rare, common),
            ranks = mapOf(2L to 10),
            now = now,
            limit = 10,
        )

        assertEquals(listOf(2L, 1L), session.map { it.id })
    }

    @Test
    fun `the tilt cannot leapfrog a genuinely overdue card`() {
        // A week overdue, and not in the list at all.
        val overdue = card(id = 1, dueAt = now - 7 * day)
        // The commonest possible word, but only just due — the largest bonus is 12 hours.
        val commonJustDue = card(id = 2, dueAt = now)

        val session = ReviewOrdering.buildSession(
            active = listOf(commonJustDue, overdue),
            ranks = mapOf(2L to 1),
            now = now,
            limit = 10,
        )

        assertEquals("the backlog still comes first", listOf(1L, 2L), session.map { it.id })
    }

    @Test
    fun `the bonus is graded by band and absent outside the list`() {
        assertEquals(12 * hour, ReviewOrdering.freqBonusMs(1))
        assertEquals(12 * hour, ReviewOrdering.freqBonusMs(500))
        assertEquals(8 * hour, ReviewOrdering.freqBonusMs(501))
        assertEquals(8 * hour, ReviewOrdering.freqBonusMs(1000))
        assertEquals(4 * hour, ReviewOrdering.freqBonusMs(2000))
        assertEquals(0L, ReviewOrdering.freqBonusMs(2001))
        assertEquals(0L, ReviewOrdering.freqBonusMs(null))
    }

    @Test
    fun `new cards are drawn in a tilted but still random order`() {
        val cards = (1L..200L).map { card(id = it, dueAt = now, state = FlashcardState.NEW) }
        // Half the deck is top-500 vocabulary, half is unlisted.
        val ranks = cards.filter { it.id % 2 == 0L }.associate { it.id to 100 }

        val first50 = ReviewOrdering
            .buildSession(cards, ranks, now, limit = 50, random = Random(7))
            .count { it.id % 2 == 0L }

        assertTrue("frequent words should be over-represented early, got $first50/50", first50 > 25)
        assertTrue("but not to the exclusion of everything else, got $first50/50", first50 < 50)
    }

    @Test
    fun `the draw is genuinely random, not a fixed order`() {
        val cards = (1L..50L).map { card(id = it, dueAt = now, state = FlashcardState.NEW) }
        val ranks = cards.associate { it.id to 100 }

        val a = ReviewOrdering.buildSession(cards, ranks, now, limit = 50, random = Random(1))
        val b = ReviewOrdering.buildSession(cards, ranks, now, limit = 50, random = Random(2))

        assertFalse("equal weights must not produce a deterministic order", a.map { it.id } == b.map { it.id })
    }

    @Test
    fun `a language with no frequency list orders purely by due date`() {
        val cards = listOf(
            card(id = 1, dueAt = now - day),
            card(id = 2, dueAt = now - 2 * day),
            card(id = 3, dueAt = now - 3 * day),
        )

        val session = ReviewOrdering.buildSession(cards, ranks = emptyMap(), now = now, limit = 10)

        assertEquals(listOf(3L, 2L, 1L), session.map { it.id })
    }

    // --- the rules that predate frequency ------------------------------------

    @Test
    fun `new cards are spread through the due backlog rather than queued behind it`() {
        val reviews = (1L..10L).map { card(id = it, dueAt = now - it * day) }
        val fresh = (11L..12L).map { card(id = it, dueAt = now, state = FlashcardState.NEW) }

        val session = ReviewOrdering.buildSession(reviews + fresh, emptyMap(), now, limit = 12, random = Random(3))

        val newPositions = session.withIndex().filter { it.value.id >= 11L }.map { it.index }
        assertEquals(2, newPositions.size)
        assertTrue("new cards should not both be at the very end: $newPositions", newPositions.first() < 6)
    }

    @Test
    fun `a session holds one card of a note, even when it runs short`() {
        val forward = card(id = 1, dueAt = now - 2 * day, note = 7)
        val reverse = card(id = 2, dueAt = now - day, note = 7)
        val other = card(id = 3, dueAt = now)

        val session = ReviewOrdering.buildSession(listOf(forward, reverse, other), emptyMap(), now, limit = 10)

        // Seeing one side hands you the answer to the other. The second card is not held back to
        // the end, it is left out: there was room for it, and a short session is still the answer.
        assertEquals(listOf(1L, 3L), session.map { it.id })
    }

    @Test
    fun `study-ahead cannot bring in the sibling of a card already in the session`() {
        val due = card(id = 1, dueAt = now - day, note = 7)
        val siblingTomorrow = card(id = 2, dueAt = now + day, note = 7)
        val later = card(id = 3, dueAt = now + 5 * day)

        val session = ReviewOrdering.buildSession(listOf(due, siblingTomorrow, later), emptyMap(), now, limit = 10)

        assertEquals(listOf(1L, 3L), session.map { it.id })
    }

    @Test
    fun `cards that are not stored yet are each their own note`() {
        // No note id means no siblings — which is also what stops a test's rows collapsing into one.
        val cards = (1L..3L).map { card(id = it, dueAt = now - it * day) }
        assertEquals(3, ReviewOrdering.buildSession(cards, emptyMap(), now, limit = 10).size)
    }

    // --- one review of a note per day -----------------------------------------

    @Test
    fun `a card whose sibling was reviewed today is out, the reviewed card itself stays`() {
        val startOfToday = now - 6 * 3_600_000L
        val reviewed = card(id = 1, dueAt = now + 600_000L, note = 7, lastReviewedAt = now - 3_600_000L)
        val sibling = card(id = 2, dueAt = now + day, note = 7)
        val unrelated = card(id = 3, dueAt = now)

        val eligible = ReviewOrdering.withoutSiblingsOfReviewed(listOf(reviewed, sibling, unrelated), startOfToday)

        // The reviewed card may well be in relearning and due back in minutes; only the other
        // direction is barred.
        assertEquals(listOf(1L, 3L), eligible.map { it.id })
    }

    @Test
    fun `a sibling reviewed yesterday bars nothing`() {
        val startOfToday = now - 6 * 3_600_000L
        val reviewed = card(id = 1, dueAt = now + 10 * day, note = 7, lastReviewedAt = startOfToday - 1)
        val sibling = card(id = 2, dueAt = now, note = 7)

        assertEquals(2, ReviewOrdering.withoutSiblingsOfReviewed(listOf(reviewed, sibling), startOfToday).size)
    }

    @Test
    fun `when both directions were reviewed today each bars the other`() {
        val startOfToday = now - 6 * 3_600_000L
        val a = card(id = 1, dueAt = now, note = 7, lastReviewedAt = now - 1000)
        val b = card(id = 2, dueAt = now, note = 7, lastReviewedAt = now - 2000)

        assertTrue(ReviewOrdering.withoutSiblingsOfReviewed(listOf(a, b), startOfToday).isEmpty())
    }

    @Test
    fun `a short session studies ahead, soonest first`() {
        val due = card(id = 1, dueAt = now - day)
        val soon = card(id = 2, dueAt = now + day)
        val later = card(id = 3, dueAt = now + 30 * day)

        val session = ReviewOrdering.buildSession(listOf(later, soon, due), emptyMap(), now, limit = 3)

        assertEquals(listOf(1L, 2L, 3L), session.map { it.id })
    }

    @Test
    fun `a non-positive limit yields nothing`() {
        val cards = listOf(card(id = 1, dueAt = now))
        assertTrue(ReviewOrdering.buildSession(cards, emptyMap(), now, limit = 0).isEmpty())
        assertTrue(ReviewOrdering.buildFrequencySession(cards, mapOf(1L to 1), now, 0, 500).isEmpty())
    }

    // --- the frequent-word drill ---------------------------------------------

    @Test
    fun `the drill takes only words inside the chosen band`() {
        val cards = listOf(
            card(id = 1, dueAt = now),
            card(id = 2, dueAt = now),
            card(id = 3, dueAt = now),
        )
        val ranks = mapOf(1L to 50, 2L to 900, 3L to 1500)

        val session = ReviewOrdering.buildFrequencySession(cards, ranks, now, limit = 10, maxRank = 1000)

        assertEquals(listOf(1L, 2L), session.map { it.id })
    }

    @Test
    fun `the drill admits cards that are not due yet`() {
        // The whole point: a common word worth practising need not have come round again.
        val notDue = card(id = 1, dueAt = now + 30 * day)

        val session = ReviewOrdering.buildFrequencySession(listOf(notDue), mapOf(1L to 5), now, 10, 500)

        assertEquals(listOf(1L), session.map { it.id })
    }

    @Test
    fun `the drill still does the due work first`() {
        val notDueButCommoner = card(id = 1, dueAt = now + 10 * day)
        val dueButRarer = card(id = 2, dueAt = now - day)

        val session = ReviewOrdering.buildFrequencySession(
            listOf(notDueButCommoner, dueButRarer),
            ranks = mapOf(1L to 5, 2L to 400),
            now = now,
            limit = 10,
            maxRank = 500,
        )

        assertEquals("real reviews before studying ahead", listOf(2L, 1L), session.map { it.id })
    }

    @Test
    fun `the drill orders by rank within each half`() {
        val cards = (1L..4L).map { card(id = it, dueAt = now - day) }
        val ranks = mapOf(1L to 400, 2L to 3, 3L to 120, 4L to 60)

        val session = ReviewOrdering.buildFrequencySession(cards, ranks, now, limit = 10, maxRank = 500)

        assertEquals(listOf(2L, 4L, 3L, 1L), session.map { it.id })
    }

    @Test
    fun `the drill is empty when the deck holds none of the band's words`() {
        val cards = listOf(card(id = 1, dueAt = now))
        assertTrue(ReviewOrdering.buildFrequencySession(cards, emptyMap(), now, 10, 500).isEmpty())
    }

    @Test
    fun `the drill holds one card of a note, exactly as an ordinary session does`() {
        val forward = card(id = 1, dueAt = now, note = 7)
        val reverse = card(id = 2, dueAt = now, note = 7)
        val other = card(id = 3, dueAt = now)

        val session = ReviewOrdering.buildFrequencySession(
            listOf(forward, reverse, other),
            ranks = mapOf(1L to 5, 2L to 5, 3L to 400),
            now = now,
            limit = 2,
            maxRank = 500,
        )

        assertEquals("the reverse card must not follow its own forward", listOf(1L, 3L), session.map { it.id })
    }

    @Test
    fun `the drill leaves the second card out even when nothing else is left`() {
        val forward = card(id = 1, dueAt = now, note = 7)
        val reverse = card(id = 2, dueAt = now, note = 7)

        val session = ReviewOrdering.buildFrequencySession(
            listOf(forward, reverse),
            ranks = mapOf(1L to 5, 2L to 5),
            now = now,
            limit = 10,
            maxRank = 500,
        )

        assertEquals(listOf(1L), session.map { it.id })
    }

    // --- the daily new-card limit ---------------------------------------------

    private fun newCard(id: Long, note: Long = 0, dueAt: Long = now) =
        card(id = id, dueAt = dueAt, state = FlashcardState.NEW, note = note)

    @Test
    fun `a session holds no more new cards than the allowance`() {
        val cards = (1L..10L).map { card(id = it, dueAt = now - day) } + (11L..40L).map { newCard(it) }

        val session = ReviewOrdering.buildSession(cards, emptyMap(), now, limit = 50, newAllowance = 3)

        assertEquals(3, session.count { it.state == FlashcardState.NEW })
        assertEquals("every due review is still there", 10, session.count { it.state != FlashcardState.NEW })
    }

    @Test
    fun `an exhausted allowance leaves a session of reviews only`() {
        val cards = (1L..5L).map { card(id = it, dueAt = now - day) } + (6L..20L).map { newCard(it) }

        val session = ReviewOrdering.buildSession(cards, emptyMap(), now, limit = 50, newAllowance = 0)

        assertEquals((1L..5L).toList(), session.map { it.id }.sorted())
    }

    @Test
    fun `the admitted new cards land in this sitting, not behind the whole backlog`() {
        // Seven hundred overdue reviews and twenty new cards allowed. Spread proportionally across
        // the pile that is one new card in thirty-five — none in a sitting of twenty.
        val reviews = (1L..700L).map { card(id = it, dueAt = now - it * hour) }
        val fresh = (1001L..1100L).map { newCard(it) }

        val session = ReviewOrdering.buildSession(reviews + fresh, emptyMap(), now, limit = 20, newAllowance = 20)

        assertEquals(20, session.size)
        assertEquals(10, session.count { it.state == FlashcardState.NEW })
    }

    @Test
    fun `shuffling many new cards never trips the sort, whatever the draw`() {
        // The shuffle's keys must be drawn once: redrawn per comparison, the sort contradicts itself
        // and throws on any list long enough to reach its merge step. Many seeds, since it was luck.
        val fresh = (1L..300L).map { newCard(it) }
        for (seed in 1..200) {
            val session = ReviewOrdering.buildSession(
                fresh, emptyMap(), now, limit = 300, random = kotlin.random.Random(seed),
            )
            assertEquals(300, session.size)
            assertEquals(300, session.map { it.id }.distinct().size)
        }
    }

    @Test
    fun `a word new in both directions spends one place of the allowance`() {
        val cards = listOf(
            newCard(1, note = 100), newCard(2, note = 100),
            newCard(3, note = 200), newCard(4, note = 200),
            newCard(5, note = 300),
        )

        val session = ReviewOrdering.buildSession(cards, emptyMap(), now, limit = 50, newAllowance = 2)

        assertEquals(2, session.size)
        assertEquals(2, session.map { it.note() }.distinct().size)
    }

    @Test
    fun `studying ahead never pulls a new card forward under a limit`() {
        // One review due, and a short session to fill. The filler has to be the review that is
        // coming up — not the seeded word that was dated next week on purpose.
        val due = card(id = 1, dueAt = now - day)
        val upcomingReview = card(id = 2, dueAt = now + 3 * day)
        val seededForLater = newCard(3, dueAt = now + 2 * day)

        val session =
            ReviewOrdering.buildSession(listOf(due, upcomingReview, seededForLater), emptyMap(), now, limit = 10, newAllowance = 5)

        assertEquals(listOf(1L, 2L), session.map { it.id })
    }

    @Test
    fun `with no limit the ordering is exactly what it was`() {
        val cards = (1L..30L).map { card(id = it, dueAt = now - it * hour) } +
            (31L..60L).map { newCard(it) } + (61L..70L).map { newCard(it, dueAt = now + day) }

        val before = ReviewOrdering.buildSession(cards, emptyMap(), now, limit = 100, random = Random(3))
        val unlimited = ReviewOrdering.buildSession(
            cards, emptyMap(), now, limit = 100, random = Random(3),
            newAllowance = NewCardLimit.remaining(NewCardLimit.UNLIMITED, introducedToday = 12),
        )

        assertEquals(before.map { it.id }, unlimited.map { it.id })
        assertEquals(70, unlimited.size)
    }

    private fun FlashcardEntity.note(): Long = noteId

    // --- helpers -------------------------------------------------------------

    private fun card(
        id: Long,
        dueAt: Long,
        state: FlashcardState = FlashcardState.REVIEW,
        note: Long = 0,
        lastReviewedAt: Long? = null,
    ) = FlashcardEntity(
        id = id,
        front = "w$id",
        back = "t$id",
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = now - 100 * day,
        dueAt = dueAt,
        state = state,
        noteId = note,
        lastReviewedAt = lastReviewedAt,
    )
}
