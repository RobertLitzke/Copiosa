package com.copiosa.domain.course

import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.routine.ClozeItem
import com.copiosa.domain.routine.PiItem

/**
 * Every form the bundled lexicon can spell, split by whether it belongs to the lesson's tenses.
 * Built once per lesson (it is ~10k conjugations, the same work the golden test does) and handed to
 * each slot's validation.
 */
class ScopeFormIndex(
    private val inScope: Set<String>,
    private val otherTenses: Set<String>,
) {
    /**
     * The verdict on one candidate answer. Deliberately three-valued: the lexicon holds 200 verbs,
     * so "the engine has never heard of this word" is common and innocent — a generated sentence may
     * legitimately use a verb the drill doesn't carry. Only a form the engine positively recognises
     * as belonging to a *different* paradigm is a real error, and that is the one this catches: an
     * imperfect handed to a preterite lesson.
     */
    fun verdict(form: String): Verdict {
        val needle = form.trim().lowercase()
        return when {
            needle in inScope -> Verdict.IN_SCOPE
            needle in otherTenses -> Verdict.WRONG_TENSE
            else -> Verdict.UNKNOWN
        }
    }

    enum class Verdict { IN_SCOPE, WRONG_TENSE, UNKNOWN }

    companion object {
        val EMPTY = ScopeFormIndex(emptySet(), emptySet())

        fun build(spec: ConjugationSpec?, scope: LessonScope): ScopeFormIndex {
            if (spec == null) return EMPTY
            // A lesson with no tenses is not teaching a paradigm, so there is no in-scope side to
            // sort forms into and nothing here has an opinion to offer. Without this guard every
            // conjugation in the lexicon would land under "other tenses" and any generated sentence
            // whose answer happens to be a verb would be thrown away as belonging to the wrong one.
            if (!scope.isMorphological) return EMPTY
            val inScope = mutableSetOf<String>()
            val other = mutableSetOf<String>()
            for (verb in spec.lexicon.verbs) {
                for (tense in spec.tenses) {
                    val target = if (tense in scope.tenses) inScope else other
                    for (person in spec.persons) {
                        spec.engine.conjugate(verb, tense, person)?.let { target += it.form.lowercase() }
                    }
                }
            }
            // A form that is both (vivimos is preterite *and* present) counts as in scope.
            other -= inScope
            return ScopeFormIndex(inScope, other)
        }
    }
}

/**
 * Turns parsed lesson items into playable segments, dropping anything that fails inspection.
 *
 * A grammar course gets a check the routine's validator can't have: the engine itself adjudicates
 * whether a generated answer is a form of the paradigm the lesson is teaching. Content nobody
 * reviewed therefore cannot contradict what the same session grades.
 *
 * Items are requested over-strength (see [OVER_REQUEST]) and validated down to the authored count.
 */
object CourseContentValidator {

    /** Ask for half again as many items as needed, so rejects don't shorten the lesson. */
    const val OVER_REQUEST = 1.5

    fun requestCount(itemCount: Int): Int = kotlin.math.ceil(itemCount * OVER_REQUEST).toInt()

    /** Validates one slot's items into a segment, or null when too few survive to be worth showing. */
    fun validate(
        slot: AuthoredSegment.LlmSlot,
        lessonScope: LessonScope,
        items: List<CourseContentParser.ItemDto>,
        forms: ScopeFormIndex,
    ): LessonSegment? {
        val kept = items.filter { isUsable(it, slot.kind, forms) }.take(slot.itemCount)
        if (kept.size < MIN_ITEMS) return null

        // A contrast drill measures discrimination, so it must not be answerable by always picking
        // one side — the rule the routine's contrast cloze follows, enforced here after the fact.
        if (slot.kind == LlmSlotKind.CLOZE && lessonScope.contrastCategory != null && kept.size >= 3 &&
            kept.all { it.answerIndex == kept.first().answerIndex }
        ) {
            return null
        }

        return when (slot.kind) {
            LlmSlotKind.CLOZE -> ClozeSegment(
                items = kept.map { ClozeItem(it.sentence.trim(), it.options, it.answerIndex, it.explanation) },
                category = lessonScope.contrastCategory,
            )

            LlmSlotKind.PI -> PiSegment(
                items = kept.map { PiItem(it.sentence.trim(), it.options, it.answerIndex, it.explanation) },
                category = lessonScope.contrastCategory,
            )
        }
    }

    private fun isUsable(
        item: CourseContentParser.ItemDto,
        kind: LlmSlotKind,
        forms: ScopeFormIndex,
    ): Boolean {
        if (item.sentence.isBlank()) return false
        if (item.options.size !in MIN_OPTIONS..MAX_OPTIONS) return false
        if (item.answerIndex !in item.options.indices) return false
        if (item.options.any { it.isBlank() }) return false
        if (item.options.distinctBy { it.trim().lowercase() }.size != item.options.size) return false
        // A cloze must actually have a gap to fill.
        if (kind == LlmSlotKind.CLOZE && !item.sentence.contains(ClozeItem.BLANK)) return false

        val answer = item.options[item.answerIndex]
        if (answer.none { it.isWhitespace() }) {
            return forms.verdict(answer) != ScopeFormIndex.Verdict.WRONG_TENSE
        }
        return true
    }

    private const val MIN_ITEMS = 2
    private const val MIN_OPTIONS = 2
    private const val MAX_OPTIONS = 4
}
