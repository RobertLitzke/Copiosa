package com.copiosa.domain.course

import com.copiosa.domain.conjugation.ConjugationDrillConfig
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.conjugation.DrillVerbFilter
import com.copiosa.domain.conjugation.IrregularityClass
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.conjugation.VerbClass

/**
 * What one lesson covers, expressed entirely in the conjugation engine's own coordinates so a lesson
 * can be turned into drill items, validated against a [com.copiosa.domain.conjugation.ConjugationSpec],
 * and intersected with a checkup result without any translation layer.
 *
 * `CourseCatalogTest` asserts every scope resolves against its course's spec — tenses the language
 * drills, verbs its lexicon carries, irregularity classes it actually exhibits.
 */
data class LessonScope(
    /**
     * The tenses in play, and the switch that decides whether this lesson is a morphological one at
     * all. **Empty means it is not**: nothing may be drawn from the conjugation lexicon for it, its
     * practice is authored beside its prose ([AuthoredSegment.AuthoredDrill]), and its warm-ups,
     * checkups and micro-lessons sample those banks instead of running the drill selector. A
     * preposition choice has no paradigm to narrow, so the honest scope for one names no tenses
     * rather than borrowing a tense it does not teach.
     */
    val tenses: Set<Tense> = emptySet(),
    val directions: Set<DrillDirection> = setOf(DrillDirection.PRODUCE, DrillDirection.DECODE),
    /** Only these infinitives (the suppletive lesson names ser/ir/dar/ver outright). */
    val verbs: Set<String>? = null,
    val verbClasses: Set<VerbClass>? = null,
    val irregularityClasses: Set<IrregularityClass>? = null,
    /** Only verbs regular across this scope's tenses — the "regular -ar" lesson's constraint. */
    val regularOnly: Boolean = false,
    /**
     * Only these persons — the knob that makes a course gradual. A paradigm has six endings and a
     * learner meeting a tense for the first time can hold two; a lesson narrows to the two it just
     * taught, and the consolidation lessons that follow leave this null to put the whole paradigm
     * back in play. Never narrows the *teaching*, only what a drill may ask.
     */
    val persons: Set<Person>? = null,
    /**
     * The proficiency bucket the lesson's cloze/PI answers land in, when the lesson drills a
     * discrimination rather than a form — the contrast course sets the existing
     * `ContrastCategory.PRETERITE_IMPERFECT` slug here so course practice and the routine's
     * contrast-cloze activity train the same mastery estimate. A course with no [tenses] at all
     * carries one here instead, and it is then the only thing saying what the course is about.
     */
    val contrastCategory: String? = null,
) {
    /**
     * Whether anything can be generated for this scope from the conjugation lexicon. False for a
     * lesson that teaches a choice with no paradigm behind it — see [tenses].
     */
    val isMorphological: Boolean get() = tenses.isNotEmpty()

    /** The verb narrowing for the drill selector, or null when this scope doesn't narrow verbs. */
    fun verbFilter(): DrillVerbFilter? =
        DrillVerbFilter(verbs, verbClasses, irregularityClasses, regularOnly, persons)
            .takeUnless { it.isEmpty }

    /** A drill config asking for exactly this scope's cells. */
    fun drillConfig(itemCount: Int, irregularEmphasis: Double): ConjugationDrillConfig =
        ConjugationDrillConfig(
            itemCount = itemCount,
            tenses = tenses,
            directions = directions,
            irregularEmphasis = irregularEmphasis,
        )

    companion object {
        /**
         * The territory [scopes] cover between them — the scope for warm-ups, checkups and the
         * routine bias.
         *
         * Every narrowing is **all-or-nothing**, because a null member means "unrestricted" and
         * unrestricted ∪ restricted is unrestricted: a narrowing survives only where *every* scope
         * in the list carries one, and then the two sets merge. That is what keeps a course honest
         * about what it has taught — after four lessons of regular -ar and -er verbs the union is
         * still "regular verbs only", so nothing built on it can ambush the learner with *dijeron*;
         * once the irregular lessons land it opens up on its own.
         */
        fun union(scopes: List<LessonScope>): LessonScope? {
            if (scopes.isEmpty()) return null
            fun <T> merge(select: (LessonScope) -> Set<T>?): Set<T>? =
                if (scopes.all { select(it) != null }) {
                    scopes.flatMapTo(mutableSetOf()) { select(it).orEmpty() }
                } else {
                    null
                }
            return LessonScope(
                tenses = scopes.flatMapTo(mutableSetOf()) { it.tenses },
                directions = scopes.flatMapTo(mutableSetOf()) { it.directions },
                verbs = merge { it.verbs },
                verbClasses = merge { it.verbClasses },
                irregularityClasses = merge { it.irregularityClasses },
                regularOnly = scopes.all { it.regularOnly },
                persons = merge { it.persons },
                // Not a narrowing but a label, so it merges by agreement rather than by union: a
                // course whose lessons all train por/para stays a por/para course to the checkup
                // and the routine bias, while a union spanning two different buckets names neither.
                contrastCategory = scopes.map { it.contrastCategory }.distinct().singleOrNull(),
            )
        }
    }
}
