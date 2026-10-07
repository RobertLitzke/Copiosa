package com.copiosa.domain.course

import com.copiosa.domain.conjugation.ConjugationSpecs
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.routine.ClozeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The runtime guard on generated grammar items. The rule under test is the one that makes generated
 * content safe to show unreviewed: the conjugation engine, not the model, decides whether an answer
 * belongs to the paradigm the lesson is teaching.
 */
class CourseContentValidatorTest {

    private val spec = ConjugationSpecs.SPANISH
    private val preterite = LessonScope(tenses = setOf(Tense.PRETERITE))
    private val contrast = LessonScope(
        tenses = setOf(Tense.PRETERITE, Tense.IMPERFECT),
        contrastCategory = "preterite_imperfect",
    )
    private val forms = ScopeFormIndex.build(spec, preterite)

    private fun slot(kind: LlmSlotKind = LlmSlotKind.CLOZE, count: Int = 4) =
        AuthoredSegment.LlmSlot(kind, count, "brief")

    private fun item(
        sentence: String = "Ayer ${ClozeItem.BLANK} tarde.",
        options: List<String> = listOf("llegué", "llegaba"),
        answerIndex: Int = 0,
    ) = CourseContentParser.ItemDto(sentence, options, answerIndex)

    @Test
    fun `well-formed preterite items pass`() {
        val segment = CourseContentValidator.validate(
            slot(), preterite,
            listOf(
                item(),
                item("Anoche ${ClozeItem.BLANK} mucho.", listOf("comí", "comía"), 0),
                item("Ella ${ClozeItem.BLANK} la puerta.", listOf("abrió", "abría"), 0),
            ),
            forms,
        )
        assertTrue(segment is ClozeSegment)
        assertEquals(3, (segment as ClozeSegment).items.size)
    }

    @Test
    fun `an answer the engine places in another tense is rejected`() {
        // "llegaba" is imperfect: a preterite lesson must not present it as the right answer.
        val segment = CourseContentValidator.validate(
            slot(), preterite,
            listOf(
                item(options = listOf("llegué", "llegaba"), answerIndex = 1),
                item("Anoche ${ClozeItem.BLANK} mucho.", listOf("comí", "comía"), 1),
                item("Ella ${ClozeItem.BLANK} la puerta.", listOf("abrió", "abría"), 1),
            ),
            forms,
        )
        assertNull(segment)
    }

    @Test
    fun `a verb outside the bundled lexicon is given the benefit of the doubt`() {
        // The lexicon carries 200 verbs; a sentence may legitimately use one it doesn't know, and
        // rejecting those would gut the generated content for no gain.
        val segment = CourseContentValidator.validate(
            slot(count = 2), preterite,
            listOf(
                item("Ayer ${ClozeItem.BLANK} la cena.", listOf("cociné", "cocinaba"), 0),
                item("Ella ${ClozeItem.BLANK} la puerta.", listOf("abrió", "abría"), 0),
            ),
            forms,
        )
        assertNotNull(segment)
        assertEquals(2, (segment as ClozeSegment).items.size)
    }

    @Test
    fun `a form that is both tenses counts as in scope`() {
        // "vivimos" is preterite *and* present first-plural. Ambiguity must not reject it.
        assertEquals(ScopeFormIndex.Verdict.IN_SCOPE, forms.verdict("vivimos"))
    }

    @Test
    fun `structurally broken items are dropped`() {
        val broken = listOf(
            item(sentence = "   "),
            item(options = listOf("solo")),
            item(answerIndex = 7),
            item(options = listOf("llegué", "")),
            item(options = listOf("llegué", "Llegué")),
            item(sentence = "Ayer llegué tarde."), // no blank
        )
        for (bad in broken) {
            assertNull(
                "should reject: $bad",
                CourseContentValidator.validate(slot(), preterite, listOf(bad, bad), forms),
            )
        }
    }

    @Test
    fun `too few survivors means no segment at all`() {
        val segment = CourseContentValidator.validate(
            slot(), preterite,
            listOf(item(), item(sentence = "   ")),
            forms,
        )
        assertNull(segment)
    }

    @Test
    fun `a contrast slot answerable by always picking one side is rejected`() {
        val sameSide = List(4) {
            item("Item $it ${ClozeItem.BLANK}.", listOf("comí", "comía"), 0)
        }
        assertNull(
            CourseContentValidator.validate(
                slot(), contrast, sameSide, ScopeFormIndex.build(spec, contrast),
            ),
        )
    }

    @Test
    fun `a contrast slot that varies its answers passes`() {
        val varied = listOf(
            item("Ayer ${ClozeItem.BLANK} mucho.", listOf("comí", "comía"), 0),
            item("De niño ${ClozeItem.BLANK} mucho.", listOf("comí", "comía"), 1),
            item("Anoche ${ClozeItem.BLANK} tarde.", listOf("llegué", "llegaba"), 0),
        )
        val segment = CourseContentValidator.validate(
            slot(), contrast, varied, ScopeFormIndex.build(spec, contrast),
        )
        assertTrue(segment is ClozeSegment)
        assertEquals("preterite_imperfect", (segment as ClozeSegment).category)
    }

    @Test
    fun `pi items need no blank and become a pi segment`() {
        val items = listOf(
            item("Llegué tarde a la reunión.", listOf("yo", "él"), 0),
            item("Llegaron tarde a la reunión.", listOf("nosotros", "ellos"), 1),
        )
        val segment = CourseContentValidator.validate(
            slot(LlmSlotKind.PI, count = 2), preterite, items, forms,
        )
        assertTrue(segment is PiSegment)
        assertEquals(2, (segment as PiSegment).items.size)
    }

    @Test
    fun `extra items beyond the authored count are trimmed`() {
        val many = List(9) {
            item("Sentence $it ${ClozeItem.BLANK}.", listOf("comí", "comía"), 0)
        }
        val segment = CourseContentValidator.validate(slot(count = 4), preterite, many, forms)
        assertEquals(4, (segment as ClozeSegment).items.size)
    }

    @Test
    fun `the over-request leaves headroom for rejects`() {
        assertTrue(CourseContentValidator.requestCount(4) > 4)
        assertEquals(6, CourseContentValidator.requestCount(4))
    }

    @Test
    fun `with no paradigm data the morphology check simply stands down`() {
        val segment = CourseContentValidator.validate(
            slot(count = 2), preterite,
            listOf(
                item(options = listOf("llegaba", "llegué"), answerIndex = 0),
                item("Ella ${ClozeItem.BLANK} la puerta.", listOf("abría", "abrió"), 0),
            ),
            ScopeFormIndex.EMPTY,
        )
        assertNotNull(segment)
    }
}
