package com.copiosa.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VocabExplanationParserTest {

    @Test
    fun parse_cleanArray() {
        val raw = """
            [
              {"id": 1, "pos": "verb", "cefr": "B2", "explanation": "**It is lived.**", "example": "Se vive bien."},
              {"id": 2, "pos": "noun", "cefr": "A2", "explanation": "the afterlife", "example": "El más allá."}
            ]
        """.trimIndent()
        val out = VocabExplanationParser.parse(raw)
        assertEquals(2, out.size)
        assertEquals(1L, out[0].id)
        assertEquals("verb", out[0].pos)
        assertEquals("B2", out[0].cefr)
        assertTrue(out[0].explanation.contains("lived"))
        assertEquals(2L, out[1].id)
    }

    @Test
    fun parse_stripsCodeFencesAndProse() {
        val raw = "Sure, here you go:\n```json\n[{\"id\": 7, \"explanation\": \"x\"}]\n```\nHope that helps!"
        val out = VocabExplanationParser.parse(raw)
        assertEquals(1, out.size)
        assertEquals(7L, out[0].id)
    }

    @Test
    fun parse_singleObjectWrapsToList() {
        val raw = "{\"id\": 3, \"pos\": \"phrase\", \"explanation\": \"y\"}"
        val out = VocabExplanationParser.parse(raw)
        assertEquals(1, out.size)
        assertEquals(3L, out[0].id)
    }

    @Test
    fun parse_ignoresUnknownKeys() {
        val raw = "[{\"id\": 1, \"explanation\": \"z\", \"confidence\": 0.9, \"notes\": \"extra\"}]"
        val out = VocabExplanationParser.parse(raw)
        assertEquals(1, out.size)
        assertEquals("z", out[0].explanation)
    }

    @Test
    fun parse_garbageReturnsEmpty() {
        assertTrue(VocabExplanationParser.parse("no json here").isEmpty())
        assertTrue(VocabExplanationParser.parse("").isEmpty())
    }

    @Test
    fun encodeRequest_roundTripsItems() {
        val payload = VocabExplanationParser.encodeRequest(
            listOf(VocabRequestItem(id = 5, term = "se vive", context = "Aquí se vive bien.")),
        )
        assertTrue(payload.contains("\"id\":5"))
        assertTrue(payload.contains("se vive"))
        assertTrue(payload.contains("Aquí se vive bien."))
    }
}
