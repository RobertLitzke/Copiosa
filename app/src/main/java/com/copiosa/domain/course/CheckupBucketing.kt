package com.copiosa.domain.course

import com.copiosa.domain.conjugation.Cell
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.conjugation.DrillItem

/** One graded checkup answer, reduced to what lesson attribution needs. */
data class CheckupAnswer(
    val infinitive: String,
    val cell: Cell,
    val correct: Boolean,
    /**
     * How the cell was asked. A lesson that only ever taught the learner to *read* a form cannot be
     * held responsible for failing to *produce* it, so attribution matches on direction as well as
     * on coordinates.
     */
    val direction: DrillDirection = DrillDirection.PRODUCE,
)

/**
 * Attributes checkup answers back to the lessons that taught them, so a failure can reopen the
 * right micro-lesson rather than the whole course.
 *
 * An answer counts toward every lesson whose scope contains it, which is deliberate: the strong
 * preterites are taught in one lesson and exercised again by the contrast course, and a miss is
 * evidence about both.
 */
object CheckupBucketing {

    fun accuracyByLesson(
        course: CourseDefinition,
        answers: List<CheckupAnswer>,
        spec: ConjugationSpec?,
    ): Map<String, Double> {
        if (answers.isEmpty()) return emptyMap()
        val tallies = mutableMapOf<String, Pair<Int, Int>>() // lessonId -> (right, total)
        for (lesson in course.lessons) {
            for (answer in answers) {
                if (!lesson.scope.covers(answer, spec)) continue
                val (right, total) = tallies[lesson.id] ?: (0 to 0)
                tallies[lesson.id] = (right + if (answer.correct) 1 else 0) to (total + 1)
            }
        }
        return tallies
            .filterValues { it.second >= MIN_EVIDENCE }
            .mapValues { (_, tally) -> tally.first.toDouble() / tally.second }
    }

    /** Whether an answer falls inside this scope's coordinates. */
    private fun LessonScope.covers(answer: CheckupAnswer, spec: ConjugationSpec?): Boolean {
        if (answer.cell.tense !in tenses) return false
        if (answer.direction !in directions) return false
        if (persons?.contains(answer.cell.person) == false) return false
        val verb = spec?.lexicon?.byInfinitive(answer.infinitive)
        // Without paradigm data only the tense can be checked — which is still the coarse half.
        if (verb == null) return verbs == null && verbClasses == null && irregularityClasses == null
        return verbFilter()?.allows(verb, tenses) ?: true
    }

    /** Too few items to accuse a lesson with. */
    private const val MIN_EVIDENCE = 2
}
