package com.copiosa.domain.vocab.freq

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.srs.ReviewWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Covers the card-to-frequency-list join.
 *
 * The join reuses each card's cached `inflectedForms` rather than lemmatising afresh, so most of what
 * is worth pinning is that it reads the *target* side of a card and that the two coverage figures —
 * "in deck" and "mature" — genuinely differ, since collapsing them would let a fresh seed of a
 * thousand words read as fluency.
 */
class VocabCoverageTest {

    private val coverage = VocabCoverage()

    /** A small stand-in list: ranks 1..5, with a couple of inflected forms each. */
    private val list = FrequencyList {
        listOf(
            "ser|verb|es,era,fue,ser",
            "gato|noun|gato,gatos",
            "casa|noun|casa,casas",
            "libro|noun|libro,libros",
            "verde|adj|verde,verdes",
        )
    }

    // --- matching -----------------------------------------------------------

    @Test
    fun `matches a card by its headword`() {
        assertEquals(2, coverage.rankOf(card(front = "gato"), list))
        assertEquals(1, coverage.rankOf(card(front = "ser"), list))
    }

    @Test
    fun `matches a card headworded as an inflection, via its cached forms`() {
        // A card saved as "gatos" carries the whole paradigm, which includes the list's headword.
        val inflected = card(front = "gatos", inflectedForms = "gato;gatos")
        assertEquals(2, coverage.rankOf(inflected, list))
    }

    @Test
    fun `matches a reverse card on its target side, not its English front`() {
        // An imported reverse card prompts from the native gloss; ranking it by "house" would both
        // miss the Spanish word and, worse, rank cards by their English spelling.
        val reverse = card(front = "house", back = "casa", frontIsTarget = false)
        assertEquals(3, coverage.rankOf(reverse, list))
    }

    // --- homographs ----------------------------------------------------------

    /**
     * The generator rolls a homograph's corpus counts onto the reading the corpus is mostly about,
     * so Spanish *dado* (a die) and *traje* (a suit) have no entry of their own — they sit in the
     * paradigms of *dar* and *traer*. Ranking a learner's noun card through those made the noun read
     * as a top-100 verb, chip and all, and put it in the frequent-word drill.
     */
    @Test
    fun `a noun card is not ranked through a verb's paradigm`() {
        val homographs = FrequencyList { listOf("dar|verb|da,dado,dar,doy", "gato|noun|gato,gatos") }

        assertNull(coverage.rankOf(card(front = "dado", partOfSpeech = "noun"), homographs))
        // The verb itself, and an ordinary inflection of a noun, are untouched.
        assertEquals(1, coverage.rankOf(card(front = "dado", partOfSpeech = "verb"), homographs))
        assertEquals(2, coverage.rankOf(card(front = "gatos", partOfSpeech = "noun"), homographs))
    }

    @Test
    fun `a card that states no part of speech still ranks`() {
        // Most of the deck is enriched later, if at all; refusing every un-annotated card would
        // quietly empty the coverage figures rather than correct them.
        val homographs = FrequencyList { listOf("dar|verb|da,dado,dar,doy") }

        assertEquals(1, coverage.rankOf(card(front = "dado", partOfSpeech = null), homographs))
        assertEquals(1, coverage.rankOf(card(front = "dado", partOfSpeech = "unknown"), homographs))
    }

    @Test
    fun `a recorded gender is enough to know the card is a noun`() {
        // An imported card often has a gender and nothing else. Only nouns have one, so "el traje"
        // is a noun whether or not the part-of-speech field was ever filled in — and that is what
        // keeps it out of traer's paradigm without waiting for the enrichment pass to reach it.
        val homographs = FrequencyList { listOf("traer|verb|trae,traer,traje,trajo") }

        assertNull(coverage.rankOf(card(front = "traje", gender = "M"), homographs))
        assertEquals(1, coverage.rankOf(card(front = "traje"), homographs))
    }

    @Test
    fun `a headword match is never refused on part of speech`() {
        // "casa" is the list's own headword; whatever we think the card is, it is that word.
        assertEquals(3, coverage.rankOf(card(front = "casa", partOfSpeech = "verb"), list))
    }

    @Test
    fun `the real Spanish list still ranks its verbs and no longer ranks their noun homographs`() {
        val es = FrequencyLists.forTag("es")!!
        val dar = es.rankOfForm("dar")!!
        val traer = es.rankOfForm("traer")!!

        // The collision is real: both forms live in a verb's paradigm and nothing else claims them.
        assertEquals(dar, es.rankOfForm("dado"))
        assertEquals(traer, es.rankOfForm("traje"))

        assertEquals(dar, coverage.rankOf(card(front = "dar", partOfSpeech = "verb"), es))
        assertNull(coverage.rankOf(card(front = "dado", partOfSpeech = "noun"), es))
        assertNull(coverage.rankOf(card(front = "traje", partOfSpeech = "noun"), es))
    }

