package com.copiosa.domain.course

import com.copiosa.domain.conjugation.Cell
import com.copiosa.domain.conjugation.DrillItem
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.routine.ClozeItem
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The sitting-assembly rules, apart from any database: warm-up placement, ordering, and — the one
 * that matters most — that a lesson stays playable when the LLM has nothing to give it.
 */
class CourseSessionBuilderTest {

    private val preteriteScope = LessonScope(tenses = setOf(Tense.PRETERITE))
    private val imperfectScope = LessonScope(tenses = setOf(Tense.IMPERFECT))

    /** A lesson with no paradigm behind it: everything it practises, it wrote itself. */
    private val porParaScope = LessonScope(contrastCategory = "por_para")

    private fun bank(vararg answers: String) = AuthoredSegment.AuthoredDrill(
        answers.mapIndexed { i, answer ->
            AuthoredChoiceItem(
                sentence = "Item $i ___ aquí.",
                options = listOf("por", "para"),
                answerIndex = if (answer == "por") 0 else 1,
                explanation = "because.",
            )
        },
    )

    /** Records what it was asked for, and hands back plausible items. */
    private class FakeSelector(
        private val itemsPerCall: Int = 3,
    ) : DrillSelector {
        val calls = mutableListOf<Triple<LessonScope, Int, Double>>()
        var empty = false

        override suspend fun select(
            scope: LessonScope,
            itemCount: Int,
            irregularEmphasis: Double,
        ): List<DrillItem> {
            calls += Triple(scope, itemCount, irregularEmphasis)
            if (empty) return emptyList()
            val tense = scope.tenses.first()
            return List(minOf(itemsPerCall, itemCount)) {
                DrillItem.Produce("hablar", Cell(tense, Person.entries[it % Person.entries.size]))
            }
        }
    }

    private fun lesson(
        id: String,
        scope: LessonScope,
        vararg segments: AuthoredSegment,
    ) = LessonDefinition(
        id = id,
        title = id,
        tagline = "A test lesson.",
        estimatedMinutes = 12,
        scope = scope,
        segments = segments.toList(),
    )

    private fun didactic() = AuthoredSegment.Didactic(
        "Title",
        listOf(DidacticBlock.Prose("Some teaching prose.")),
    )

    @Test
    fun `the first lesson has no warm-up`() = runTest {
        val selector = FakeSelector()
        val segments = LessonSegmentBuilder.buildLesson(
            lesson = lesson("meaning", preteriteScope, didactic(), AuthoredSegment.EngineDrill(6)),
            priorLessons = emptyList(),
            language = "es",
            selector = selector,
        )
        assertTrue(segments.filterIsInstance<ConjDrillSegment>().none { it.warmUp })
        assertEquals(listOf(6), selector.calls.map { it.second })
    }

    @Test
    fun `a later lesson opens on a warm-up drawn from earlier scopes only`() = runTest {
        val selector = FakeSelector()
        val prior = listOf(
            lesson("meaning", preteriteScope),
            lesson("forms", imperfectScope),
        )
        val segments = LessonSegmentBuilder.buildLesson(
            lesson = lesson("usage", imperfectScope, didactic(), AuthoredSegment.EngineDrill(8)),
            priorLessons = prior,
            language = "es",
            selector = selector,
        )
        val first = segments.first()
        assertTrue(first is ConjDrillSegment && first.warmUp)

        // Each earlier lesson is sampled through its *own* scope — never a merged one, which is what
        // would let a warm-up ask for a cell no single lesson taught. Most recent first.
        val warmCalls = selector.calls.take(prior.size)
        assertEquals(
            listOf(imperfectScope, preteriteScope),
            warmCalls.map { it.first },
        )
        // Five cards shared out across them, the freshest lesson taking the odd one.
        assertEquals(LessonSegmentBuilder.WARM_UP_ITEMS, warmCalls.sumOf { it.second })
        assertEquals(listOf(3, 2), warmCalls.map { it.second })
        assertEquals(LessonSegmentBuilder.WARM_UP_ITEMS, (first as ConjDrillSegment).items.size)
    }

    @Test
    fun `the warm-up reaches back no further than its window`() = runTest {
        val selector = FakeSelector()
        val prior = List(6) { lesson("l$it", LessonScope(tenses = setOf(Tense.PRETERITE))) }
        LessonSegmentBuilder.buildLesson(
            lesson = lesson("last", preteriteScope, AuthoredSegment.EngineDrill(8)),
            priorLessons = prior,
            language = "es",
            selector = selector,
        )
        assertEquals(
            LessonSegmentBuilder.WARM_UP_LESSON_WINDOW,
            selector.calls.size - 1,
        )
    }

