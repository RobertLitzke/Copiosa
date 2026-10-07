package com.copiosa.domain.course

import com.copiosa.domain.conjugation.ConjugationDrillSelector
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.ConjugationSpecs
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.model.Cloze
import com.copiosa.domain.routine.ClozeItem
import com.copiosa.domain.routine.ContrastCategory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The completeness check for the authored course catalog, in the registry-test tradition: everything
 * a course *claims* — its ids, its scopes, the morphology inside its prose — is resolved against the
 * conjugation spec it will run on, so an authored typo fails the build instead of teaching a wrong
 * form. Covers every language's course list, not just Spanish, so a future catalog inherits the
 * rules for free.
 */
class CourseCatalogTest {

    private val languages = listOf("es", "pt")

    private fun courses(): List<Pair<CourseDefinition, ConjugationSpec>> =
        languages.flatMap { tag ->
            val spec = ConjugationSpecs.forTag(tag)
            CourseCatalog.forTag(tag).map { course ->
                assertNotNull("course ${course.id} needs a conjugation spec for '$tag'", spec)
                course to spec!!
            }
        }

    private val slug = Regex("[a-z][a-z0-9_]*")

    @Test
    fun `course and lesson ids are unique and slug-shaped`() {
        val allIds = languages.flatMap { CourseCatalog.forTag(it) }.map { it.id }
        assertEquals(allIds.toSet().size, allIds.size)
        for ((course, _) in courses()) {
            assertTrue("course id '${course.id}'", slug.matches(course.id))
            val lessonIds = course.lessons.map { it.id }
            assertEquals("duplicate lesson ids in ${course.id}", lessonIds.toSet().size, lessonIds.size)
            lessonIds.forEach { assertTrue("lesson id '$it'", slug.matches(it)) }
            assertTrue("course ${course.id} has no lessons", course.lessons.isNotEmpty())
        }
    }

    /**
     * Every lesson carries the one-line tagline the course screen shows under its title, and it is a
     * sentence rather than a restatement of the title. The length band is the real check: anything
     * much shorter is a fragment, anything much longer wraps past the two lines the row allows.
     */
    @Test
    fun `every lesson has a tagline that says something the title does not`() {
        for ((course, _) in courses()) {
            for (lesson in course.lessons) {
                val where = "${course.id}/${lesson.id}"
                assertTrue("$where has no tagline", lesson.tagline.isNotBlank())
                assertTrue(
                    "$where tagline is ${lesson.tagline.length} chars: '${lesson.tagline}'",
                    lesson.tagline.length in 40..125,
                )
                assertTrue(
                    "$where tagline just repeats the title",
                    !lesson.tagline.equals(lesson.title, ignoreCase = true),
                )
            }
        }
    }

    /**
     * Parts are display-only, but a part that names a lesson the course doesn't have — or that runs
     * backwards — silently collapses the whole grouping to nothing and the screen falls back to a
     * flat list. That is a failure the eye would not catch on a 17-lesson course, so it fails here.
     */
    @Test
    fun `parts cover every lesson exactly once, in course order`() {
        for ((course, _) in courses()) {
            val sections = course.sections()
            assertTrue("${course.id} declares no parts", course.parts.isNotEmpty())
            assertEquals("${course.id} lost a part", course.parts.size, sections.size)
            assertEquals(
                "${course.id} parts do not reassemble its lesson list",
                course.lessons.map { it.id },
                sections.flatMap { section -> section.lessons.map { it.id } },
            )
            sections.forEach { section ->
                assertTrue("${course.id} part '${section.part.title}' is empty", section.lessons.isNotEmpty())
                assertTrue("${course.id} part title", section.part.title.isNotBlank())
            }
        }
    }

