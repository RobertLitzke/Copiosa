package com.copiosa.ui.vocab

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.FlashcardState
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.Collator
import java.util.Locale

/** The vocabulary list's search, filters and sort orders, driven through [listVocab]. */
class VocabListingTest {

    private val now = 1_000_000_000L
    private val day = 86_400_000L
    private val collator: Collator = Collator.getInstance(Locale.forLanguageTag("es"))

    private fun card(
        id: Long,
        front: String,
        createdAt: Long = id,
        dueAt: Long = now,
        pos: String? = null,
        explanation: String? = null,
        marked: Boolean = false,
        suspended: Boolean = false,
        lapses: Int = 0,
        state: FlashcardState = FlashcardState.REVIEW,
        intervalDays: Double = 5.0,
    ) = FlashcardEntity(
        id = id, noteId = id, front = front, back = "gloss of $front", partOfSpeech = pos,
        explanation = explanation, marked = marked, suspended = suspended, lapses = lapses, state = state,
        intervalDays = intervalDays, targetLanguage = "es", nativeLanguage = "en", createdAt = createdAt, dueAt = dueAt,
    )

    private fun list(
        cards: List<FlashcardEntity>,
        filter: VocabFilter = VocabFilter(),
        sort: VocabSort = VocabSort.NEWEST,
        ranks: Map<Long, Int> = emptyMap(),
    ) = listVocab(cards, filter, sort, now, ranks, collator).map { it.id }

    private val deck = listOf(
        card(1, "gato", pos = "noun"),
        card(2, "correr", pos = "verb", explanation = "To run."),
        card(3, "árbol", pos = "noun", marked = true),
        card(4, "zapato", pos = "noun", suspended = true),
    )

    @Test
    fun `the default is newest first and lets everything through`() {
        assertEquals(listOf(4L, 3L, 2L, 1L), list(deck))
    }

    @Test
    fun `oldest first is the reverse`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), list(deck, sort = VocabSort.OLDEST))
    }

    @Test
    fun `alphabetical order puts an accented letter with its base letter`() {
        // Plain code-point order would put árbol after zapato.
        assertEquals(listOf(3L, 2L, 1L, 4L), list(deck, sort = VocabSort.ALPHABETICAL))
    }

    @Test
    fun `due soonest orders by due date and puts frozen words last`() {
        val cards = listOf(
            card(1, "a", dueAt = now + 3 * day),
            card(2, "b", dueAt = now - day),
            card(3, "c", dueAt = now - 5 * day, suspended = true),
            card(4, "d", dueAt = now + day),
        )
        assertEquals(listOf(2L, 4L, 1L, 3L), list(cards, sort = VocabSort.DUE_SOONEST))
    }

    @Test
    fun `most forgotten leads with the most lapses and breaks ties newest first`() {
        val cards = listOf(card(1, "a", lapses = 2), card(2, "b", lapses = 5), card(3, "c", lapses = 2), card(4, "d"))
        assertEquals(listOf(2L, 3L, 1L, 4L), list(cards, sort = VocabSort.MOST_LAPSED))
    }

    @Test
    fun `most common orders by rank and puts unranked words last`() {
        val ranks = mapOf(1L to 900, 2L to 40, 4L to 41)
        assertEquals(listOf(2L, 4L, 1L, 3L), list(deck, sort = VocabSort.MOST_COMMON, ranks = ranks))
    }

    @Test
    fun `search matches either side, ignoring case`() {
        assertEquals(listOf(2L), list(deck, VocabFilter(query = " CORR ")))
        assertEquals(listOf(4L, 3L, 2L, 1L), list(deck, VocabFilter(query = "gloss of")))
    }

    @Test
    fun `the due window leaves out frozen words and ones due later`() {
        val cards = listOf(
            card(1, "a", dueAt = now - day),
            card(2, "b", dueAt = now + 3 * day),
            card(3, "c", dueAt = now - day, suspended = true),
        )
        assertEquals(listOf(1L), list(cards, VocabFilter(dueWindow = DueWindow.DAY)))
        assertEquals(listOf(2L, 1L), list(cards, VocabFilter(dueWindow = DueWindow.WEEK)))
    }

    @Test
    fun `status filters by the lifecycle the dot describes`() {
        val cards = listOf(
            card(1, "fresh", state = FlashcardState.NEW),
            card(2, "young", intervalDays = 5.0),
            card(3, "mature", intervalDays = 40.0),
            card(4, "frozen", suspended = true),
        )
        assertEquals(listOf(1L), list(cards, VocabFilter(status = CardStatus.NEW)))
        assertEquals(listOf(2L), list(cards, VocabFilter(status = CardStatus.YOUNG)))
        assertEquals(listOf(3L), list(cards, VocabFilter(status = CardStatus.MATURE)))
        assertEquals(listOf(4L), list(cards, VocabFilter(status = CardStatus.FROZEN)))
    }

    @Test
    fun `part of speech and explanation filters combine with marked`() {
        assertEquals(listOf(4L, 3L, 1L), list(deck, VocabFilter(partOfSpeech = "noun")))
        assertEquals(listOf(2L), list(deck, VocabFilter(explained = ExplainedFilter.EXPLAINED)))
        assertEquals(listOf(4L, 3L, 1L), list(deck, VocabFilter(explained = ExplainedFilter.UNEXPLAINED)))
        assertEquals(listOf(3L), list(deck, VocabFilter(partOfSpeech = "noun", markedOnly = true)))
    }

    @Test
    fun `the leech filter keeps a word whose other direction is the one slipping`() {
        // Every card of the deck: word 1 is a leech only in the direction that is not its lead card.
        val everyCard = listOf(
            card(1, "a"),
            card(10, "a", lapses = 9, intervalDays = 2.0).copy(noteId = 1),
            card(2, "b", lapses = 9, intervalDays = 40.0),
            card(3, "c", lapses = 9, intervalDays = 2.0, suspended = true),
            card(4, "d", lapses = 8, intervalDays = 2.0),
        )
        val leeches = leechNotesIn(everyCard)
        assertEquals(setOf(1L, 4L), leeches)

        val rows = listOf(card(1, "a"), card(2, "b"), card(3, "c"), card(4, "d"))
        val shown = listVocab(rows, VocabFilter(leechesOnly = true), VocabSort.NEWEST, now, emptyMap(), collator, leeches)
        assertEquals(listOf(4L, 1L), shown.map { it.id })
        assertEquals(true, VocabFilter(leechesOnly = true).sheetFiltersActive)
    }

    @Test
    fun `a blank explanation counts as unexplained`() {
        val cards = listOf(card(1, "a", explanation = "  "), card(2, "b", explanation = "Yes."))
        assertEquals(listOf(1L), list(cards, VocabFilter(explained = ExplainedFilter.UNEXPLAINED)))
    }

    @Test
    fun `parts of speech are offered commonest first, normalized, without blanks`() {
        val cards = listOf(
            card(1, "a", pos = "Verb"), card(2, "b", pos = "noun"), card(3, "c", pos = "noun"),
            card(4, "d", pos = " "), card(5, "e", pos = null), card(6, "f", pos = "adjective"),
        )
        assertEquals(listOf("noun", "adjective", "verb"), partsOfSpeechIn(cards))
    }

    @Test
    fun `active reports whether anything narrows the list`() {
        assertEquals(false, VocabFilter().active)
        assertEquals(true, VocabFilter(query = "x").active)
        assertEquals(true, VocabFilter(status = CardStatus.NEW).active)
        assertEquals(false, VocabFilter(query = "x").sheetFiltersActive)
    }
}