    @Test
    fun `an unfilled llm slot surrenders its practice time to an extra drill`() = runTest {
        val selector = FakeSelector()
        val target = lesson(
            "regular_ar",
            preteriteScope,
            didactic(),
            AuthoredSegment.EngineDrill(8),
            AuthoredSegment.LlmSlot(LlmSlotKind.CLOZE, 4, "cloze please"),
            AuthoredSegment.LlmSlot(LlmSlotKind.PI, 4, "pi please"),
        )
        val segments = LessonSegmentBuilder.buildLesson(target, emptyList(), "es", selector)

        assertTrue(segments.none { it is ClozeSegment || it is PiSegment })
        // Two drills: the authored one, and the fallback carrying both dropped slots' items.
        assertEquals(2, segments.filterIsInstance<ConjDrillSegment>().size)
        assertEquals(listOf(8, 8), selector.calls.map { it.second })
        assertTrue(LessonSegmentBuilder.isPlayable(segments))
    }

    @Test
    fun `a filled llm slot lands in place and claims no fallback`() = runTest {
        val selector = FakeSelector()
        val target = lesson(
            "aspect",
            preteriteScope,
            didactic(),
            AuthoredSegment.LlmSlot(LlmSlotKind.CLOZE, 4, "cloze please"),
        )
        val filled = ClozeSegment(
            items = listOf(ClozeItem("Ayer ___ tarde.", listOf("llegué", "llegaba"), 0)),
            category = "preterite_imperfect",
        )
        val segments = LessonSegmentBuilder.buildLesson(
            target, emptyList(), "es", selector, llmSegments = mapOf(1 to filled),
        )

        assertEquals(listOf(DidacticSegment::class, ClozeSegment::class, WrapUpSegment::class), segments.map { it::class })
        assertTrue("no fallback drill expected", selector.calls.isEmpty())
    }

    @Test
    fun `a drill that yields nothing is omitted rather than left empty`() = runTest {
        val selector = FakeSelector().apply { empty = true }
        val segments = LessonSegmentBuilder.buildLesson(
            lesson = lesson("meaning", preteriteScope, didactic(), AuthoredSegment.EngineDrill(6)),
            priorLessons = emptyList(),
            language = "es",
            selector = selector,
        )
        assertTrue(segments.filterIsInstance<ConjDrillSegment>().isEmpty())
        // The teaching beat still carries the sitting.
        assertTrue(LessonSegmentBuilder.isPlayable(segments))
    }

    @Test
    fun `a lesson with nothing but an unfillable slot is reported unplayable`() = runTest {
        val selector = FakeSelector().apply { empty = true }
        val segments = LessonSegmentBuilder.buildLesson(
            lesson = lesson(
                "narration",
                preteriteScope,
                AuthoredSegment.LlmSlot(LlmSlotKind.CLOZE, 10, "narrative cloze"),
            ),
            priorLessons = emptyList(),
            language = "es",
            selector = selector,
        )
        assertFalse(LessonSegmentBuilder.isPlayable(segments))
    }

    @Test
    fun `a micro-lesson replays teaching and drills, without llm slots`() = runTest {
        val selector = FakeSelector()
        val target = lesson(
            "strong_preterites",
            preteriteScope,
            didactic(),
            AuthoredSegment.EngineDrill(12),
            AuthoredSegment.LlmSlot(LlmSlotKind.CLOZE, 4, "cloze please"),
        )
        val segments = LessonSegmentBuilder.buildMicroLesson(target, "es", selector)

        assertEquals(
            listOf(DidacticSegment::class, ConjDrillSegment::class, WrapUpSegment::class),
            segments.map { it::class },
        )
        assertEquals(listOf(LessonSegmentBuilder.MICRO_LESSON_ITEMS), selector.calls.map { it.second })
    }

    @Test
    fun `every sitting ends on its wrap-up`() = runTest {
        val selector = FakeSelector()
        val target = lesson("meaning", preteriteScope, didactic(), AuthoredSegment.EngineDrill(6))
        assertTrue(
            LessonSegmentBuilder.buildLesson(target, emptyList(), "es", selector).last() is WrapUpSegment,
        )
        assertTrue(
            LessonSegmentBuilder.buildMicroLesson(target, "es", selector).last() is WrapUpSegment,
        )
    }

