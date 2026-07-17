package com.copiosa.data.anki

import com.copiosa.domain.model.CardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the cleaned-deck import path: reading `la:*` tags into structured columns, splitting a
 * "primary; alt" front into front + altForms, and deriving [com.copiosa.data.local.FlashcardEntity.frontIsTarget]
 * per card from `la:tgt` + the forward/reverse template. Uses an in-memory [RawApkg], no binary fixture.
 */
class AnkiImporterCleanedTest {

    // One note type with Forward (ord 0) + Reverse (ord 1) templates and Front/Back fields.
    private val modelsJson = """
        {"1000":{"name":"Basic (and reversed card)","sortf":0,
          "flds":[{"name":"Front"},{"name":"Back"}],
          "tmpls":[{"name":"Forward"},{"name":"Reverse"}]}}
    """.trimIndent()
    private val decksJson = """{"1":{"name":"Spanish"}}"""

    private class Fake(
        override val modelsJson: String,
        override val decksJson: String,
        private val notes: List<RawNote>,
        private val cards: List<RawCard>,
    ) : RawApkg {
        override val crt = 1_600_000_000L
        override fun notes() = notes
        override fun cards() = cards
        override fun revlog() = emptyList<RawRevlog>()
        override fun mediaMap() = emptyMap<String, String>()
    }

    private fun card(nid: Long, ord: Int) = RawCard(
        id = nid * 10 + ord, nid = nid, did = 1, ord = ord,
        type = 0, queue = 0, due = 0, ivl = 0, factor = 0, reps = 0, lapses = 0,
    )

    private fun import(notes: List<RawNote>): ImportResult {
        val cards = notes.flatMap { listOf(card(it.id, 0), card(it.id, 1)) }
        return AnkiImporter("es", "en").import(Fake(modelsJson, decksJson, notes, cards), now = 1_700_000_000_000L)
    }

    private fun ImportResult.card(guid: String, ord: Int) =
        cards.first { it.card.ankiNoteGuid == guid && it.card.ankiOrd == ord }.card

    @Test
    fun spanishFrontVocabExtractsStructuredFields() {
        val note = RawNote(
            id = 1, guid = "g-cerdo", mid = 1000,
            tags = " casa la:kind:vocab la:tgt:0 la:gender:m la:pos:noun la:region:mx ",
            fields = listOf("cerdo; chancho", "pig"),
        )
        val res = import(listOf(note))

        val forward = res.card("g-cerdo", 0)
        assertEquals("cerdo", forward.front)
        assertEquals("pig", forward.back)
        assertEquals("chancho", forward.altForms)
        assertEquals("M", forward.gender)
        assertEquals("mx", forward.region)
        assertEquals("noun", forward.partOfSpeech)
        assertEquals(CardKind.VOCAB, forward.cardKind)
        assertTrue(forward.frontIsTarget)
        assertEquals("casa", forward.ankiTags) // la:* tags stripped from passthrough

        val reverse = res.card("g-cerdo", 1)
        assertEquals("pig", reverse.front) // reverse card prompts in native
        assertEquals("cerdo", reverse.back)
        assertEquals("chancho", reverse.altForms)
        assertFalse(reverse.frontIsTarget)
    }

    @Test
    fun englishFrontIdiomPutsTargetOnTheReverseCard() {
        val note = RawNote(
            id = 2, guid = "g-bycar", mid = 1000,
            tags = " la:kind:phrase la:tgt:1 ",
            fields = listOf("by car", "en coche"),
        )
        val res = import(listOf(note))

        val forward = res.card("g-bycar", 0)
        assertEquals("by car", forward.front)
        assertFalse(forward.frontIsTarget) // native is on the front here
        assertEquals(CardKind.PHRASE, forward.cardKind)
        assertNull(forward.altForms)

        val reverse = res.card("g-bycar", 1)
        assertEquals("en coche", reverse.front) // target lands on the reverse card's front
        assertTrue(reverse.frontIsTarget)
    }

    @Test
    fun uncleanedNoteDefaultsToVocabForwardTarget() {
        val note = RawNote(id = 3, guid = "g-plain", mid = 1000, tags = "", fields = listOf("hola", "hello"))
        val res = import(listOf(note))

        val forward = res.card("g-plain", 0)
        assertEquals("hola", forward.front)
        assertEquals(CardKind.VOCAB, forward.cardKind)
        assertTrue(forward.frontIsTarget)
        assertNull(forward.gender)
        assertNull(forward.ankiTags)
    }
}