    @Test
    fun `prerequisites name earlier courses of the same language`() {
        for (tag in languages) {
            val list = CourseCatalog.forTag(tag)
            list.forEachIndexed { index, course ->
                for (prereq in course.prerequisites) {
                    val at = list.indexOfFirst { it.id == prereq }
                    assertTrue("${course.id} prerequisite '$prereq' unknown", at >= 0)
                    assertTrue("${course.id} prerequisite '$prereq' must come earlier", at < index)
                }
            }
        }
    }

    @Test
    fun `lessons stay inside the sitting length the course promises`() {
        for ((course, _) in courses()) {
            for (lesson in course.lessons) {
                assertTrue(
                    "${course.id}/${lesson.id}: ${lesson.estimatedMinutes} min",
                    lesson.estimatedMinutes in 5..20,
                )
                assertTrue("${course.id}/${lesson.id} has no segments", lesson.segments.isNotEmpty())
            }
        }
    }

    @Test
    fun `every scope resolves against its language's spec`() {
        for ((course, spec) in courses()) {
            for (lesson in course.lessons) {
                val scopes = listOfNotNull(lesson.scope) +
                    lesson.segments.filterIsInstance<AuthoredSegment.EngineDrill>()
                        .mapNotNull { it.scopeOverride }
                for (scope in scopes) {
                    val where = "${course.id}/${lesson.id}"
                    // A scope must say what it covers in *some* currency. Tenses are the usual one;
                    // a lesson teaching a choice with no paradigm behind it names its contrast
                    // bucket instead, and then the bucket is the only thing identifying it.
                    assertTrue(
                        "$where names neither tenses nor a contrast",
                        scope.tenses.isNotEmpty() || scope.contrastCategory != null,
                    )
                    scope.tenses.forEach {
                        assertTrue("$where tense $it not in spec", it in spec.tenses)
                    }
                    assertTrue("$where has no directions", scope.directions.isNotEmpty())
                    scope.verbs?.forEach {
                        assertNotNull("$where verb '$it' not in lexicon", spec.lexicon.byInfinitive(it))
                    }
                    scope.irregularityClasses?.forEach {
                        assertTrue(
                            "$where class ${it.slug} not exhibited by ${spec.language}",
                            it in spec.irregularityClasses,
                        )
                    }
                    scope.contrastCategory?.let {
                        assertNotNull("$where contrast '$it' unknown", ContrastCategory.fromSlug(it))
                    }
                }
            }
        }
    }

    @Test
    fun `every authored example reproduces the engine's form`() {
        for ((course, spec) in courses()) {
            for (lesson in course.lessons) {
                for (example in lesson.authoredExamples()) {
                    val where = "${course.id}/${lesson.id}: '${example.sentence}'"
                    val verb = spec.lexicon.byInfinitive(example.infinitive)
                    assertNotNull("$where names unknown verb '${example.infinitive}'", verb)
                    val cell = example.cell ?: continue
                    val form = spec.engine.conjugate(verb!!, cell.tense, cell.person)
                    assertNotNull("$where cell $cell has no form", form)
                    // Case-insensitive: a sentence-initial form is legitimately capitalised.
                    assertTrue(
                        "$where must contain '${form!!.form}'",
                        example.sentence.contains(form.form, ignoreCase = true),
                    )
                }
            }
        }
    }

    @Test
    fun `table references name real verbs and paradigms`() {
        for ((course, spec) in courses()) {
            for (lesson in course.lessons) {
                for (table in lesson.blocks().filterIsInstance<DidacticBlock.TableRef>()) {
                    val where = "${course.id}/${lesson.id}"
                    assertTrue("$where table tense ${table.tense}", table.tense in spec.tenses)
                    assertTrue("$where table has no verbs", table.verbs.isNotEmpty())
                    table.verbs.forEach {
                        assertNotNull("$where table verb '$it'", spec.lexicon.byInfinitive(it))
                    }
                    table.persons?.forEach {
                        assertTrue("$where table person $it", it in spec.persons)
                    }
                }
            }
        }
    }

