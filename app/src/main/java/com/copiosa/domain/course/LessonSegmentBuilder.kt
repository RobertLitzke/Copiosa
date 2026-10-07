package com.copiosa.domain.course

import com.copiosa.domain.conjugation.DrillItem
import com.copiosa.domain.conjugation.DrillItemCodec
import com.copiosa.domain.routine.ClozeItem
import kotlin.random.Random

/**
 * Assembles a sitting's segments from the authored catalog. Pure but for the [DrillSelector] handed
 * in — which is the whole point: the ordering rules, the warm-up, and the degradation behaviour when
 * an LLM slot can't be filled are all testable without a database (`CourseSessionBuilderTest`),
 * exactly as `RoutinePlanner` is testable apart from `RoutineSynthesisManager`.
 *
 * Two kinds of course run through here. A **morphological** one draws its practice from the
 * conjugation lexicon through the selector, and everything below reads as it always has. A course
 * whose scopes name no tenses (see [LessonScope.tenses]) has no lexicon to draw from, so its
 * warm-ups, checkups and micro-lessons sample the hand-written banks its lessons carry instead. The
 * two paths meet at the same [LessonSegment] types, so nothing downstream needs to know which it is.
 */
fun interface DrillSelector {
    suspend fun select(scope: LessonScope, itemCount: Int, irregularEmphasis: Double): List<DrillItem>
}

/**
 * Items an LLM slot would have supplied, keyed by the slot's index within the lesson's authored
 * segments. The offline build passes none; the LLM phase fills what it validated and leaves the rest
 * for the fallback widener.
 */
typealias LlmSegments = Map<Int, LessonSegment>

object LessonSegmentBuilder {

    const val WARM_UP_ITEMS = 5

    /**
     * How far back a warm-up reaches. Retrieval practice wants what is *fading*, not everything ever
     * covered: three lessons back is a day or two of study, which is where forgetting actually bites,
     * and it keeps the warm-up recognisably related to the sitting it opens. Checkups are the thing
     * that sweeps the whole course.
     */
    const val WARM_UP_LESSON_WINDOW = 3

    /**
     * Leans hard regular: a warm-up should re-land what was taught, not ambush. Anything above ~0.3
     * actively *favours* irregular cells (see `ConjugationDrillSelector.IRREGULAR_BOOST`), which is
     * right for a drill the learner chose and wrong for the first thirty seconds of a lesson.
     */
    const val WARM_UP_EMPHASIS = 0.15
    const val FALLBACK_EMPHASIS = 0.6

    /**
     * The widest a dropped LLM slot may make an engine drill. Generous enough that an offline sitting
     * stays close to the length the lesson promises, capped so a lesson leaning on two slots can't
     * turn into nothing but conjugation cards.
     */
    const val FALLBACK_DRILL_MAX = 12
    const val MICRO_LESSON_ITEMS = 8

    /** Three to five minutes: long enough to be evidence, short enough to actually get done. */
    const val CHECKUP_ITEMS = 12
    const val CHECKUP_EMPHASIS = 0.6

    /**
     * The opening warm-up: a few cards drawn from the lessons just before this one, most recent
     * first. [priorLessons] is what the learner has actually **been taught** — the caller filters
     * out lessons that were skipped rather than completed, because the hub locks nothing and a
     * learner who jumps ahead must not be "reminded" of endings they have never met.
     *
     * Each recent lesson is selected from **separately, through its own scope**, rather than through
     * one merged scope. That is the difference between a warm-up and an ambush. Merging loses the
     * correlations that make a gradual course gradual — "-ar verbs, every person" and "-er verbs, yo
     * and tú" blend into "-ar and -er verbs, every person", which is a licence to ask for *comimos*
     * three lessons before it is taught. Sampling each scope in turn can only ever produce cells some
     * lesson actually covered.
     */
    suspend fun warmUpItems(
        priorLessons: List<LessonDefinition>,
        selector: DrillSelector,
    ): List<DrillItem> {
        val items = mutableListOf<DrillItem>()
        val seen = mutableSetOf<String>()
        for ((lesson, want) in shares(priorLessons.filter { it.scope.isMorphological })) {
            for (item in selector.select(lesson.scope, want, WARM_UP_EMPHASIS)) {
                if (seen.add(item.itemKey)) items += item
            }
            if (items.size >= WARM_UP_ITEMS) break
        }
        return items.take(WARM_UP_ITEMS)
    }

