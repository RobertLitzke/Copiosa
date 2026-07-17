package com.copiosa.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutineContentParserTest {

    @Test
    fun parse_cleanResponse_keysByIdAndReadsFields() {
        val raw = """
            {"activities":[
              {"id":0,"items":[{"sentence":"Comí una manzana.","options":["yo","tú","ella"],"answerIndex":0,"explanation":"-é = yo"}]},
              {"id":1,"category":"ser_estar","items":[{"sentence":"Mi hermano ___ cansado.","options":["es","está"],"answerIndex":1}]},
              {"id":2,"title":"El jardín","paragraphs":["Hay flores.","Es tranquilo."]},
              {"id":3,"prompt":"¿Qué es verdad para ti?","statements":["Duermo temprano.","Leo de noche."]},
              {"id":4,"title":"Buenas noches","sentences":["La luna brilla.","Todo está en calma."]}
            ]}
        """.trimIndent()

        val byId = RoutineContentParser.parse(raw)

        assertEquals(setOf(0, 1, 2, 3, 4), byId.keys)
        assertEquals("Comí una manzana.", byId.getValue(0).items.single().sentence)
        assertEquals("ser_estar", byId.getValue(1).category)
        assertEquals(1, byId.getValue(1).items.single().answerIndex)
        assertEquals(listOf("Hay flores.", "Es tranquilo."), byId.getValue(2).paragraphs)
        assertEquals(2, byId.getValue(3).statements.size)
        assertEquals("Buenas noches", byId.getValue(4).title)
    }

    @Test
    fun parse_fencedAndTrailingComma_isSalvaged() {
        val raw = "```json\n{\"activities\":[{\"id\":4,\"sentences\":[\"La luna brilla.\",]}]}\n```"
        val byId = RoutineContentParser.parse(raw)
        assertEquals(listOf("La luna brilla."), byId.getValue(4).sentences)
    }

    @Test
    fun parse_garbage_isEmpty() {
        assertTrue(RoutineContentParser.parse("not json at all").isEmpty())
        assertTrue(RoutineContentParser.parse("").isEmpty())
    }

    @Test
    fun parse_dropsEntriesWithoutAnId() {
        val raw = """{"activities":[{"items":[]},{"id":2,"paragraphs":["ok"]}]}"""
        val byId = RoutineContentParser.parse(raw)
        assertEquals(setOf(2), byId.keys)
    }
}
