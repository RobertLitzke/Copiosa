package com.copiosa.domain.reading

import org.junit.Assert.assertEquals
import org.junit.Test

class BilingualBlocksTest {

    @Test
    fun split_breaksOnBlankLinesAndTrims() {
        assertEquals(
            listOf("# Title", "First para.", "Second para."),
            BilingualBlocks.split("# Title\n\n  First para.  \n\n\n Second para.\n"),
        )
        assertEquals(emptyList<String>(), BilingualBlocks.split("   \n\n  "))
    }

    @Test
    fun align_zipsMatchingCounts() {
        val rows = BilingualBlocks.align(listOf("one", "two"), listOf("uno", "dos"), targetComplete = true)
        assertEquals(listOf(BilingualBlock("uno", "one"), BilingualBlock("dos", "two")), rows)
    }

    @Test
    fun align_leavesTheUntranslatedTailPendingWhileTranslating() {
        // The blocks that exist are the *first* n paragraphs; spreading them would scatter them.
        val rows = BilingualBlocks.align(
            listOf("one", "two", "three", "four"),
            listOf("uno"),
            targetComplete = false,
        )
        assertEquals(listOf("uno", null, null, null), rows.map { it.target })
        assertEquals(listOf("one", "two", "three", "four"), rows.map { it.native })
    }

    @Test
    fun align_spreadsProportionallyOnceCompleteWithAMismatch() {
        // A finished translation that merged paragraphs: every source row still gets its share, and
        // no translated block is dropped.
        val rows = BilingualBlocks.align(
            listOf("one", "two", "three", "four"),
            listOf("uno", "dos"),
            targetComplete = true,
        )
        assertEquals(4, rows.size)
        assertEquals(listOf("one", "two", "three", "four"), rows.map { it.native })
        assertEquals(listOf("uno", "dos"), rows.mapNotNull { it.target })
    }

    @Test
    fun align_neverDropsASurplusTranslationBlock() {
        val rows = BilingualBlocks.align(listOf("one"), listOf("uno", "y uno más"), targetComplete = false)
        assertEquals(1, rows.size)
        assertEquals("uno\n\ny uno más", rows.single().target)
    }

    @Test
    fun align_survivesAnEmptySide() {
        assertEquals(
            listOf(BilingualBlock(null, "one")),
            BilingualBlocks.align(listOf("one"), emptyList(), targetComplete = false),
        )
        assertEquals(
            listOf(BilingualBlock("uno", null)),
            BilingualBlocks.align(emptyList(), listOf("uno"), targetComplete = true),
        )
    }
}