    /**
     * Every drill must be fillable **to its authored length**. A narrow scope — one irregularity
     * class, two persons — can only offer so many distinct cells, and asking for twenty out of a
     * pool of fifteen silently shortens the lesson instead of failing. This is the test that makes
     * [LessonScope.persons] safe to author against.
     */
    @Test
    fun `every engine drill scope can fill its authored item count`() {
        for ((course, spec) in courses()) {
            for (lesson in course.lessons) {
                for (segment in lesson.segments.filterIsInstance<AuthoredSegment.EngineDrill>()) {
                    val scope = segment.scopeOverride ?: lesson.scope
                    val items = ConjugationDrillSelector(spec, Random(7)).select(
                        config = scope.drillConfig(segment.itemCount, segment.irregularEmphasis),
                        scope = scope.verbFilter(),
                    )
                    assertEquals(
                        "${course.id}/${lesson.id} drill yields ${items.size} of ${segment.itemCount}",
                        segment.itemCount,
                        items.size,
                    )
                    scope.persons?.let { wanted ->
                        val stray = items.map { it.target.person }.filter { it !in wanted }
                        assertTrue("${course.id}/${lesson.id} drilled unscoped persons $stray", stray.isEmpty())
                    }
                }
            }
        }
    }

    /**
     * The pacing contract, in two halves.
     *
     * A lesson with most of the paradigm in play is a **practice** sitting and owes the learner a
     * real one: at least [MIN_PRACTICE_ITEMS] cards between its drills and its generated slots. A
     * lesson narrowed to a couple of persons, or one that only asks the learner to *read* forms, is
     * an **introduction** and may be shorter — that is the whole point of introducing a paradigm a
     * couple of endings at a time.
     *
     * Both halves matter. Without the floor the courses drift back toward six-card lessons; without
     * the exemption the first lesson of a tense cannot be gentle.
     */
    @Test
    fun `lessons carry enough practice for what they have in play`() {
        for ((course, _) in courses()) {
            for (lesson in course.lessons) {
                val items = lesson.segments.sumOf {
                    when (it) {
                        is AuthoredSegment.EngineDrill -> it.itemCount
                        is AuthoredSegment.LlmSlot -> it.itemCount
                        is AuthoredSegment.AuthoredDrill -> it.itemCount
                        else -> 0
                    }
                }
                val personsInPlay = lesson.scope.persons?.size ?: PARADIGM_PERSONS
                val introduction = personsInPlay <= 2 ||
                    lesson.scope.directions == setOf(DrillDirection.DECODE)
                val floor = if (introduction) MIN_INTRO_ITEMS else MIN_PRACTICE_ITEMS
                assertTrue(
                    "${course.id}/${lesson.id} has $items practice items for $personsInPlay persons",
                    items >= floor,
                )
            }
        }
    }

    /**
     * A lesson should not be a wall of prose followed by a wall of cards. Once a sitting is long
     * enough to need the structure, its teaching has to arrive in more than one beat with practice
     * in between — which is also the only way the reading stays under a screenful at a time.
     */
    @Test
    fun `long lessons interleave teaching with practice`() {
        for ((course, _) in courses()) {
            for (lesson in course.lessons) {
                val drills = lesson.segments.count {
                    it is AuthoredSegment.EngineDrill || it is AuthoredSegment.AuthoredDrill
                }
                if (lesson.estimatedMinutes < LONG_LESSON_MINUTES || drills < 2) continue
                val didactics = lesson.segments.count { it is AuthoredSegment.Didactic }
                assertTrue(
                    "${course.id}/${lesson.id} stacks $didactics teaching beats before $drills drills",
                    didactics >= 2,
                )
            }
        }
    }

