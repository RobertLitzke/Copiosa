package com.copiosa.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model replies in plain text — there is no JSON mode — so parsing has to survive fences, stray
 * prose and unknown keys, and must never throw. A generation pass that crashed on a chatty reply
 * would take a whole maintenance run with it.
 */
class ClozeGenerationParserTest {

    @Test
    fun `parses a clean generation array`() {
        val dtos = ClozeGenerationParser.parseGeneration(
            """[{"id":7,"sentence":"El gato duerme en la casa","form":"gato","translation":"The cat sleeps in the house"}]""",
        )
        assertEquals(1, dtos.size)
        assertEquals(7L, dtos.single().id)
        assertEquals("gato", dtos.single().form)
    }

    @Test
    fun `digs the array out of a code fence and surrounding prose`() {
        val raw = """
            Sure! Here are your sentences:
            ```json
            [{"id":1,"sentence":"Comimos paella el domingo","form":"comimos"}]
            ```
            Let me know if you want more.
        """.trimIndent()
        assertEquals("comimos", ClozeGenerationParser.parseGeneration(raw).single().form)
    }

    @Test
    fun `tolerates unknown keys and missing optional fields`() {
        val dtos = ClozeGenerationParser.parseGeneration(
            """[{"id":1,"sentence":"Vivimos aquí","confidence":0.9}]""",
        )
        assertEquals("Vivimos aquí", dtos.single().sentence)
        assertEquals(null, dtos.single().form)
    }

    /** Every failure mode has to be an empty list, never an exception. */
    @Test
    fun `returns nothing rather than throwing on unusable output`() {
        assertTrue(ClozeGenerationParser.parseGeneration("").isEmpty())
        assertTrue(ClozeGenerationParser.parseGeneration("I can't help with that.").isEmpty())
        assertTrue(ClozeGenerationParser.parseGeneration("[not json at all}").isEmpty())
        assertTrue(ClozeGenerationParser.parseVerdicts("{}").isEmpty())
    }

    @Test
    fun `parses verdicts, defaulting to sound`() {
        val verdicts = ClozeGenerationParser.parseVerdicts(
            """[{"id":1,"alsoFit":["pequeña"]},{"id":2},{"id":3,"underdetermined":true}]""",
        )
        assertEquals(3, verdicts.size)
        assertEquals(listOf("pequeña"), verdicts[0].alsoFit)
        // An item the model says nothing about must not read as "another option fits".
        assertTrue(verdicts[1].alsoFit.isEmpty())
        assertEquals(false, verdicts[1].underdetermined)
        assertEquals(true, verdicts[2].underdetermined)
    }

    @Test
    fun `round-trips its own request encoding`() {
        val encoded = ClozeGenerationParser.encodeGenerationRequest(
            listOf(ClozeRequestItem(id = 3, term = "gato", pos = "noun", meaning = "cat")),
        )
        assertTrue(encoded.contains("\"term\":\"gato\""))
        assertTrue(encoded.contains("\"id\":3"))

        val verdictPayload = ClozeGenerationParser.encodeVerdictRequest(
            listOf(ClozeVerdictRequestItem(1, "El ___ duerme", "gato", listOf("gato", "coche"))),
        )
        assertTrue(verdictPayload.contains("\"answer\":\"gato\""))

        val translationPayload = ClozeGenerationParser.encodeTranslationRequest(
            listOf(ClozeTranslationRequestItem(id = 5, sentence = "El gato duerme en la casa")),
        )
        assertTrue(translationPayload.contains("\"sentence\":\"El gato duerme en la casa\""))
    }

    @Test
    fun `parses translations`() {
        val dtos = ClozeGenerationParser.parseTranslations(
            """[{"id":5,"translation":"The cat sleeps in the house"}]""",
        )
        assertEquals(1, dtos.size)
        assertEquals(5L, dtos.single().id)
        assertEquals("The cat sleeps in the house", dtos.single().translation)
    }

    /**
     * A row the model skipped has to come back as an absent id, not an empty string: the back-fill
     * leaves unanswered rows in the queue, and a blank translation stored as "done" would retire a
     * sentence permanently unexplained.
     */
    @Test
    fun `leaves a dropped translation absent rather than blank`() {
        val dtos = ClozeGenerationParser.parseTranslations(
            """Sure! ```json
            [{"id":5,"translation":"The cat sleeps"},{"translation":"orphan"}]
            ``` """,
        )
        assertEquals(2, dtos.size)
        assertEquals(5L, dtos.first().id)
        assertEquals(null, dtos.last().id)
    }
}
