package com.copiosa.domain.course

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that decides whether an authored line break survives. Both halves matter: get it
 * wrong one way and the ending lists render as a single run-on line, wrong the other way and
 * ordinary prose is chopped into fragments mid-sentence.
 */
class ProseTextTest {

    @Test
    fun `wrapped prose is rejoined into one paragraph`() {
        val text = """
            The preterite reports an event as **complete**: it
            happened, it ended, the story moved on.
        """.trimIndent()
        assertEquals(
            listOf("The preterite reports an event as **complete**: it happened, it ended, the story moved on."),
            ProseText.paragraphs(text),
        )
    }

    @Test
    fun `blank lines separate paragraphs as always`() {
        val text = "First thing.\n\nSecond thing."
        assertEquals(listOf("First thing.", "Second thing."), ProseText.paragraphs(text))
    }

    @Test
    fun `a run of bold-leading lines is a list, one item per paragraph`() {
        val text = """
            Half of all Spanish verbs end in -ar:
            **yo → -é** · *hablar → hablé*
            **tú → -aste** · *hablar → hablaste*
            The accent is not decoration.
        """.trimIndent()
        assertEquals(
            listOf(
                "Half of all Spanish verbs end in -ar:",
                "**yo → -é** · *hablar → hablé*",
                "**tú → -aste** · *hablar → hablaste*",
                "The accent is not decoration.",
            ),
            ProseText.paragraphs(text),
        )
    }

    /**
     * The case that makes the rule a *run* rather than "any line opening with bold": this is one
     * sentence that happens to wrap just before an emphasised word, and splitting it would leave
     * "…for years otherwise:" stranded as its own paragraph.
     */
    @Test
    fun `a lone bold-leading line is wrapped prose, not a list`() {
        val text = """
            A wrinkle worth fixing now, because it costs marks for years otherwise:
            **hablaste** has no written accent. The stress already falls where Spanish
            expects it, so nothing needs marking.
        """.trimIndent()
        val paragraphs = ProseText.paragraphs(text)
        assertEquals(1, paragraphs.size)
        assertTrue(paragraphs.single().startsWith("A wrinkle worth fixing now"))
        assertTrue(paragraphs.single().contains("otherwise: **hablaste** has no written accent"))
    }

    @Test
    fun `every authored lesson survives the round trip with nothing dropped`() {
        for (tag in listOf("es", "pt")) {
            for (course in CourseCatalog.forTag(tag)) {
                for (lesson in course.lessons) {
                    val prose = lesson.segments
                        .filterIsInstance<AuthoredSegment.Didactic>()
                        .flatMap { it.blocks }
                        .filterIsInstance<DidacticBlock.Prose>()
                    for (block in prose) {
                        val where = "${course.id}/${lesson.id}"
                        val words = block.text.split(Regex("\\s+")).filter { it.isNotBlank() }
                        val kept = ProseText.paragraphs(block.text)
                            .flatMap { it.split(Regex("\\s+")) }
                            .filter { it.isNotBlank() }
                        assertEquals("$where lost or gained words", words, kept)
                    }
                }
            }
        }
    }
}