    /**
     * The same warm-up for a course with no paradigm behind it: items the earlier lessons wrote out
     * themselves, freshest lesson first. Sampled per lesson rather than from one pooled bank, for
     * the reason [warmUpItems] gives — a warm-up may only ask what some single earlier sitting
     * actually taught.
     */
    fun warmUpAuthored(
        priorLessons: List<LessonDefinition>,
        random: Random,
    ): List<AuthoredChoiceItem> {
        val items = mutableListOf<AuthoredChoiceItem>()
        val seen = mutableSetOf<String>()
        for ((lesson, want) in shares(priorLessons.filter { !it.scope.isMorphological })) {
            for (item in lesson.authoredItems.shuffled(random).take(want)) {
                if (seen.add(item.sentence)) items += item
            }
            if (items.size >= WARM_UP_ITEMS) break
        }
        return items.take(WARM_UP_ITEMS)
    }

    /**
     * The [WARM_UP_LESSON_WINDOW] most recent lessons, newest first, each paired with its even share
     * of [WARM_UP_ITEMS] rounded up — so the freshest lesson gets the odd card. Shares are computed
     * against the whole window rather than against what has been collected so far, which keeps this
     * a pure function of the lesson list; the callers stop early once they are full.
     */
    private fun shares(priorLessons: List<LessonDefinition>): List<Pair<LessonDefinition, Int>> {
        val recent = priorLessons.takeLast(WARM_UP_LESSON_WINDOW).asReversed()
        var scopesLeft = recent.size
        var allotted = 0
        return recent.map { lesson ->
            val want = (WARM_UP_ITEMS - allotted + scopesLeft - 1) / scopesLeft
            scopesLeft--
            allotted += want
            lesson to want
        }
    }

    /**
     * A full lesson: warm-up retrieval from [priorLessons], then the authored beats in order, with
     * unfilled [llmSegments] slots surrendering their practice time to one extra scoped drill, and a
     * wrap-up last. A sitting always ends up playable — that is what makes the course usable offline.
     */
    suspend fun buildLesson(
        lesson: LessonDefinition,
        /** Earlier lessons the learner has completed — see [warmUpItems]. */
        priorLessons: List<LessonDefinition>,
        language: String,
        selector: DrillSelector,
        llmSegments: LlmSegments = emptyMap(),
        random: Random = Random.Default,
    ): List<LessonSegment> = buildList {
        warmUp(lesson, priorLessons, language, selector, random)?.let { add(it) }

        var unfilledItems = 0
        lesson.segments.forEachIndexed { index, segment ->
            when (segment) {
                is AuthoredSegment.Didactic -> add(segment.materialize())

                is AuthoredSegment.EngineDrill -> {
                    val scope = segment.scopeOverride ?: lesson.scope
                    val items = selector.select(scope, segment.itemCount, segment.irregularEmphasis)
                    if (items.isNotEmpty()) add(drillSegment(items, language))
                }

                is AuthoredSegment.AuthoredDrill -> add(clozeSegment(segment.items, lesson.scope))

                is AuthoredSegment.LlmSlot -> {
                    val filled = llmSegments[index]
                    if (filled != null) add(filled) else unfilledItems += segment.itemCount
                }
            }
        }

        // A dropped slot buys back its practice time as one wider engine drill. There is nothing to
        // widen for a lesson with no paradigm behind it, and nothing that needs widening either: its
        // authored bank already played in full above, and that bank *is* the lesson's floor.
        if (unfilledItems > 0 && lesson.scope.isMorphological) {
            val items = selector.select(
                lesson.scope,
                unfilledItems.coerceAtMost(FALLBACK_DRILL_MAX),
                FALLBACK_EMPHASIS,
            )
            if (items.isNotEmpty()) add(drillSegment(items, language))
        }

        add(WrapUpSegment())
    }