    @Test
    fun `an authored bank plays as a cloze beat carrying the lesson's bucket`() = runTest {
        val selector = FakeSelector()
        val target = lesson(
            "core",
            porParaScope,
            didactic(),
            bank("por", "para", "por", "para"),
            AuthoredSegment.LlmSlot(LlmSlotKind.CLOZE, 8, "cloze please"),
        )
        val segments = LessonSegmentBuilder.buildLesson(target, emptyList(), "es", selector)

        assertEquals(
            listOf(DidacticSegment::class, ClozeSegment::class, WrapUpSegment::class),
            segments.map { it::class },
        )
        val cloze = segments.filterIsInstance<ClozeSegment>().single()
        assertEquals(4, cloze.items.size)
        assertEquals("por_para", cloze.category)
        assertFalse(cloze.warmUp)
        // The dropped slot must not reach for the lexicon: there is no paradigm to widen into, and
        // the bank above is already the floor this lesson promised.
        assertTrue("nothing should have been selected", selector.calls.isEmpty())
        assertTrue(LessonSegmentBuilder.isPlayable(segments))
    }

    @Test
    fun `a paradigm-free lesson warms up on earlier lessons' own items`() = runTest {
        val selector = FakeSelector()
        val prior = listOf(
            lesson("core", porParaScope, bank("por", "para", "por", "para", "por", "para")),
            lesson("motion", porParaScope, bank("para", "por", "para", "por", "para", "por")),
        )
        val segments = LessonSegmentBuilder.buildLesson(
            lesson = lesson("time", porParaScope, didactic(), bank("por", "para")),
            priorLessons = prior,
            language = "es",
            selector = selector,
            random = Random(3),
        )

        val warm = segments.first()
        assertTrue(warm is ClozeSegment && warm.warmUp)
        assertEquals(LessonSegmentBuilder.WARM_UP_ITEMS, (warm as ClozeSegment).items.size)
        // Every card came out of a bank some earlier lesson actually wrote.
        val taught = prior.flatMap { it.authoredItems }.map { it.sentence }.toSet()
        assertTrue(warm.items.all { it.sentence in taught })
        assertTrue("the lexicon has nothing to offer here", selector.calls.isEmpty())
    }

    @Test
    fun `a paradigm-free checkup sweeps the course's own banks`() = runTest {
        val course = CourseDefinition(
            id = "es_por_para",
            language = "es",
            title = "Por vs para",
            blurb = "blurb",
            lessons = List(4) { i ->
                lesson("l$i", porParaScope, bank(*Array(6) { if (it % 2 == 0) "por" else "para" }))
            },
        )
        val segments = LessonSegmentBuilder.buildCheckup(course, "es", FakeSelector(), Random(9))
        val cloze = segments.filterIsInstance<ClozeSegment>().single()
        assertEquals(LessonSegmentBuilder.CHECKUP_ITEMS, cloze.items.size)
        assertEquals("por_para", cloze.category)
        assertTrue(LessonSegmentBuilder.isPlayable(segments))
    }

    @Test
    fun `a paradigm-free micro-lesson replays teaching and its own items`() = runTest {
        val target = lesson(
            "cause",
            porParaScope,
            didactic(),
            bank(*Array(14) { if (it % 2 == 0) "por" else "para" }),
            AuthoredSegment.LlmSlot(LlmSlotKind.CLOZE, 8, "cloze please"),
        )
        val segments = LessonSegmentBuilder.buildMicroLesson(target, "es", FakeSelector(), Random(1))
        assertEquals(
            listOf(DidacticSegment::class, ClozeSegment::class, WrapUpSegment::class),
            segments.map { it::class },
        )
        assertEquals(
            LessonSegmentBuilder.MICRO_LESSON_ITEMS,
            segments.filterIsInstance<ClozeSegment>().single().items.size,
        )
    }

    @Test
    fun `the authored por-para course builds a playable lesson with no llm and no lexicon`() = runTest {
        val course = CourseCatalog.course("es", "es_por_para")!!
        val dead = FakeSelector().apply { empty = true }
        val segments = LessonSegmentBuilder.buildLesson(
            lesson = course.lessons[1],
            priorLessons = course.lessons.take(1),
            language = "es",
            selector = dead,
            random = Random(7),
        )
        assertTrue(LessonSegmentBuilder.isPlayable(segments))
        assertTrue(segments.any { it is DidacticSegment })
        assertTrue(segments.filterIsInstance<ClozeSegment>().any { it.warmUp })
        assertTrue(segments.none { it is ConjDrillSegment })
    }

    @Test
    fun `the authored spanish preterite course builds a playable first lesson`() = runTest {
        val course = CourseCatalog.course("es", "es_preterite")!!
        val segments = LessonSegmentBuilder.buildLesson(
            lesson = course.lessons.first(),
            priorLessons = emptyList(),
            language = "es",
            selector = FakeSelector(),
        )
        assertTrue(LessonSegmentBuilder.isPlayable(segments))
        assertTrue(segments.any { it is DidacticSegment })
    }
}