    /**
     * A gradual course is only gradual if the warm-up is too: every card that opens a lesson must be
     * a cell some earlier lesson actually taught, in a direction it actually practised. This is the
     * test that would have caught asking for *comimos* three lessons before the -er endings land.
     */
    @Test
    fun `warm-ups only ask what earlier lessons taught`() = runBlocking {
        for ((course, spec) in courses()) {
            course.lessons.forEachIndexed { index, lesson ->
                val prior = course.lessons.take(index)
                if (prior.isEmpty() || !lesson.scope.isMorphological) return@forEachIndexed
                val items = LessonSegmentBuilder.warmUpItems(prior, selectorFor(spec))
                val where = "${course.id}/${lesson.id} warm-up"
                assertTrue("$where is empty", items.isNotEmpty())
                for (item in items) {
                    val taught = prior.any { earlier ->
                        val verb = spec.lexicon.byInfinitive(item.infinitive) ?: return@any false
                        item.target.tense in earlier.scope.tenses &&
                            item.direction in earlier.scope.directions &&
                            earlier.scope.persons?.contains(item.target.person) != false &&
                            earlier.scope.verbFilter()?.allows(verb, earlier.scope.tenses) != false
                    }
                    assertTrue(
                        "$where asks ${item.direction} '${item.infinitive}' ${item.target}, untaught",
                        taught,
                    )
                }
            }
        }
    }

    /**
     * The other half of the same promise. The warm-up may reach back only to lessons the learner has
     * *finished*: the hub locks nothing, so a learner who opens lesson five first must be warmed up
     * on nothing at all rather than on four lessons' worth of endings they have never seen.
     */
    @Test
    fun `warm-up sources are earlier lessons the learner actually finished`() {
        for ((course, _) in courses()) {
            val ids = course.lessons.map { it.id }
            if (ids.size < 3) continue
            val target = ids[2]

            assertTrue(
                "${course.id}: a jumped-to lesson must warm up on nothing",
                course.lessonsTaughtBefore(target, completed = emptySet()).isEmpty(),
            )
            assertEquals(
                "${course.id}: only the finished earlier lessons",
                listOf(ids[0]),
                course.lessonsTaughtBefore(target, completed = setOf(ids[0], target, ids.last()))
                    .map { it.id },
            )
        }
    }

    /**
     * The counterpart for a course with no paradigm behind it. Its warm-up is drawn from the
     * hand-written banks of the lessons before it, and the same promise applies: every item that
     * opens a sitting must be one some *single* earlier lesson actually wrote out. A pooled draw
     * would let lesson twelve's errand items open lesson three.
     */
    @Test
    fun `authored warm-ups only ask what earlier lessons wrote`() {
        for ((course, _) in courses()) {
            course.lessons.forEachIndexed { index, lesson ->
                if (lesson.scope.isMorphological) return@forEachIndexed
                val prior = course.lessons.take(index)
                val items = LessonSegmentBuilder.warmUpAuthored(prior, Random(13))
                val where = "${course.id}/${lesson.id} warm-up"
                if (prior.none { it.authoredItems.isNotEmpty() }) {
                    assertTrue("$where should be empty", items.isEmpty())
                    return@forEachIndexed
                }
                assertTrue("$where is empty", items.isNotEmpty())
                for (item in items) {
                    assertTrue(
                        "$where asks '${item.sentence}', which no earlier lesson wrote",
                        prior.any { earlier -> item in earlier.authoredItems },
                    )
                }
            }
        }
    }

