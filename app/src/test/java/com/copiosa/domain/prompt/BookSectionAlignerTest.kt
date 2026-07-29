package com.copiosa.domain.prompt

import com.copiosa.domain.prompt.BookSectionAligner.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookSectionAlignerTest {

    private val ords = listOf(214, 215, 216)

    @Test
    fun `a perfect echo aligns`() {
        val result = BookSectionAligner.parse(
            """
                [[P214]]
                "Después de la ayuda que le dimos a papá..."

                [[P215]]
                ## II: El Roadster Robado

                [[P216]]
                El roadster había desaparecido.
            """.trimIndent(),
            ords,
        )

        assertTrue(result is Result.Aligned)
        assertEquals(3, result.byOrd.size)
        assertTrue(result.byOrd[214]!!.startsWith("\"Después"))
        assertEquals("## II: El Roadster Robado", result.byOrd[215])
    }

    @Test
    fun `markers out of order but complete still align by number`() {
        val result = BookSectionAligner.parse(
            "[[P215]]\nSegundo\n\n[[P214]]\nPrimero\n\n[[P216]]\nTercero",
            ords,
        )

        assertTrue(result is Result.Aligned)
        assertEquals("Primero", result.byOrd[214])
        assertEquals("Segundo", result.byOrd[215])
    }

    @Test
    fun `renumbered markers with a matching count zip by order`() {
        val result = BookSectionAligner.parse(
            "[[P1]]\nUno\n\n[[P2]]\nDos\n\n[[P3]]\nTres",
            ords,
        )

        assertTrue(result is Result.Aligned)
        assertEquals("Uno", result.byOrd[214])
        assertEquals("Dos", result.byOrd[215])
        assertEquals("Tres", result.byOrd[216])
    }

    @Test
    fun `merged paragraphs fall back to a best effort covering every block`() {
        // Two blocks for three expected paragraphs: proportional mapping, no block lost.
        val result = BookSectionAligner.parse(
            "[[P214]]\nUno\n\nDos y tres juntos",
            ords,
        )

        assertTrue(result is Result.Misaligned)
        val texts = result.byOrd.values.joinToString(" ")
        assertTrue(texts.contains("Uno"))
        assertTrue(texts.contains("Dos y tres juntos"))
        // Order preserved: the first block maps to the first ord.
        assertEquals("Uno", result.byOrd[214])
    }

    @Test
    fun `markerless prose maps proportionally`() {
        val result = BookSectionAligner.parse(
            "Uno\n\nDos\n\nTres",
            ords,
        )

        assertTrue(result is Result.Misaligned)
        assertEquals("Uno", result.byOrd[214])
        assertEquals("Dos", result.byOrd[215])
        assertEquals("Tres", result.byOrd[216])
    }

    @Test
    fun `more blocks than paragraphs concatenates collisions in order`() {
        val result = BookSectionAligner.parse(
            "A\n\nB\n\nC\n\nD",
            listOf(1, 2),
        )

        assertTrue(result is Result.Misaligned)
        assertEquals("A\n\nB", result.byOrd[1])
        assertEquals("C\n\nD", result.byOrd[2])
    }

    @Test
    fun `a blank body inside a perfect echo demotes to best effort or drops the ord`() {
        val result = BookSectionAligner.parse(
            "[[P214]]\nUno\n\n[[P215]]\n\n[[P216]]\nTres",
            ords,
        )

        // Whatever the grade, 215 must not be mapped to blank text.
        assertTrue(result.byOrd[215].orEmpty().isNotBlank() || 215 !in result.byOrd)
        assertTrue(result.byOrd.values.none { it.isBlank() })
    }

    @Test
    fun `garbage input yields an empty map, not a crash`() {
        val result = BookSectionAligner.parse("   \n  \n ", ords)
        assertTrue(result is Result.Misaligned)
        assertTrue(result.byOrd.isEmpty())
    }

    @Test
    fun `marker with spaces inside still matches`() {
        val result = BookSectionAligner.parse(
            "[[ P214 ]]\nUno\n\n[[P215]]\nDos\n\n[[P216]]\nTres",
            ords,
        )
        assertTrue(result is Result.Aligned)
        assertEquals("Uno", result.byOrd[214])
    }
}
