package com.copiosa.domain.cloze

import com.copiosa.domain.vocab.freq.FrequencyList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Where the anti-ambiguity design is actually proven. Each test names one way an option set can be
 * wrong: it can hand the answer over (grammar that doesn't agree), or it can contain a second
 * correct answer (a synonym, or the same word in another inflection).
 */
class ClozeDistractorsTest {

    private val list = FrequencyList {
        listOf(
            "ser|verb|es,era,fue,fuimos,somos",
            "comer|verb|como,comes,come,comimos,comió",
            "vivir|verb|vivo,vive,vivió,vivimos",
            "partir|verb|parto,parte,partió,partimos",
            "casa|noun|casa,casas",
            "perro|noun|perro,perros",
            "gato|noun|gato,gatos",
            "coche|noun|coche,coches",
            "grande|adj|grande,grandes",
            "pequeño|adj|pequeño,pequeña,pequeños",
            "viejo|adj|viejo,vieja,viejos",
        )
    }

    private val rng = Random(7)

    private fun pick(
        answer: String,
        pos: String? = null,
        sentence: Set<String> = emptySet(),
        excluded: Set<String> = emptySet(),
        count: Int = 3,
    ) = ClozeDistractors.pick(
        answer = answer,
        answerPos = pos,
        list = list,
        sentenceTokens = sentence,
        excluded = excluded,
        deckFallback = emptyList(),
        random = rng,
        count = count,
    )

    // --- grammar must not give the answer away -------------------------------

    /**
     * The single most important behaviour: a first-person-plural preterite must draw other
     * first-person-plural preterites, so agreement with the subject can't eliminate options.
     */
    @Test
    fun drawsDistractorsSharingTheAnswersEnding() {
        val options = requireNotNull(pick("comimos"))
        assertTrue(
            "expected -imos forms, got $options",
            options.all { it.endsWith("imos") },
        )
    }

    @Test
    fun staysWithinTheAnswersPartOfSpeech() {
        val nouns = requireNotNull(pick("perro"))
        assertTrue("expected nouns, got $nouns", nouns.all { list.matchOfForm(it)?.pos == "noun" })
    }

    // --- there must be only one right answer ---------------------------------

    /**
     * Another inflection of the same verb is not a wrong answer, it is a different tense — and it may
     * well fit the sentence. Different lemma, always.
     */
    @Test
    fun neverOffersAnotherInflectionOfTheSameWord() {
        repeat(20) {
            val options = requireNotNull(pick("comimos"))
            assertFalse("$options leaked a form of comer", options.any { it in setOf("como", "comes", "come", "comió") })
        }
    }

    /** The card's own synonym field is the app's existing record of "these mean the same thing". */
    @Test
    fun refusesExcludedSynonyms() {
        repeat(20) {
            val options = requireNotNull(pick("grande", excluded = setOf("pequeño", "viejo")))
            assertFalse("$options included an excluded synonym", options.any { it in setOf("pequeño", "viejo") })
        }
    }

    @Test
    fun neverOffersAWordAlreadyInTheSentence() {
        repeat(20) {
            val options = requireNotNull(pick("perro", sentence = setOf("gato", "casa")))
            assertFalse("$options repeated a sentence word", options.any { it in setOf("gato", "casa") })
        }
    }

    @Test
    fun neverOffersTheAnswerItself() {
        repeat(20) { assertFalse(requireNotNull(pick("perro")).contains("perro")) }
    }

    // --- failure is a dropped item, never a padded one -----------------------

    /**
     * Padding with junk would make the item free. The caller is expected to drop it instead, so the
     * contract here is null rather than a short list.
     */
    @Test
    fun returnsNullRatherThanTooFewOptions() {
        assertNull(pick("perro", count = 50))
        val tiny = FrequencyList { listOf("perro|noun|perro") }
        assertNull(
            ClozeDistractors.pick(
                "perro", "noun", tiny, emptySet(), emptySet(), emptyList(), rng, count = 3,
            ),
        )
    }

    @Test
    fun fallsBackToTheDeckWhenTheLanguageHasNoList() {
        val options = ClozeDistractors.pick(
            answer = "perro",
            answerPos = "noun",
            list = null,
            sentenceTokens = emptySet(),
            excluded = emptySet(),
            deckFallback = listOf("gato", "casa", "coche", "perro"),
            random = rng,
            count = 3,
        )
        assertEquals(setOf("gato", "casa", "coche"), requireNotNull(options).toSet())
    }

    // --- the suffix heuristic itself -----------------------------------------

    @Test
    fun sharedSuffixMeasuresMorphologicalShape() {
        assertEquals(3, ClozeDistractors.sharedSuffix("comimos", "vivimos"))
        assertEquals(0, ClozeDistractors.sharedSuffix("comimos", "gato"))
        // Capped at three so it stops discriminating past the inflectional ending.
        assertEquals(3, ClozeDistractors.sharedSuffix("hablando", "cantando"))
        // A shared final vowel is a weak signal, and scores as such rather than as agreement.
        assertEquals(1, ClozeDistractors.sharedSuffix("perro", "gato"))
        assertEquals(0, ClozeDistractors.sharedSuffix("", "gato"))
    }

    /** Same seed, same options: a sitting must not reshuffle under the learner. */
    @Test
    fun isDeterministicForAGivenSeed() {
        val a = ClozeDistractors.pick("perro", null, list, emptySet(), emptySet(), emptyList(), Random(42))
        val b = ClozeDistractors.pick("perro", null, list, emptySet(), emptySet(), emptyList(), Random(42))
        assertEquals(a, b)
    }
}
