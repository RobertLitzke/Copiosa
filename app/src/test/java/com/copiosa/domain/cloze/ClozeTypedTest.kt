package com.copiosa.domain.cloze

import org.junit.Assert.assertEquals
import org.junit.Test

class ClozeTypedTest {

    @Test
    fun `case, spacing and stray punctuation do not matter`() {
        assertEquals(TypedVerdict.EXACT, ClozeTyped.judge("  Gato ", "gato"))
        assertEquals(TypedVerdict.EXACT, ClozeTyped.judge("a  pesar de", "a pesar de"))
        assertEquals(TypedVerdict.EXACT, ClozeTyped.judge("gato.", "gato"))
    }

    @Test
    fun `a missing or wrong accent is the right word, flagged`() {
        assertEquals(TypedVerdict.ACCENTS, ClozeTyped.judge("jardin", "jardín"))
        assertEquals(TypedVerdict.ACCENTS, ClozeTyped.judge("comío", "comió"))
        assertEquals(TypedVerdict.ACCENTS, ClozeTyped.judge("pinguino", "pingüino"))
    }

    @Test
    fun `enye is a letter, not an accent`() {
        assertEquals(TypedVerdict.WRONG, ClozeTyped.judge("ano", "año"))
        assertEquals(TypedVerdict.EXACT, ClozeTyped.judge("año", "año"))
        assertEquals(TypedVerdict.ACCENTS, ClozeTyped.judge("señalo", "señaló"))
        assertEquals(TypedVerdict.WRONG, ClozeTyped.judge("senalo", "señaló"))
    }

    @Test
    fun `another word, or nothing, is wrong`() {
        assertEquals(TypedVerdict.WRONG, ClozeTyped.judge("perro", "gato"))
        assertEquals(TypedVerdict.WRONG, ClozeTyped.judge("gatos", "gato"))
        assertEquals(TypedVerdict.WRONG, ClozeTyped.judge("   ", "gato"))
    }

    @Test
    fun `the hint gives the first letter and the length`() {
        assertEquals("g···", ClozeTyped.firstLetterHint("gato"))
        assertEquals("a ····· ··", ClozeTyped.firstLetterHint("a pesar de"))
        assertEquals("", ClozeTyped.firstLetterHint(" "))
    }
}
