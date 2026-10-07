package com.copiosa.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The word boundary Android hands back is not the word: `WordIterator` treats a trailing punctuation
 * run as part of the boundary it abuts, so the last word of a sentence comes back with its full stop
 * attached — and that range is what gets highlighted, copied and looked up.
 */
class ReadingSelectionSpanTest {

    private fun trim(text: String, min: Int, max: Int): String {
        val (start, end) = trimWordPunctuation(text, min, max)
        return text.substring(start, end)
    }

    @Test
    fun `a trailing full stop is not part of the word`() {
        assertEquals("hola", trim("hola.", 0, 5))
    }

    @Test
    fun `leading marks are shed too`() {
        assertEquals("Qué", trim("¿Qué?", 0, 5))
        assertEquals("hola", trim("«hola»", 0, 6))
    }

    @Test
    fun `a clean word is returned untouched`() {
        assertEquals("hola", trim("hola mundo", 0, 4))
    }

    @Test
    fun `marks inside a word survive`() {
        assertEquals("ex-marido", trim("ex-marido.", 0, 10))
        assertEquals("l'ami", trim("l'ami,", 0, 6))
    }

    @Test
    fun `an accented word keeps its letters`() {
        assertEquals("montaña", trim("montaña.", 0, 8))
    }

    @Test
    fun `a boundary that is all punctuation is left alone`() {
        // Tapping the stop itself: there is no word to find, and collapsing the span would silently
        // move the selection onto a neighbouring word.
        assertEquals("...", trim("hola... adiós", 4, 7))
    }
}
