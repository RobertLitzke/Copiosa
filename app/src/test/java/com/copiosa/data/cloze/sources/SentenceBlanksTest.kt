package com.copiosa.data.cloze.sources

import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.vocab.freq.FrequencyList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceBlanksTest {

    private val list = FrequencyList {
        listOf("haber|verb|he,ha,han", "tener|verb|tiene,tengo", "momento|noun|momento,momentos", "casa|noun|casa,casas")
    }

    private fun ctx(
        cards: Map<String, Long> = mapOf("perro" to 1L, "perros" to 1L),
        banked: Map<String, Int> = emptyMap(),
        settled: Set<Long> = emptySet(),
    ) = ClozeMineContext(
        lang = "es", formToCard = cards, list = list, banked = banked, posByCard = emptyMap(),
        functionWords = setOf("el", "la", "en", "de", "que", "no"), bankSize = 0, settledCards = settled,
    )

    private fun pick(sentence: String, ctx: ClozeMineContext = ctx(), proposed: MutableSet<String> = HashSet()) =
        SentenceBlanks.pick(sentence, ctx, "book:1", null, proposed)

    // --- tidying ---

    @Test
    fun `half a pair of quotation marks is removed, a whole pair is kept`() {
        assertEquals("No dijo mucho, pero apuesto a que sí.", SentenceBlanks.clean(" No dijo mucho, pero apuesto a que sí”. "))
        assertEquals("“No tiene sentido”, dijo.", SentenceBlanks.clean("“No tiene sentido”, dijo."))
    }

    @Test
    fun `a byte-order mark never reaches the bank`() {
        assertEquals("Of course.", SentenceBlanks.clean("﻿Of course."))
    }

    @Test
    fun `reading sources take only sentences short enough to hear`() {
        assertTrue(SentenceBlanks.comfortable("El perro viejo come en la casa"))
        assertFalse(SentenceBlanks.comfortable("El perro"))
        assertFalse(SentenceBlanks.comfortable("uno dos tres cuatro cinco seis siete ocho nueve diez once doce trece"))
    }

    // --- choosing the blank ---

    @Test
    fun `a carded word is blanked before a common one`() {
        val picked = requireNotNull(pick("En ese momento los perros entraron en la casa"))
        assertTrue(picked.carded)
        assertEquals("perros", picked.candidate.answer)
        assertEquals("perro", picked.candidate.lemma)
        assertEquals(1L, picked.candidate.cardId)
    }

    @Test
    fun `with no carded word a common content word is blanked, once`() {
        val proposed = HashSet<String>()
        val picked = requireNotNull(pick("En ese momento no tiene sentido", proposed = proposed))
        assertFalse(picked.carded)
        assertEquals("momento", picked.candidate.answer)
        assertNull(picked.candidate.cardId)
        // The same word is not offered twice in one run; the next common word is taken instead.
        assertEquals("tiene", pick("En ese momento no tiene sentido", proposed = proposed)?.candidate?.answer)
    }

    @Test
    fun `short auxiliaries and words already banked are not blanked`() {
        assertNull(pick("Le he dicho que no"))
        assertNull(pick("En ese momento no", ctx(banked = mapOf("momento" to 1))))
    }

    @Test
    fun `a settled card's word is passed over`() {
        val picked = pick("En ese lugar los perros entraron", ctx(settled = setOf(1L)))
        assertNull(picked)
    }

    @Test
    fun `the first word of a sentence is never the blank`() {
        assertNull(pick("Perros hay en ese lugar"))
    }
}
