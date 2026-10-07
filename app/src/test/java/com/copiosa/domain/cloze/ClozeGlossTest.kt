package com.copiosa.domain.cloze

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a word means, in few enough words to sit under it in the feedback panel.
 *
 * Every rule here is paying for the same thing: dictionary prose is written for a page. A common
 * word carries a dozen senses, each sense is its own comma-list, regional qualifiers hang off them
 * in brackets, and the same sense repeats across a lemma and its inflections. Printed whole it
 * buries the comparison the gloss exists to serve.
 */
class ClozeGlossTest {

    @Test
    fun joinsTheFirstFewMeanings() {
        assertEquals("up", formatGloss(listOf("up")))
        assertEquals("up, above", formatGloss(listOf("up", "above")))
    }

    /** A comma-list inside one definition is a list of meanings, not one meaning. */
    @Test
    fun flattensACommaListWithinADefinition() {
        assertEquals("up, above, overhead", formatGloss(listOf("up, above, overhead")))
    }

    @Test
    fun stopsAtThreeMeanings() {
        assertEquals("up, above, overhead", formatGloss(listOf("up", "above", "overhead", "upstairs")))
    }

    /**
     * The case that prompted the budget: *guiño* comes back with its regional qualifiers attached,
     * and rendered whole it ran to five lines under the word.
     */
    @Test
    fun dropsRegionalAsidesAndStaysShort() {
        val gloss = formatGloss(
            listOf(
                "wink",
                "indicator (UK, Australia, New Zealand), turn signal (US), blinker (informal, US)",
                "direction indicator",
            ),
        )
        assertEquals("wink, indicator, turn signal", gloss)
    }

    /** Meanings are dropped rather than wrapped once they stop fitting. */
    @Test
    fun stopsAddingMeaningsOnceTheyNoLongerFit() {
        val gloss = formatGloss(listOf("a rather long first meaning here", "second", "third"))
        assertEquals("a rather long first meaning here, second", gloss)
    }

    /**
     * One over-long meaning still gets through. A truncated gloss says more than none, and null has
     * to keep meaning "the dictionary had nothing" so the caller can tell the two apart.
     */
    @Test
    fun keepsTheFirstMeaningEvenWhenItAloneBlowsTheBudget() {
        val long = "the female equivalent of a word that will not fit on any line at all"
        assertEquals(long, formatGloss(listOf(long)))
    }

    /** The same sense on a lemma and its inflections must not read as "up, up". */
    @Test
    fun dropsRepeatedMeaningsRegardlessOfCase() {
        assertEquals("up, above", formatGloss(listOf("up", "Up", "above")))
    }

    /** Order is the dictionary's: the first sense is the one worth reading. */
    @Test
    fun keepsDictionaryOrder() {
        assertEquals("ready, quick, prompt", formatGloss(listOf("ready", "quick", "prompt")))
    }

    @Test
    fun tidiesWhitespaceAndStrayPunctuation() {
        assertEquals("up, above", formatGloss(listOf("  up\n", "\tabove ;")))
    }

    @Test
    fun givesNullWhenThereIsNothingToSay() {
        assertNull(formatGloss(emptyList()))
        assertNull(formatGloss(listOf("", "   ")))
        // A definition that was nothing but a qualifier leaves nothing behind.
        assertNull(formatGloss(listOf("(obsolete)")))
    }
}
