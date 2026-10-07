package com.copiosa.ui.routines

import com.copiosa.ui.routines.activities.splitOnBlank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The question screen fills the blank in with the word that was chosen, which means it has to find
 * the blank first — and the app writes one three different ways. The cloze bank renders five
 * underscores, the routine and course plans write three, and neither constant is visible from the
 * screen that has to handle both. So the split matches a *run* of underscores rather than either
 * literal, and these tests are what keeps that honest if a third convention turns up.
 */
class SplitOnBlankTest {

    /** The cloze bank's blank: [com.copiosa.domain.model.Cloze.BLANK] is five underscores. */
    @Test
    fun findsTheBanksFiveUnderscoreBlank() {
        val split = splitOnBlank("Mira, hay un pájaro volando allá _____.")
        assertEquals("Mira, hay un pájaro volando allá " to ".", split)
    }

    /** The routine and course plans write three. */
    @Test
    fun findsThePlanThreeUnderscoreBlank() {
        val split = splitOnBlank("Ayer ___ al mercado.")
        assertEquals("Ayer " to " al mercado.", split)
    }

    /**
     * A PI item ("Who or when?") asks about a whole sentence and has no blank at all. Returning null
     * rather than a degenerate split is what stops the screen inlining an answer into a sentence
     * that never had a gap.
     */
    @Test
    fun returnsNullWhenThereIsNoBlank() {
        assertNull(splitOnBlank("Los niños comieron toda la tarta."))
    }

    /** A blank can open the sentence, which leaves nothing before it. */
    @Test
    fun handlesASentenceInitialBlank() {
        assertEquals("" to " es mi hermana.", splitOnBlank("_____ es mi hermana."))
    }

    /** …and close it, which leaves nothing after. */
    @Test
    fun handlesASentenceFinalBlank() {
        assertEquals("No quiero " to "", splitOnBlank("No quiero ___"))
    }

    /**
     * Two underscores are punctuation someone typed, not a blank. The floor of three is what keeps
     * transcribed text from being torn in half.
     */
    @Test
    fun ignoresARunTooShortToBeABlank() {
        assertNull(splitOnBlank("el archivo se llama mi__foto.jpg"))
    }

    /** With more than one blank the first wins; the rest stay in the tail, visibly unfilled. */
    @Test
    fun splitsOnTheFirstBlankOnly() {
        assertEquals("Ni " to " ni ___ me gustan.", splitOnBlank("Ni ___ ni ___ me gustan."))
    }

    /** A run longer than either convention is still one blank, not a blank plus stray underscores. */
    @Test
    fun consumesTheWholeRunOfUnderscores() {
        assertEquals("Voy " to " mañana.", splitOnBlank("Voy ________ mañana."))
    }

    // --- emphasis left behind by blanking an emphasised word -----------------

    /**
     * Mined sentences arrive with the studied word emphasised, and blanking it strands the markers
     * on either side of the gap — "un *_____* de culturas", which is what the screen showed before
     * this was handled.
     */
    @Test
    fun eatsEmphasisStrandedAroundTheBlank() {
        assertEquals(
            "La ciudad es un " to " de culturas.",
            splitOnBlank("La ciudad es un *_____* de culturas."),
        )
    }

    /** Bold is two markers a side, and both go. */
    @Test
    fun eatsBoldMarkersToo() {
        assertEquals("Es " to " hoy.", splitOnBlank("Es **___** hoy."))
    }

    /**
     * Only a matched pair is eaten. An asterisk on one side is doing something else — a footnote
     * mark, a truncated sentence — and swallowing it would corrupt the text.
     */
    @Test
    fun leavesAnUnmatchedAsteriskAlone() {
        assertEquals("Es *" to " hoy.", splitOnBlank("Es *___ hoy."))
        assertEquals("Es " to "* hoy.", splitOnBlank("Es ___* hoy."))
    }

    /** Uneven pairs give up only what matches on both sides. */
    @Test
    fun eatsOnlyAsManyMarkersAsBothSidesShare() {
        assertEquals("Es *" to " hoy.", splitOnBlank("Es **___* hoy."))
    }
}