    /**
     * The authored banks get the shape check the generated items get from
     * `CourseContentValidator`, applied at build time instead of at play time: one gap, a real set
     * of options, an answer among them, and a reason the learner can read on reveal.
     */
    @Test
    fun `authored practice items are well-formed`() {
        for ((course, _) in courses()) {
            for (lesson in course.lessons) {
                for ((at, item) in lesson.authoredItems.withIndex()) {
                    val where = "${course.id}/${lesson.id}[$at] '${item.sentence}'"
                    assertEquals(
                        "$where needs exactly one ${ClozeItem.BLANK}",
                        2,
                        item.sentence.split(ClozeItem.BLANK).size,
                    )
                    assertTrue("$where option count", item.options.size in 2..4)
                    assertTrue("$where blank option", item.options.none { it.isBlank() })
                    assertEquals(
                        "$where has duplicate options",
                        item.options.size,
                        item.options.distinctBy { it.trim().lowercase() }.size,
                    )
                    assertTrue("$where answerIndex", item.answerIndex in item.options.indices)
                    assertTrue("$where has no explanation", item.explanation.isNotBlank())
                    // The blank is filled with the option text verbatim, and options are lower
                    // case, so a sentence that opens on the blank renders without its capital.
                    // Reword the item rather than teaching the renderer about capitals.
                    assertFalse(
                        "$where opens on the blank, which would render uncapitalised",
                        item.sentence.trimStart().startsWith(ClozeItem.BLANK),
                    )
                    // An answer sitting unblanked elsewhere in the sentence gives itself away.
                    assertFalse(
                        "$where shows '${item.answer}' outside the blank",
                        Regex("\b${Regex.escape(item.answer)}\b")
                            .containsMatchIn(item.sentence.replace(ClozeItem.BLANK, " ")),
                    )
                }
            }
        }
    }

    /**
     * A bank measures a discrimination, so it must not be answerable by picking one side every
     * time — the rule `CourseContentValidator` enforces on generated contrast cloze, applied here to
     * the items that ship. A lesson leaning three-to-one on its own preposition is teaching a habit.
     */
    @Test
    fun `authored banks train a discrimination rather than a habit`() {
        for ((course, _) in courses()) {
            for (segment in course.lessons.flatMap { it.segments }
                .filterIsInstance<AuthoredSegment.AuthoredDrill>()
            ) {
                val answers = segment.items.map { it.answer.trim().lowercase() }
                val commonest = answers.groupingBy { it }.eachCount().values.max()
                assertTrue(
                    "${course.id}: a bank answers '${answers.first()}'-side $commonest of ${answers.size} times",
                    commonest <= kotlin.math.ceil(answers.size * MAX_ONE_SIDED).toInt(),
                )
            }
        }
    }

    /**
     * The authored analogue of `every engine drill scope can fill its authored item count`: a bank
     * that a warm-up, a checkup sweep or a micro-lesson draws from has to hold enough items to be
     * drawn from without repeating itself.
     */
    @Test
    fun `authored banks are deep enough for what samples them`() {
        for ((course, _) in courses()) {
            val lessons = course.lessons.filter { !it.scope.isMorphological }
            if (lessons.isEmpty()) continue
            for (lesson in lessons) {
                assertTrue(
                    "${course.id}/${lesson.id} has ${lesson.authoredItems.size} authored items",
                    lesson.authoredItems.size >= LessonSegmentBuilder.MICRO_LESSON_ITEMS,
                )
            }
            assertTrue(
                "${course.id} cannot fill a checkup from its banks",
                lessons.sumOf { it.authoredItems.size } >= LessonSegmentBuilder.CHECKUP_ITEMS,
            )
        }
    }

    /** A course with no paradigm still has to assemble a playable sitting with no LLM and no lexicon. */
    @Test
    fun `a course with no paradigm plays offline`() = runBlocking {
        val dead = DrillSelector { _, _, _ -> emptyList() }
        for ((course, _) in courses()) {
            if (course.lessons.any { it.scope.isMorphological }) continue
            course.lessons.forEachIndexed { index, lesson ->
                val segments = LessonSegmentBuilder.buildLesson(
                    lesson = lesson,
                    priorLessons = course.lessons.take(index),
                    language = course.language,
                    selector = dead,
                    random = Random(5),
                )
                assertTrue(
                    "${course.id}/${lesson.id} is not playable offline",
                    LessonSegmentBuilder.isPlayable(segments),
                )
                assertTrue(
                    "${course.id}/${lesson.id} lost its authored practice",
                    segments.filterIsInstance<ClozeSegment>()
                        .filterNot { it.warmUp }
                        .sumOf { it.items.size } == lesson.authoredItems.size,
                )
            }
            assertTrue(
                "${course.id} checkup is not playable",
                LessonSegmentBuilder.isPlayable(
                    LessonSegmentBuilder.buildCheckup(course, course.language, dead, Random(5)),
                ),
            )
        }
    }

