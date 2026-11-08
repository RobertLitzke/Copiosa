package com.copiosa.domain.text

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    @Test
    fun plainTextPassesThrough() {
        assertEquals("comer", SpeechText.fromMarkdown("comer"))
        assertEquals("¿Qué tal? Todo bien.", SpeechText.fromMarkdown("  ¿Qué tal? Todo bien.  "))
    }

    @Test
    fun stripsInlineMarkers() {
        assertEquals(
            "El gato es muy listo y rápido.",
            SpeechText.fromMarkdown("El **gato** es *muy* listo y `rápido`."),
        )
        assertEquals("Mira la página aquí.", SpeechText.fromMarkdown("Mira [la página](https://example.com) aquí."))
        assertEquals("Una nota aquí.", SpeechText.fromMarkdown("Una nota[^1] aquí."))
    }

    /** Block markers go, the lines stay: the splitter pauses at each line break. */
    @Test
    fun stripsBlockMarkersButKeepsTheLines() {
        val markdown = """
            ## La receta

            - Dos huevos
            * Una **cebolla**
            1. Batir todo
            > Como decía mi abuela.
        """.trimIndent()
        assertEquals(
            "La receta\n\nDos huevos\nUna cebolla\nBatir todo\nComo decía mi abuela.",
            SpeechText.fromMarkdown(markdown),
        )
        assertEquals(
            listOf("La receta", "Dos huevos", "Una cebolla", "Batir todo", "Como decía mi abuela."),
            Sentences.split(SpeechText.fromMarkdown(markdown)),
        )
    }

    @Test
    fun dropsRulesAndTableScaffolding() {
        val markdown = "Antes\n\n---\n\n| yo | como |\n|----|:----:|\n| tú | comes |\n\nDespués"
        assertEquals("Antes\n\nyo   como\ntú   comes\n\nDespués", SpeechText.fromMarkdown(markdown))
    }

    /** An italic that opens a line is emphasis, not a bullet — the word must survive. */
    @Test
    fun aLeadingEmphasisIsNotABullet() {
        assertEquals("Ojalá llueva.", SpeechText.fromMarkdown("*Ojalá* llueva."))
    }

    @Test
    fun nothingSpeakableIsBlank() {
        assertEquals("", SpeechText.fromMarkdown("***\n\n---"))
        assertEquals("", SpeechText.fromMarkdown("   "))
    }
}
