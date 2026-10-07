package com.copiosa.data.vocab

import com.copiosa.domain.srs.ReviewWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the seeder's own decisions. Which words it picks is [com.copiosa.domain.vocab.freq.VocabCoverage]'s
 * job and is tested there; what is left here is the pacing that stops a seed of a thousand words from
 * burying the review queue, and the part-of-speech translation between the dictionary pack's
 * vocabulary and the card editor's.
 */
class FrequencySeederTest {

    private val now = 1_000_000_000_000L
    private val day = ReviewWindows.DAY_MS

    @Test
    fun `the first batch is due today and each later batch a day further out`() {
        assertEquals(now, FrequencySeeder.dueAtFor(now, index = 0, perDay = 20))
        assertEquals(now, FrequencySeeder.dueAtFor(now, index = 19, perDay = 20))
        assertEquals(now + day, FrequencySeeder.dueAtFor(now, index = 20, perDay = 20))
        assertEquals(now + day, FrequencySeeder.dueAtFor(now, index = 39, perDay = 20))
        assertEquals(now + 2 * day, FrequencySeeder.dueAtFor(now, index = 40, perDay = 20))
    }

    @Test
    fun `seeding the whole top 2000 spreads over months rather than landing at once`() {
        val last = FrequencySeeder.dueAtFor(now, index = 1999, perDay = 20)
        assertEquals(now + 99 * day, last)
        assertTrue("a full seed should not all be due immediately", last > now + 30 * day)
    }

    @Test
    fun `a nonsensical rate does not divide by zero`() {
        // The rate comes from a picker, but a zero would make every card due at once — or crash.
        assertEquals(now, FrequencySeeder.dueAtFor(now, index = 0, perDay = 0))
        assertEquals(now + 5 * day, FrequencySeeder.dueAtFor(now, index = 5, perDay = 0))
        assertEquals(now + 5 * day, FrequencySeeder.dueAtFor(now, index = 5, perDay = -3))
    }

    @Test
    fun `pack parts of speech map onto the card editor's vocabulary`() {
        // HighlightTargetProvider.posFilter reads these back to decide which paradigm a headword may
        // expand through, so "adj" instead of "adjective" would silently disable inflection matching.
        assertEquals("noun", FrequencySeeder.cardPosFor("noun"))
        assertEquals("verb", FrequencySeeder.cardPosFor("verb"))
        assertEquals("adjective", FrequencySeeder.cardPosFor("adj"))
        assertEquals("adverb", FrequencySeeder.cardPosFor("adv"))
        assertEquals("expression", FrequencySeeder.cardPosFor("intj"))
        assertEquals("phrase", FrequencySeeder.cardPosFor("phrase"))
    }

    @Test
    fun `an unrecognised part of speech falls back to unknown rather than passing through`() {
        assertEquals("unknown", FrequencySeeder.cardPosFor("participle"))
        assertEquals("unknown", FrequencySeeder.cardPosFor(""))
        assertEquals("noun", FrequencySeeder.cardPosFor("  NOUN "))
    }

    @Test
    fun `the provenance stamp is the value the cleanup query looks for`() {
        assertEquals("freq", FrequencySeeder.SOURCE)
    }
}
