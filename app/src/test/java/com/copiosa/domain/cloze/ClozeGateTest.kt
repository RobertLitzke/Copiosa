package com.copiosa.domain.cloze

import com.copiosa.domain.vocab.freq.FrequencyList
import com.copiosa.domain.vocab.freq.FunctionWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The gate is what stops the bank filling with items that have no single right answer, so these
 * cases are the feature's quality floor rather than incidental coverage.
 */
class ClozeGateTest {

    /** Enough of a Spanish list to exercise part of speech and comprehensibility. */
    private val list = FrequencyList {
        listOf(
            "de|other|de",
            "el|other|el,la,los,las",
            "que|other|que",
            "en|other|en",
            "un|other|un,una",
            "ser|verb|es,era,fue,son",
            "comer|verb|como,comes,come,comimos,comió",
            "casa|noun|casa,casas",
            "perro|noun|perro,perros",
            "gato|noun|gato,gatos",
            "grande|adj|grande,grandes",
            "viejo|adj|viejo,vieja,viejos",
            "vivir|verb|vivo,vive,vivió,vivimos",
            "ciudad|noun|ciudad,ciudades",
            "muy|adv|muy",
            "jardín|noun|jardín,jardines",
        )
    }

    private val known = setOf("el", "la", "en", "un", "una", "es", "muy", "de", "que")

    private fun candidate(
        sentence: String,
        answer: String,
        cardId: Long? = 1L,
        source: String = "context",
    ): ClozeCandidate {
        val start = sentence.indexOf(answer)
        return ClozeCandidate(
            sentence = sentence,
            answer = answer,
            answerStart = start,
            lemma = answer.lowercase(),
            cardId = cardId,
            translation = null,
            source = source,
        )
    }

    private fun reject(sentence: String, answer: String, cardId: Long? = 1L, pos: String? = null) =
        ClozeGate.reject(candidate(sentence, answer, cardId), known, list, pos)

    @Test
    fun acceptsAContentWordInAComprehensibleSentence() {
        assertNull(reject("El perro grande vive en la ciudad", "perro"))
    }

    // --- the rule that does most of the work ---------------------------------

    /**
     * A blanked preposition or article usually has several grammatical fillers, which is exactly the
     * ambiguity this mode must not ship. Those contrasts belong to the course machinery instead.
     */
    @Test
    fun refusesToBlankAFunctionWord() {
        assertEquals(
            ClozeReject.ANSWER_NOT_CONTENT_WORD,
            reject("El perro grande vive en la ciudad", "en"),
        )
        assertEquals(
            ClozeReject.ANSWER_NOT_CONTENT_WORD,
            reject("El perro grande vive en la ciudad", "la"),
        )
    }

    /** Nothing anywhere states a part of speech: refuse rather than guess. */
    @Test
    fun refusesAnAnswerWithNoStatedPartOfSpeech() {
        val c = candidate("Zzz qqq wwww vvvv uuuu", "qqq")
        assertEquals(ClozeReject.ANSWER_NOT_CONTENT_WORD, ClozeGate.reject(c, known, list, null))
    }

    @Test
    fun usesTheCardsPartOfSpeechWhenTheListHasNothingToSay() {
        // "jardín" is listed, so this exercises the card-POS fallback for an unlisted word.
        val c = candidate("El perro grande vive en el bosque", "bosque")
        assertNull(ClozeGate.reject(c, known, list, answerPos = "noun"))
    }

    // --- structural ----------------------------------------------------------

    @Test
    fun refusesSentencesOutsideTheLengthBand() {
        assertEquals(ClozeReject.TOO_SHORT, reject("El perro grande", "perro"))
        val long = "El perro grande y viejo de la casa vieja vive en la ciudad grande con un gato muy viejo"
        assertEquals(ClozeReject.TOO_LONG, reject(long, "perro"))
    }

    /** A second sighting of the answer spells it out for the learner. */
    @Test
    fun refusesWhenTheAnswerAppearsTwice() {
        assertEquals(
            ClozeReject.ANSWER_REPEATED,
            reject("El perro grande y el perro viejo", "perro"),
        )
    }