    @Test
    fun `words outside the list, phrases and sentences are unranked`() {
        assertNull(coverage.rankOf(card(front = "murciélago"), list))
        assertNull(coverage.rankOf(card(front = "de vez en cuando", cardKind = CardKind.PHRASE), list))
        assertNull(coverage.rankOf(card(front = "casa", cardKind = CardKind.SENTENCE), list))
        assertNull(coverage.rankOf(card(front = ""), list))
    }

    @Test
    fun `ranksByCard indexes only the cards that are in the list`() {
        val cards = listOf(
            card(id = 1, front = "gato"),
            card(id = 2, front = "murciélago"),
            card(id = 3, front = "ser"),
        )
        assertEquals(mapOf(1L to 2, 3L to 1), coverage.ranksByCard(cards, list))
    }

    // --- coverage -----------------------------------------------------------

    @Test
    fun `in-deck counts any card, mature counts only settled ones`() {
        val cards = listOf(
            card(id = 1, front = "ser", intervalDays = 40.0),
            card(id = 2, front = "gato", intervalDays = 40.0),
            // In the deck but nowhere near learned — freshly seeded.
            card(id = 3, front = "casa", intervalDays = 0.0),
            card(id = 4, front = "libro", intervalDays = 5.0),
        )

        val band = coverage.coverage(cards, list).first { it.bucket == FrequencyBucket.TOP_100 }
        // The stand-in list is only 5 long, so the band is capped at the list's size, not at 100.
        assertEquals(5, band.total)
        assertEquals(4, band.inDeck)
        assertEquals(2, band.mature)
        assertEquals(80, band.inDeckPercent)
        assertEquals(40, band.maturePercent)
    }

    @Test
    fun `the maturity cutoff is the shared threshold, not a local guess`() {
        val justUnder = ReviewWindows.MATURE_THRESHOLD_DAYS - 0.1
        val atCutoff = ReviewWindows.MATURE_THRESHOLD_DAYS

        val under = coverage.coverage(listOf(card(front = "ser", intervalDays = justUnder)), list)
        val at = coverage.coverage(listOf(card(front = "ser", intervalDays = atCutoff)), list)

        assertEquals(0, under.first().mature)
        assertEquals(1, at.first().mature)
    }

    @Test
    fun `coverage of an empty deck is zero everywhere, not a divide by zero`() {
        coverage.coverage(emptyList(), list).forEach {
            assertEquals(0, it.inDeck)
            assertEquals(0, it.inDeckPercent)
            assertEquals(0, it.maturePercent)
        }
    }

    // --- the seeding backlog -------------------------------------------------

    @Test
    fun `missing returns unowned words in rank order`() {
        val cards = listOf(card(id = 1, front = "gato"), card(id = 2, front = "libro"))

        val missing = coverage.missing(cards, list, maxRank = 5)

        assertEquals(listOf("ser", "casa", "verde"), missing.map { it.lemma })
        assertEquals(listOf(1, 3, 5), missing.map { it.rank })
    }

    @Test
    fun `missing respects the rank ceiling`() {
        assertEquals(listOf("ser", "gato"), coverage.missing(emptyList(), list, maxRank = 2).map { it.lemma })
    }

    @Test
    fun `a frozen word is not offered again`() {
        // The user set this word aside deliberately; re-adding it on every seed would be a bug that
        // quietly resurrects everything they have ever rejected.
        val cards = listOf(card(id = 1, front = "gato", suspended = true))

        assertEquals(listOf("ser", "casa", "libro", "verde"), coverage.missing(cards, list, 5).map { it.lemma })
    }

    // --- helpers ------------------------------------------------------------

    private fun card(
        id: Long = 1L,
        front: String = "gato",
        back: String = "cat",
        frontIsTarget: Boolean = true,
        cardKind: CardKind = CardKind.VOCAB,
        inflectedForms: String? = null,
        intervalDays: Double = 0.0,
        suspended: Boolean = false,
        partOfSpeech: String? = null,
        gender: String? = null,
    ) = FlashcardEntity(
        id = id,
        front = front,
        back = back,
        frontIsTarget = frontIsTarget,
        cardKind = cardKind,
        inflectedForms = inflectedForms,
        partOfSpeech = partOfSpeech,
        gender = gender,
        intervalDays = intervalDays,
        suspended = suspended,
        targetLanguage = "es",
        nativeLanguage = "en",
        createdAt = 0L,
        dueAt = 0L,
    )
}
