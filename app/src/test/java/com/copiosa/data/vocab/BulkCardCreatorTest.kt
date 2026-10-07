package com.copiosa.data.vocab

import com.copiosa.domain.model.CardKind
import com.copiosa.domain.prompt.BulkCardDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the creator's own decisions about a model reply — what it keeps, what it refuses to store.
 * Splitting, parsing and alignment are [com.copiosa.domain.prompt.BulkCards]' and are tested there.
 */
class BulkCardCreatorTest {

    @Test
    fun `a full reply becomes a draft with everything on it`() {
        val draft = BulkCardCreator.draftFrom(
            "tablecloth",
            BulkCardDto(
                id = 0,
                term = " mantel ",
                translation = "tablecloth",
                pos = "Noun",
                gender = "m",
                example = " Pon el mantel en la mesa. ",
            ),
        )!!
        assertEquals("tablecloth", draft.source)
        assertEquals("mantel", draft.term)
        assertEquals("tablecloth", draft.translation)
        assertEquals("noun", draft.pos)
        assertEquals("M", draft.gender)
        assertEquals("Pon el mantel en la mesa.", draft.example)
    }

    @Test
    fun `a reply with no term is no draft`() {
        assertNull(BulkCardCreator.draftFrom("xyzzy", BulkCardDto(id = 0, term = "  ")))
    }

    @Test
    fun `a missing gloss falls back to what the learner typed`() {
        assertEquals("on purpose", BulkCardCreator.draftFrom("on purpose", BulkCardDto(term = "adrede"))!!.translation)
        assertEquals(
            "on purpose",
            BulkCardCreator.draftFrom("on purpose", BulkCardDto(term = "adrede", translation = " "))!!.translation,
        )
    }

    @Test
    fun `values the card editor does not know are dropped rather than stored`() {
        val draft = BulkCardCreator.draftFrom(
            "quick",
            BulkCardDto(term = "rápido", pos = "adj.", gender = "null", example = ""),
        )!!
        assertNull(draft.pos)
        assertNull(draft.gender)
        assertNull(draft.example)
    }

    @Test
    fun `only a noun keeps a gender`() {
        assertEquals("F", BulkCardCreator.draftFrom("table", BulkCardDto(term = "mesa", pos = "noun", gender = "F"))!!.gender)
        assertNull(BulkCardCreator.draftFrom("to eat", BulkCardDto(term = "comer", pos = "verb", gender = "M"))!!.gender)
    }

    @Test
    fun `a multi-word term is a phrase card`() {
        assertEquals(CardKind.VOCAB, BulkCardCreator.kindFor("mantel"))
        assertEquals(CardKind.PHRASE, BulkCardCreator.kindFor("pedir prestado"))
        assertEquals(CardKind.VOCAB, BulkCardCreator.kindFor(" mantel "))
    }

    @Test
    fun `the provenance stamp is stable`() {
        // Stored on every card this makes and round-tripped through .apkg as an `la:auto` tag.
        assertEquals("bulk", BulkCardCreator.SOURCE)
    }
}