    /** Options render lower case, so a capitalised blank leaks. */
    @Test
    fun refusesASentenceInitialBlank() {
        assertEquals(
            ClozeReject.ANSWER_SENTENCE_INITIAL,
            reject("Perro grande vive en la ciudad", "perro"),
        )
    }

    @Test
    fun refusesMarkupAndUrls() {
        assertEquals(ClozeReject.MARKUP, reject("El perro grande vive en https://x.com", "perro"))
        assertEquals(ClozeReject.MARKUP, reject("El perro {{c1::grande}} vive en la casa", "perro"))
    }

    @Test
    fun refusesAWordThatIsNeitherStudiedNorFrequent() {
        assertEquals(
            ClozeReject.UNANCHORED,
            reject("El bosque grande vive en la ciudad", "bosque", cardId = null, pos = "noun"),
        )
    }

    // --- i+1 -----------------------------------------------------------------

    /**
     * The bundled frequency lists are content words only — the real Spanish list holds 1027 nouns,
     * 510 verbs, 369 adjectives and 86 adverbs, and not one article or preposition. So the
     * comprehensibility check must be handed [FunctionWords] or it scores `la`, `de` and `que` as
     * unknown vocabulary, and no ordinary sentence in the language can clear 75%.
     *
     * [list] above is deliberately unlike the shipped one on this point (it carries `de`/`el`/`que`
     * entries), which is why this case uses a content-only list of its own: the fixture that hid the
     * bug cannot also be the fixture that proves it fixed.
     */
    @Test
    fun readsGrammarWordsAsKnownRatherThanAsUnlearnedVocabulary() {
        val contentOnly = FrequencyList {
            listOf(
                "ciudad|noun|ciudad,ciudades",
                "cultura|noun|cultura,culturas",
                "tradición|noun|tradición,tradiciones",
                "mundo|noun|mundo,mundos",
                "crisol|noun|crisol,crisoles",
            )
        }
        val sentence = "La ciudad es un crisol de culturas y tradiciones de todo el mundo"

        assertEquals(
            ClozeReject.TOO_HARD,
            ClozeGate.reject(candidate(sentence, "crisol"), emptySet(), contentOnly, "noun"),
        )
        assertNull(
            ClozeGate.reject(
                candidate(sentence, "crisol"),
                knownForms = emptySet(),
                list = contentOnly,
                answerPos = "noun",
                functionWords = FunctionWords.forTag("es"),
            ),
        )
    }

    /** A genuinely unreadable sentence stays refused once grammar words stop masking the check. */
    @Test
    fun stillRefusesRealVocabularyItCannotRead() {
        assertEquals(
            ClozeReject.TOO_HARD,
            ClozeGate.reject(
                candidate("El perro grande come en la casa xxxxx yyyyy zzzzz", "perro"),
                knownForms = emptySet(),
                list = list,
                answerPos = null,
                functionWords = FunctionWords.forTag("es"),
            ),
        )
    }

    /** An item is only a vocabulary test if everything except the gap can be read. */
    @Test
    fun refusesASentenceTheLearnerCannotOtherwiseRead() {
        assertEquals(
            ClozeReject.TOO_HARD,
            ClozeGate.reject(
                candidate("El perro grande come en casa xxxxx yyyyy zzzzz", "perro"),
                knownForms = emptySet(),
                list = list,
            ),
        )
    }

    @Test
    fun refusesASentenceWithTooFewOtherContentWords() {
        assertEquals(
            ClozeReject.TOO_LITTLE_CONTEXT,
            reject("El perro es de la que en", "perro"),
        )
    }

    /**
     * A language with no bundled list can't have its sentences judged for readability at all, so the
     * gate falls back to the structural rules. Applying the i+1 check anyway would reject nearly
     * every sentence and leave the mode permanently empty for that language.
     */
    @Test
    fun degradesToStructuralChecksWithoutAList() {
        val c = candidate("El perro grande vive en la ciudad", "perro")
        assertNull(ClozeGate.reject(c, knownForms = emptySet(), list = null, answerPos = "noun"))
        assertEquals(
            ClozeReject.ANSWER_SENTENCE_INITIAL,
            ClozeGate.reject(
                candidate("Perro grande vive en la ciudad", "perro"),
                known, list = null, answerPos = "noun",
            ),
        )
    }
}
