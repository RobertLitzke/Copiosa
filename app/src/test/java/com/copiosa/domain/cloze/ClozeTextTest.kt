package com.copiosa.domain.cloze

import com.copiosa.domain.model.Cloze
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The dedup key and the markup are both load-bearing: the key is what makes background mining
 * idempotent and what makes a thrown-out sentence stay thrown out, and the markup is what lets the
 * bank reuse the existing [Cloze] renderer instead of inventing a third blank convention.
 */
class ClozeTextTest {

    // --- the dedup key -------------------------------------------------------

    /** Two sources quoting one sentence differ by punctuation and spacing, and must collide. */
    @Test
    fun theSameSentencePunctuatedDifferentlyGivesOneKey() {
        val a = ClozeText.keyOf("El gato duerme en la casa.")
        val b = ClozeText.keyOf("el gato duerme  en la casa")
        val c = ClozeText.keyOf("  ¡El gato duerme en la casa!  ")
        assertEquals(a, b)
        assertEquals(a, c)
    }

    /**
     * The answer stays in the key. Stripping it would make two different blanks in one sentence
     * collide, so only one of them could ever be banked.
     */
    @Test
    fun differentSentencesGiveDifferentKeys() {
        assertNotEquals(
            ClozeText.keyOf("El gato duerme en la casa"),
            ClozeText.keyOf("El perro duerme en la casa"),
        )
    }

    @Test
    fun punctuationIsDroppedWithoutSplittingWords() {
        // "gato," must not become "gato ,"— otherwise the collapsed spacing differs from "gato".
        assertEquals("el gato duerme", ClozeText.keyOf("El gato, duerme"))
        assertEquals("dont stop", ClozeText.keyOf("Don't stop!"))
    }

    @Test
    fun blankTextGivesAnEmptyKey() {
        assertEquals("", ClozeText.keyOf("   "))
        assertEquals("", ClozeText.keyOf("¿?¡!"))
    }

    // --- markup --------------------------------------------------------------

    @Test
    fun wrapsTheAnswerInClozeMarkup() {
        val sentence = "El gato duerme en la casa"
        val markup = ClozeText.markupFor(candidate(sentence, "gato"))
        assertEquals("El {{c1::gato}} duerme en la casa", markup)
    }

    /** The point of the markup: the existing renderer must be able to read it back. */
    @Test
    fun roundTripsThroughTheExistingClozeRenderer() {
        val sentence = "El gato duerme en la casa"
        val markup = ClozeText.markupFor(candidate(sentence, "gato"))

        assertEquals(sentence, Cloze.answer(markup))
        assertEquals("El ${Cloze.BLANK} duerme en la casa", Cloze.question(markup, activeOrd = null))
        assertEquals(listOf("gato"), Cloze.activeAnswers(markup, activeOrd = null))
    }

    /**
     * The offset decides which occurrence is blanked, not a search — so a caller can't accidentally
     * blank the wrong one when a word repeats.
     */
    @Test
    fun blanksTheOccurrenceTheOffsetNames() {
        val sentence = "El gato ve al gato"
        val second = ClozeCandidate(
            sentence = sentence,
            answer = "gato",
            answerStart = sentence.lastIndexOf("gato"),
            lemma = "gato",
            cardId = null,
            translation = null,
            source = "context",
        )
        assertEquals("El gato ve al {{c1::gato}}", ClozeText.markupFor(second))
    }

    /** A miner that miscomputed an offset must produce a plain sentence, not corrupt markup. */
    @Test
    fun anOutOfRangeOffsetLeavesTheSentenceAlone() {
        val sentence = "El gato duerme"
        val bad = candidate(sentence, "gato").copy(answerStart = 99)
        assertEquals(sentence, ClozeText.markupFor(bad))
    }

    private fun candidate(sentence: String, answer: String) = ClozeCandidate(
        sentence = sentence,
        answer = answer,
        answerStart = sentence.indexOf(answer),
        lemma = answer,
        cardId = 1L,
        translation = null,
        source = "context",
    )
}
