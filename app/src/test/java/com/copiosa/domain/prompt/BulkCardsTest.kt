package com.copiosa.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bulk-add box takes free typing in and a plain-text model reply back, so both ends have to be
 * forgiving: the split must make sense of however the list was typed, and the parse must survive
 * fences, prose and a reply that came back short — without ever pairing a draft with the wrong word.
 */
class BulkCardsTest {

    private fun items(vararg texts: String) = texts.mapIndexed { i, t -> BulkCardRequestItem(i, t) }

    @Test
    fun `splits on commas and line breaks, trimming each item`() {
        assertEquals(
            listOf("tablecloth", "to borrow", "on purpose", "ladle"),
            BulkCards.splitInput("tablecloth,  to borrow\n on purpose \r\nladle"),
        )
    }

    @Test
    fun `drops blanks and repeats, keeping the order typed`() {
        assertEquals(
            listOf("dog", "cat"),
            BulkCards.splitInput("dog,, cat, Dog,\n\n ,cat,"),
        )
    }

    @Test
    fun `collapses stray whitespace inside a phrase`() {
        assertEquals(listOf("to run out of"), BulkCards.splitInput("to   run\tout of"))
    }

    @Test
    fun `nothing typed is no items`() {
        assertTrue(BulkCards.splitInput("").isEmpty())
        assertTrue(BulkCards.splitInput(" , \n ,").isEmpty())
    }

    @Test
    fun `the request carries each id and text`() {
        val payload = BulkCards.encodeRequest(items("tablecloth", "to borrow"))
        assertTrue(payload.contains("\"id\":0"))
        assertTrue(payload.contains("tablecloth"))
        assertTrue(payload.contains("\"id\":1"))
        assertTrue(payload.contains("to borrow"))
    }

    @Test
    fun `parses a clean reply`() {
        val dtos = BulkCards.parse(
            """[{"id":0,"term":"mantel","translation":"tablecloth","pos":"noun","gender":"M","example":"Pon el mantel."}]""",
        )
        val dto = dtos.single()
        assertEquals(0, dto.id)
        assertEquals("mantel", dto.term)
        assertEquals("tablecloth", dto.translation)
        assertEquals("noun", dto.pos)
        assertEquals("M", dto.gender)
        assertEquals("Pon el mantel.", dto.example)
    }

    @Test
    fun `digs the array out of a code fence and surrounding prose`() {
        val raw = """
            Here are your cards:
            ```json
            [{"id":0,"term":"prestar","translation":"to lend","pos":"verb","gender":null}]
            ```
            Buena suerte!
        """.trimIndent()
        val dto = BulkCards.parse(raw).single()
        assertEquals("prestar", dto.term)
        assertNull(dto.gender)
    }

    @Test
    fun `tolerates unknown keys and missing optional fields`() {
        val dto = BulkCards.parse("""[{"id":3,"term":"adrede","confidence":0.9}]""").single()
        assertEquals("adrede", dto.term)
        assertNull(dto.translation)
        assertNull(dto.example)
    }

    @Test
    fun `garbage parses to nothing rather than throwing`() {
        assertTrue(BulkCards.parse("").isEmpty())
        assertTrue(BulkCards.parse("I can't help with that.").isEmpty())
        assertTrue(BulkCards.parse("[{\"id\": 0, \"term\": ").isEmpty())
    }

    @Test
    fun `aligns by echoed id even when the reply is reordered or short`() {
        val aligned = BulkCards.align(
            items("tablecloth", "to borrow", "on purpose"),
            listOf(BulkCardDto(id = 2, term = "adrede"), BulkCardDto(id = 0, term = "mantel")),
        )
        assertEquals("mantel", aligned[0]?.term)
        assertNull(aligned[1])
        assertEquals("adrede", aligned[2]?.term)
    }

    @Test
    fun `falls back to position when no ids were echoed and the counts agree`() {
        val aligned = BulkCards.align(
            items("tablecloth", "to borrow"),
            listOf(BulkCardDto(term = "mantel"), BulkCardDto(term = "pedir prestado")),
        )
        assertEquals(listOf("mantel", "pedir prestado"), aligned.map { it?.term })
    }

    @Test
    fun `refuses to guess when no ids were echoed and the counts disagree`() {
        // Zipping a short reply by position would hand "to borrow" the card meant for "on purpose".
        val aligned = BulkCards.align(
            items("tablecloth", "to borrow", "on purpose"),
            listOf(BulkCardDto(term = "mantel"), BulkCardDto(term = "adrede")),
        )
        assertEquals(listOf(null, null, null), aligned)
    }

    @Test
    fun `a reply with no term is not a card`() {
        val byId = BulkCards.align(
            items("tablecloth", "xyzzy"),
            listOf(BulkCardDto(id = 0, term = "mantel"), BulkCardDto(id = 1, term = "  ")),
        )
        assertEquals("mantel", byId[0]?.term)
        assertNull(byId[1])

        val byPosition = BulkCards.align(
            items("tablecloth", "xyzzy"),
            listOf(BulkCardDto(term = "mantel"), BulkCardDto(term = "")),
        )
        assertEquals("mantel", byPosition[0]?.term)
        assertNull(byPosition[1])
    }

    @Test
    fun `the prompt names both languages, the country and the headword rules`() {
        val prompt = BulkCards.systemPrompt(
            country = "Mexico",
            targetLanguage = "Spanish",
            nativeLanguage = "English",
            level = "intermediate",
        )
        assertTrue(prompt.contains("Mexico Spanish teacher"))
        assertTrue(prompt.contains("English-speaking"))
        assertTrue(prompt.contains("WITHOUT an article"))
        assertTrue(prompt.contains("infinitive"))
        assertTrue(prompt.contains("already in Spanish"))
    }
}
