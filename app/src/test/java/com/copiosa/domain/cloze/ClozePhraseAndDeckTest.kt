package com.copiosa.domain.cloze

import com.copiosa.domain.vocab.freq.FrequencyList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The two things the bank could not do before: blank a multi-word term whole, and draw wrong options
 * from the learner's own vocabulary as well as from the frequency list.
 */
class ClozePhraseAndDeckTest {

    private val list = FrequencyList {
        listOf(
            "casa|noun|casa,casas",
            "perro|noun|perro,perros",
            "gato|noun|gato,gatos",
            "coche|noun|coche,coches",
            "ciudad|noun|ciudad,ciudades",
            "grande|adj|grande,grandes",
            "viejo|adj|viejo,vieja,viejos",
            "nuevo|adj|nuevo,nueva",
            "largo|adj|largo,larga",
            "blanco|adj|blanco,blanca",
            "comer|verb|como,comes,come,comimos,comió",
            "vivir|verb|vivo,vive,vivió,vivimos",
            "salir|verb|salgo,sale,salió,salimos",
            "llover|verb|llueve,llovió",
        )
    }
    private val known = setOf("el", "la", "en", "un", "una", "es", "muy", "de", "que", "a", "y")

    private fun candidate(sentence: String, answer: String, cardId: Long? = 1L) = ClozeCandidate(
        sentence = sentence,
        answer = answer,
        answerStart = sentence.indexOf(answer),
        lemma = answer.lowercase(),
        cardId = cardId,
        translation = null,
        source = "context",
    )

    // --- multi-word blanks ---------------------------------------------------

    @Test
    fun `a phrase card's term is blanked whole`() {
        val c = candidate("El perro viejo salió de la casa a pesar de la lluvia grande", "a pesar de")
        assertNull(ClozeGate.reject(c, known, list, answerPos = "phrase"))
    }

    @Test
    fun `a phrase with no card behind it is an arbitrary run of words`() {
        val c = candidate("El perro viejo salió de la casa a pesar de la lluvia grande", "a pesar de", cardId = null)
        assertEquals(ClozeReject.UNANCHORED, ClozeGate.reject(c, known, list))
    }

    @Test
    fun `a phrase that opens the sentence or appears twice is refused like a word`() {
        val opening = candidate("A pesar de la lluvia el perro viejo salió de la casa", "A pesar de")
        assertEquals(ClozeReject.ANSWER_SENTENCE_INITIAL, ClozeGate.reject(opening, known, list))
        val twice = candidate("El gato vive a pesar de todo y el perro come a pesar de todo", "a pesar de")
        assertEquals(ClozeReject.ANSWER_REPEATED, ClozeGate.reject(twice, known, list))
    }

    @Test
    fun `a phrase draws other phrases, never single words`() {
        val options = ClozeDistractors.pick(
            answer = "a pesar de",
            answerPos = null,
            list = list,
            sentenceTokens = emptySet(),
            excluded = emptySet(),
            deckFallback = emptyList(),
            random = Random(3),
            deckPhrases = listOf("sin embargo", "por lo tanto", "a pesar de", "en cuanto a", "de repente"),
        )
        assertNotNull(options)
        assertEquals(3, options!!.size)
        assertTrue(options.all { it.contains(' ') })
        assertFalse("a pesar de" in options)
    }

    /** Found on device: *hacer estragos* beside *a pie*, *a mano*, *a ciegas* was the only verb phrase. */
    @Test
    fun `a verb phrase is set against other verb phrases when the deck has them`() {
        val phrases = listOf(
            "a pie", "a mano", "a ciegas", "sin embargo", "de repente",
            "meter la pata", "echar de menos", "dar la lata", "tomar el pelo",
        )
        repeat(10) { seed ->
            val options = requireNotNull(
                ClozeDistractors.pick(
                    answer = "hacer estragos", answerPos = null, list = list, sentenceTokens = emptySet(),
                    excluded = emptySet(), deckFallback = emptyList(), random = Random(seed), deckPhrases = phrases,
                ),
            )
            assertTrue("got $options", options.all { it.substringBefore(' ').endsWith("r") })
        }
    }

    @Test
    fun `a phrase with too few other phrases to offer is dropped, not padded`() {
        assertNull(
            ClozeDistractors.pick(
                answer = "a pesar de", answerPos = null, list = list, sentenceTokens = emptySet(),
                excluded = emptySet(), deckFallback = emptyList(), random = Random(3),
                deckPhrases = listOf("sin embargo"),
            ),
        )
    }

    // --- options from the learner's own deck ---------------------------------

    private val deck = mapOf(
        "yermo" to "adjective", "sombrío" to "adjective", "tacaño" to "adjective", "zurdo" to "adjective",
        "lagartija" to "noun", "viejo" to "adjective",
    )

    private fun pick(answer: String, pos: String?, seed: Int = 5) = ClozeDistractors.pick(
        answer = answer,
        answerPos = pos,
        list = list,
        sentenceTokens = emptySet(),
        excluded = emptySet(),
        deckFallback = emptyList(),
        random = Random(seed),
        deckForms = deck,
    )

    /** The old failure: a rare answer beside three common words is the odd one out. */
    @Test
    fun `a rare answer is set against words the learner is also studying`() {
        repeat(10) { seed ->
            val options = requireNotNull(pick("yermo", "adjective", seed))
            val fromDeck = options.count { it in deck && list.matchOfForm(it) == null }
            assertEquals("got $options", 2, fromDeck)
            // The list still supplies one, so it never becomes "which of my cards is this".
            assertEquals(1, options.count { list.matchOfForm(it) != null })
        }
    }

    @Test
    fun `deck options keep to the answer's part of speech`() {
        repeat(10) { seed ->
            assertFalse("lagartija" in requireNotNull(pick("yermo", "adjective", seed)))
        }
    }

    /** Found on device: *he* beside *pertreche*, *marche*, *sospeche* — the only everyday word offered. */
    @Test
    fun `a common answer keeps common company`() {
        repeat(10) { seed ->
            val options = requireNotNull(pick("viejo", null, seed))
            assertTrue("got $options", options.all { list.matchOfForm(it) != null })
        }
    }

    /**
     * A card says "adjective" where the list says "adj". Compared raw, an unlisted adjective matched
     * no pool at all and its sentence was silently dropped from every sitting.
     */
    @Test
    fun `a card's part of speech is read in the list's vocabulary`() {
        val options = ClozeDistractors.pick(
            answer = "yermo", answerPos = "adjective", list = list, sentenceTokens = emptySet(),
            excluded = emptySet(), deckFallback = emptyList(), random = Random(1), count = 2,
        )
        assertNotNull(options)
        assertTrue(options!!.all { list.matchOfForm(it)?.pos == "adj" })
    }
}