    private fun selectorFor(spec: ConjugationSpec) = DrillSelector { scope, count, emphasis ->
        ConjugationDrillSelector(spec, Random(11)).select(
            config = scope.drillConfig(count, emphasis),
            scope = scope.verbFilter(),
        )
    }

    @Test
    fun `llm slots carry a usable brief and graduation cards carry cloze markup`() {
        for ((course, _) in courses()) {
            for (lesson in course.lessons) {
                for (slot in lesson.segments.filterIsInstance<AuthoredSegment.LlmSlot>()) {
                    assertTrue("${course.id}/${lesson.id} blank brief", slot.brief.isNotBlank())
                    assertTrue("${course.id}/${lesson.id} slot count", slot.itemCount > 0)
                }
            }
            for (card in course.graduationCards) {
                assertTrue(
                    "${course.id} graduation card lacks cloze: '${card.clozeText}'",
                    Cloze.hasCloze(card.clozeText),
                )
                assertTrue("${course.id} graduation card blank back", card.back.isNotBlank())
            }
        }
    }

    /**
     * The authored graduation cards get the same morphology check the lessons' examples get: every
     * cloze answer must be a form the engine agrees belongs to one of the course's tenses. A form it
     * has never seen is allowed (the lexicon carries 200 verbs, and a sentence may use another), but
     * a form it places in the *wrong* tense is an authoring bug in a card that will be reviewed for
     * years.
     */
    @Test
    fun `graduation card answers are not forms of the wrong tense`() {
        val clozeRe = Regex("""\{\{c\d+::(.*?)(?:::.*?)?\}\}""")
        for ((course, spec) in courses()) {
            val scope = course.cumulativeScope() ?: continue
            val forms = ScopeFormIndex.build(spec, scope)
            for (card in course.graduationCards) {
                for (match in clozeRe.findAll(card.clozeText)) {
                    val answer = match.groupValues[1]
                    if (answer.any { it.isWhitespace() }) continue
                    assertEquals(
                        "${course.id}: '$answer' in \"${card.clozeText}\"",
                        ScopeFormIndex.Verdict.WRONG_TENSE != forms.verdict(answer),
                        true,
                    )
                }
            }
        }
    }

    @Test
    fun `cumulative scope grows monotonically along the course`() {
        for ((course, _) in courses()) {
            var seen = emptySet<com.copiosa.domain.conjugation.Tense>()
            for (lesson in course.lessons) {
                val cumulative = course.cumulativeScope(lesson.id)
                assertNotNull(cumulative)
                assertTrue(cumulative!!.tenses.containsAll(seen))
                assertTrue(cumulative.tenses.containsAll(lesson.scope.tenses))
                seen = cumulative.tenses
            }
        }
    }

    private companion object {
        /** Rob's floor, stated on the device: a lesson drilling a whole paradigm is at least this. */
        const val MIN_PRACTICE_ITEMS = 20

        /** An introduction narrowed to one or two endings still has to be worth opening. */
        const val MIN_INTRO_ITEMS = 12

        const val LONG_LESSON_MINUTES = 12
        const val PARADIGM_PERSONS = 6

        /** The most a single bank may lean on one answer before it is teaching a habit. */
        const val MAX_ONE_SIDED = 0.7
    }

    private fun LessonDefinition.blocks(): List<DidacticBlock> =
        segments.filterIsInstance<AuthoredSegment.Didactic>().flatMap { it.blocks }

    private fun LessonDefinition.authoredExamples(): List<AuthoredExample> =
        blocks().filterIsInstance<DidacticBlock.Examples>().flatMap { it.items }
}
