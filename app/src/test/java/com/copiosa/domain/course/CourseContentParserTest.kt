package com.copiosa.domain.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseContentParserTest {

    @Test
    fun `parses a clean response into slots keyed by requested id`() {
        val raw = """
            {"slots":[
              {"id":2,"items":[{"sentence":"Ayer ___ tarde.","options":["llegué","llego"],"answerIndex":0,
               "explanation":"Ayer marks a finished event."}]},
              {"id":4,"items":[{"sentence":"___ mucho de niño.","options":["Leí","Leía"],"answerIndex":1}]}
            ]}
        """.trimIndent()
        val parsed = CourseContentParser.parse(raw)
        assertEquals(setOf(2, 4), parsed.keys)
        assertEquals("llegué", parsed.getValue(2).items.first().options.first())
        assertEquals(1, parsed.getValue(4).items.first().answerIndex)
    }

    @Test
    fun `survives code fences, prose, smart quotes and trailing commas`() {
        val raw = """
            Sure! Here are your items:
            ```json
            {“slots”:[{“id”:0,“items”:[{“sentence”:“Ayer ___ tarde.”,“options”:[“llegué”,“llego”,],
             “answerIndex”:0},]},]}
            ```
            Hope that helps.
        """.trimIndent()
        val parsed = CourseContentParser.parse(raw)
        assertEquals(setOf(0), parsed.keys)
        assertEquals(2, parsed.getValue(0).items.first().options.size)
    }

    @Test
    fun `never throws on garbage, returning nothing to work with`() {
        for (raw in listOf("", "no json here", "{", "{\"slots\":", "[]", "{\"unexpected\":true}")) {
            assertTrue("should be empty for '$raw'", CourseContentParser.parse(raw).isEmpty())
            assertNull(CourseContentParser.parseFeedback(raw))
        }
    }

    @Test
    fun `slots without an echoed id are discarded`() {
        val raw = """{"slots":[{"items":[{"sentence":"x","options":["a","b"],"answerIndex":0}]}]}"""
        assertTrue(CourseContentParser.parse(raw).isEmpty())
    }

    @Test
    fun `unknown fields are tolerated`() {
        val raw = """
            {"model":"whatever","slots":[{"id":1,"difficulty":"hard",
             "items":[{"sentence":"a ___ b","options":["x","y"],"answerIndex":1,"tags":["extra"]}]}]}
        """.trimIndent()
        assertEquals(setOf(1), CourseContentParser.parse(raw).keys)
    }

    @Test
    fun `feedback is read from the same envelope`() {
        val raw = """{"feedback":"You are reaching for the imperfect where the event is finished."}"""
        assertEquals(
            "You are reaching for the imperfect where the event is finished.",
            CourseContentParser.parseFeedback(raw),
        )
        assertNull(CourseContentParser.parseFeedback("""{"feedback":"   "}"""))
    }
}
