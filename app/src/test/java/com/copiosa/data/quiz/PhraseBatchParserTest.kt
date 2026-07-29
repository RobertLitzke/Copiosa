package com.copiosa.data.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhraseBatchParserTest {

    @Test
    fun `parses a well-formed batch`() {
        val raw = """
            {"phrases":[
              {"phrase":"¿Listos para pedir?","correct":"Ready to order?",
               "distractors":["Ready to leave?","Are you paying?","How many are you?","Anything to drink?"]},
              {"phrase":"¿Algo más?","correct":"Anything else?",
               "distractors":["Everything okay?","Cash or card?","Table for two?","Still the same?"]}
            ]}
        """.trimIndent()
        val entries = PhraseBatchParser.parse(raw)
        assertEquals(2, entries.size)
        assertEquals("¿Listos para pedir?", entries[0].phrase)
        assertEquals("Ready to order?", entries[0].correct)
        assertEquals(4, entries[0].distractors.size)
    }

    @Test
    fun `tolerates prose wrapping, smart quotes and a trailing comma`() {
        val raw = """
            Sure! Here you go:
            {“phrases”:[
              {“phrase”:“¿Listos para pedir?”,“correct”:“Ready to order?”,
               “distractors”:[“Ready to leave?”,“Are you paying?”,“How many?”,“A drink?”,]},
            ]}
            Hope that helps.
        """.trimIndent()
        val entries = PhraseBatchParser.parse(raw)
        assertEquals(1, entries.size)
        assertEquals("Ready to order?", entries[0].correct)
    }

    @Test
    fun `drops entries missing a phrase or correct answer`() {
        val raw = """
            {"phrases":[
              {"phrase":"","correct":"No phrase","distractors":["a","b","c","d"]},
              {"phrase":"Sólo frase","correct":"","distractors":["a","b","c","d"]},
              {"phrase":"Buena","correct":"Good one","distractors":["a","b","c","d"]}
            ]}
        """.trimIndent()
        val entries = PhraseBatchParser.parse(raw)
        assertEquals(1, entries.size)
        assertEquals("Good one", entries[0].correct)
    }

    @Test
    fun `garbage returns empty, never throws`() {
        assertTrue(PhraseBatchParser.parse("").isEmpty())
        assertTrue(PhraseBatchParser.parse("no json here").isEmpty())
        assertTrue(PhraseBatchParser.parse("{\"phrases\":[]}").isEmpty())
    }
}
