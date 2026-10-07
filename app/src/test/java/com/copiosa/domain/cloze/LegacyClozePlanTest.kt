package com.copiosa.domain.cloze

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.FlashcardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The conversion is the only operation in this feature that deletes the learner's cards, so its
 * decisions are worth pinning individually — especially the ones that decide what is *not* converted.
 */
class LegacyClozePlanTest {

    private val formToCard = mapOf("gato" to 10L, "gatos" to 10L, "perro" to 11L)

    @Test
    fun `converts a single-deletion card and links it to the vocab card`() {
        val plan = LegacyClozePlan.plan(listOf(card(1, "El {{c1::gato}} duerme")), formToCard)

        assertEquals(1, plan.rows.size)
        assertEquals(1, plan.cards.size)
        val row = plan.rows.single()
        assertEquals("gato", row.answer)
        assertEquals(10L, row.cardId)
        assertTrue("the first row inherits the schedule", row.carriesHistory)
    }

    /**
     * A hand-made two-gap sentence becomes two independently scheduled clozes, each linked to its
     * own word — which is strictly better than today's behaviour of blanking both at once.
     */
    @Test
    fun `splits a hand-made multi-deletion card into one row per blank`() {
        val plan = LegacyClozePlan.plan(
            listOf(card(1, "El {{c1::gato}} ve al {{c2::perro}}")),
            formToCard,
        )

        assertEquals(2, plan.rows.size)
        assertEquals(listOf("gato", "perro"), plan.rows.map { it.answer })
        assertEquals(listOf(10L, 11L), plan.rows.map { it.cardId })
        // Each row blanks only its own word; the other is filled in.
        assertTrue(plan.rows[0].text.contains("perro") && !plan.rows[0].text.contains("{{c1::perro"))
        assertTrue(plan.rows[1].text.contains("{{c1::perro}}"))
    }

    /** Only one row may inherit the card's history — copying it onto both would invent reviews. */
    @Test
    fun `only the first row of a split carries the history`() {
        val plan = LegacyClozePlan.plan(
            listOf(card(1, "El {{c1::gato}} ve al {{c2::perro}}")),
            formToCard,
        )
        assertEquals(listOf(true, false), plan.rows.map { it.carriesHistory })
    }

    /**
     * An imported Anki card is about exactly one deletion — its template ordinal plus one. Expanding
     * it would invent clozes Anki never had, and the same note's other card will be converted too.
     */
    @Test
    fun `an imported anki card converts only its own ordinal`() {
        val imported = card(1, "El {{c1::gato}} ve al {{c2::perro}}").copy(ankiNoteGuid = "g", ankiOrd = 1)
        val plan = LegacyClozePlan.plan(listOf(imported), formToCard)

        assertEquals(1, plan.rows.size)
        assertEquals("perro", plan.rows.single().answer)
    }

    @Test
    fun `refuses a card with more blanks than it is worth splitting`() {
        val many = (1..6).joinToString(" ") { "{{c$it::w$it}}" }
        val plan = LegacyClozePlan.plan(listOf(card(1, "Frase $many")), formToCard)

        assertTrue(plan.isEmpty)
        assertEquals(1, plan.skippedTooManyDeletions)
        assertTrue("the card must not be deleted", plan.cards.isEmpty())
    }

    /** No vocab card for the blanked word: still worth drilling, it just has nothing to nudge. */
    @Test
    fun `keeps a sentence whose word is not in the deck, unlinked`() {
        val plan = LegacyClozePlan.plan(listOf(card(1, "El {{c1::pájaro}} canta")), formToCard)

        assertEquals(1, plan.rows.size)
        assertNull(plan.rows.single().cardId)
        assertEquals(0, plan.linked)
    }

    // --- the non-markup side -------------------------------------------------

    @Test
    fun `uses the other side as a translation when it plausibly is one`() {
        val c = card(1, "El {{c1::gato}} duerme").copy(back = "The cat is sleeping")
        val plan = LegacyClozePlan.plan(listOf(c), formToCard)
        assertEquals("The cat is sleeping", plan.rows.single().translation)
    }

    /**
     * On an Anki cloze note the other side is as often a hint as a translation, and a hint containing
     * the answer simply gives the item away. Showing nothing beats showing something wrong.
     */
    @Test
    fun `refuses an extra side that contains the answer`() {
        val c = card(1, "El {{c1::gato}} duerme").copy(back = "gato = cat")
        assertNull(LegacyClozePlan.plan(listOf(c), formToCard).rows.single().translation)
    }

    @Test
    fun `refuses an extra side that is itself cloze markup`() {
        val c = card(1, "El {{c1::gato}} duerme").copy(back = "Otro {{c1::perro}}")
        assertNull(LegacyClozePlan.plan(listOf(c), formToCard).rows.single().translation)
    }

    // --- things that are not cloze cards --------------------------------------

    @Test
    fun `counts a card whose markup will not parse instead of dropping it silently`() {
        val plan = LegacyClozePlan.plan(listOf(card(1, "No markup here")), formToCard)

        assertTrue(plan.isEmpty)
        assertEquals(1, plan.skippedUnparseable)
        assertTrue(plan.cards.isEmpty())
    }

    @Test
    fun `reports the media cards the query held back`() {
        val plan = LegacyClozePlan.plan(emptyList(), formToCard, skippedMedia = 4)
        assertEquals(4, plan.skippedMedia)
        assertTrue(plan.isEmpty)
    }

    private fun card(id: Long, front: String) = FlashcardEntity(
        id = id,
        front = front,
        back = "",
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = 0,
        dueAt = 0,
        cardKind = CardKind.CLOZE,
        state = FlashcardState.REVIEW,
        intervalDays = 12.0,
        repetitions = 3,
    )
}
