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

    @Test
    fun `a multi-line table survives as one paragraph`() {
        // A table is stored as a single block, so it arrives back under a single marker with its
        // newlines intact. Bodies run to the next marker rather than to the next blank line.
        val table = "| Persona | Verbo |\n| --- | --- |\n| yo | hablo |"
        val result = BookSectionAligner.parse("[[P214]]\n$table\n\n[[P215]]\nDos\n\n[[P216]]\nTres", ords)

        assertTrue(result is Result.Aligned)
        assertEquals(table, result.byOrd[214])
    }

    @Test
    fun `the best-effort split does not tear a table apart`() {
        // On the misaligned path blocks are split on blank lines. A pipe table has none inside it,
        // so it stays whole — which is what keeps it parseable as a table on the way out.
        val table = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        val result = BookSectionAligner.parse("$table\n\nDos\n\nTres", ords)

        assertTrue(result is Result.Misaligned)
        assertEquals(table, result.byOrd[214])
    }

    @Test
    fun `a reply cut short keeps the paragraphs it finished and drops the one it stopped in`() {
        val done = BookSectionAligner.completedPrefix("[[P214]]\nUno\n\n[[P215]]\nDos, pero sin ter", ords)

        assertEquals(mapOf(214 to "Uno"), done)
    }

    @Test
    fun `a cut inside the last paragraph is not mistaken for a finished section`() {
        // Every marker is present, so parse() calls this aligned — with half a paragraph in the
        // final row. The prefix reader is what knows the last one cannot be trusted.
        val raw = "[[P214]]\nUno\n\n[[P215]]\nDos\n\n[[P216]]\nTres, a med"

        assertTrue(BookSectionAligner.parse(raw, ords) is Result.Aligned)
        assertEquals(setOf(214, 215), BookSectionAligner.completedPrefix(raw, ords).keys)
    }

    @Test
    fun `a cut-off reply that ignored the markers has no usable prefix`() {
        assertTrue(BookSectionAligner.completedPrefix("Uno\n\nDos", ords).isEmpty())
        assertTrue(BookSectionAligner.completedPrefix("[[P1]]\nUno\n\n[[P2]]\nDos", ords).isEmpty())
        // A single paragraph that was itself cut is the stopping point, so nothing is finished.
        assertTrue(BookSectionAligner.completedPrefix("[[P214]]\nUno, a med", ords).isEmpty())
    }
}