    /** The retrieval opener, in whichever currency the lessons behind it were taught. */
    private suspend fun warmUp(
        lesson: LessonDefinition,
        priorLessons: List<LessonDefinition>,
        language: String,
        selector: DrillSelector,
        random: Random,
    ): LessonSegment? =
        if (lesson.scope.isMorphological) {
            warmUpItems(priorLessons, selector)
                .takeIf { it.isNotEmpty() }
                ?.let { drillSegment(it, language, warmUp = true) }
        } else {
            warmUpAuthored(priorLessons, random)
                .takeIf { it.isNotEmpty() }
                ?.let { clozeSegment(it, lesson.scope, warmUp = true) }
        }

    /**
     * The retention check: one drill across everything the course taught, no teaching and no LLM.
     * The selector's own mastery-and-recency weighting is what aims it at the weakest cells, so
     * there is nothing further to choose here — a checkup is a measurement, not a lesson.
     *
     * A course with no paradigm has no mastery-weighted selector to aim it, so it sweeps its own
     * banks instead: an even share from each lesson, which is the same "across everything the course
     * taught" promise reached the only way available to it.
     */
    suspend fun buildCheckup(
        course: CourseDefinition,
        language: String,
        selector: DrillSelector,
        random: Random = Random.Default,
    ): List<LessonSegment> = buildList {
        val scope = course.cumulativeScope() ?: return@buildList
        if (scope.isMorphological) {
            val items = selector.select(scope, CHECKUP_ITEMS, CHECKUP_EMPHASIS)
            if (items.isNotEmpty()) add(drillSegment(items, language))
        } else {
            val items = sweep(course.lessons, CHECKUP_ITEMS, random)
            if (items.isNotEmpty()) add(clozeSegment(items, scope))
        }
        add(WrapUpSegment())
    }

    /** The remedial sitting: the lesson's teaching beats replayed, one short scoped drill, no LLM. */
    suspend fun buildMicroLesson(
        lesson: LessonDefinition,
        language: String,
        selector: DrillSelector,
        random: Random = Random.Default,
    ): List<LessonSegment> = buildList {
        lesson.segments.filterIsInstance<AuthoredSegment.Didactic>().forEach { add(it.materialize()) }
        if (lesson.scope.isMorphological) {
            val items = selector.select(lesson.scope, MICRO_LESSON_ITEMS, FALLBACK_EMPHASIS)
            if (items.isNotEmpty()) add(drillSegment(items, language))
        } else {
            val items = lesson.authoredItems.shuffled(random).take(MICRO_LESSON_ITEMS)
            if (items.isNotEmpty()) add(clozeSegment(items, lesson.scope))
        }
        add(WrapUpSegment())
    }

    /**
     * [want] items spread evenly across [lessons]' banks, so a checkup samples the whole course
     * rather than whichever lesson happened to write the most items. Lesson order is kept, so the
     * sweep reads as a walk through the course.
     */
    private fun sweep(
        lessons: List<LessonDefinition>,
        want: Int,
        random: Random,
    ): List<AuthoredChoiceItem> {
        val banks = lessons.map { it.authoredItems }.filter { it.isNotEmpty() }
        if (banks.isEmpty()) return emptyList()
        val items = mutableListOf<AuthoredChoiceItem>()
        var left = banks.size
        for (bank in banks) {
            val remaining = want - items.size
            if (remaining <= 0) break
            val share = (remaining + left - 1) / left
            left--
            items += bank.shuffled(random).take(share)
        }
        return items
    }

    /** True when a built sitting has something to do beyond its wrap-up screen. */
    fun isPlayable(segments: List<LessonSegment>): Boolean = segments.any { it !is WrapUpSegment }

    private fun drillSegment(items: List<DrillItem>, language: String, warmUp: Boolean = false) =
        ConjDrillSegment(items.map(DrillItemCodec::encode), language, warmUp)

    private fun clozeSegment(
        items: List<AuthoredChoiceItem>,
        scope: LessonScope,
        warmUp: Boolean = false,
    ) = ClozeSegment(
        items = items.map { ClozeItem(it.sentence, it.options, it.answerIndex, it.explanation) },
        category = scope.contrastCategory,
        warmUp = warmUp,
    )
}
