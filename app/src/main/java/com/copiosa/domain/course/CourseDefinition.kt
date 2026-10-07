package com.copiosa.domain.course

/**
 * One authored course: an ordered run of lessons teaching a tense (or a contrast between tenses)
 * from zero, plus the flashcards graduation mints.
 *
 * [id] and every [LessonDefinition.id] are **frozen slugs** — they key `course_progress`,
 * `course_lesson_state` and `course_sessions` rows and the `course:<id>` flashcard `autoSource`.
 * Lesson ids are names, not ordinals, so a lesson can be inserted mid-course later without
 * re-keying stored state. Append or insert freely; never rename.
 */
data class CourseDefinition(
    val id: String,
    /** Bare language subtag ("es"), matched like `ConjugationSpecs.forTag` — region ignored. */
    val language: String,
    val title: String,
    val blurb: String,
    /**
     * Courses best taken first. **Soft**: the hub shows a notice, nothing locks — the learner may
     * know a prerequisite from elsewhere, and the courses must stay individually usable.
     */
    val prerequisites: List<String> = emptyList(),
    val lessons: List<LessonDefinition>,
    /**
     * The named stretches the lesson list is grouped under. Display only — no stored row keys off a
     * part — so they can be renamed or re-split whenever the course reads better a different way.
     * A course may declare none, and then its lessons render as one flat run.
     */
    val parts: List<CoursePart> = emptyList(),
    /** Authored seeds for the FSRS cards graduation mints, alongside the data-driven paradigm cards. */
    val graduationCards: List<GraduationCardSpec> = emptyList(),
) {
    fun lesson(lessonId: String): LessonDefinition? = lessons.firstOrNull { it.id == lessonId }

    /**
     * [parts] paired with the lessons each one covers, in course order. A part runs from the lesson
     * it names to the one before the next part starts, so the lesson list stays the single source of
     * order and a part can never claim a lesson twice or leave one out.
     *
     * Empty when the course declares no parts, or when a part names a lesson that isn't there —
     * the screen falls back to the flat list rather than dropping lessons on the floor.
     */
    fun sections(): List<CourseSection> {
        val starts = parts.map { part -> lessons.indexOfFirst { it.id == part.firstLessonId } }
        if (parts.isEmpty() || starts.any { it < 0 } || starts != starts.sorted()) return emptyList()
        return parts.mapIndexed { i, part ->
            val end = starts.getOrElse(i + 1) { lessons.size }
            CourseSection(part, lessons.subList(starts[i], end))
        }
    }

    /**
     * The lessons a sitting of [lessonId] may warm up on: those earlier in the course that appear in
     * [completed].
     *
     * Position alone would be wrong. Nothing in the hub locks, so a learner may open lesson five
     * first — and warming them up on the endings of lessons two to four, which they have never been
     * shown, turns retrieval practice into an ambush. A skipped lesson simply makes the warm-up
     * shorter.
     */
    fun lessonsTaughtBefore(lessonId: String, completed: Set<String>): List<LessonDefinition> =
        lessons.takeWhile { it.id != lessonId }.filter { it.id in completed }

    /** The part [lessonId] belongs to, or null when the course declares no parts. */
    fun partOf(lessonId: String): CoursePart? =
        sections().firstOrNull { section -> section.lessons.any { it.id == lessonId } }?.part

    /**
     * Everything taught *before* [lessonId] — what a learner opening this lesson already has, which
     * is what lets a paradigm table draw the forms they know differently from the ones this sitting
     * is about to add.
     *
     * Null means **nothing precedes it**, which is the opposite of what a null [LessonScope] member
     * means elsewhere. A caller reading this as "unrestricted" would show a first lesson every cell
     * in the paradigm as already known, so the two cases have to stay distinguishable.
     */
    fun scopeBefore(lessonId: String): LessonScope? {
        val at = lessons.indexOfFirst { it.id == lessonId }
        if (at <= 0) return null
        return LessonScope.union(lessons.take(at).map { it.scope })
    }

    /** Everything taught up to and including [lessonId] — the warm-up/checkup/routine-bias scope. */
    fun cumulativeScope(lessonId: String? = null): LessonScope? {
        val upTo = lessonId?.let { id -> lessons.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 } ?: lessons.lastIndex
        return LessonScope.union(lessons.take(upTo + 1).map { it.scope })
    }
}

/**
 * A named stretch of a course. [firstLessonId] is the lesson it opens on; where it ends is decided
 * by the next part, so parts cannot overlap or leave a gap. See [CourseDefinition.sections].
 */
data class CoursePart(
    val title: String,
    val firstLessonId: String,
)

/** One part with the lessons it covers — what the course screen draws under a heading. */
data class CourseSection(
    val part: CoursePart,
    val lessons: List<LessonDefinition>,
)

/** One lesson: a 10–15 minute sitting of teaching beats and practice beats. */
data class LessonDefinition(
    /** Frozen slug, unique within its course. */
    val id: String,
    val title: String,
    /**
     * One plain sentence saying what the sitting actually covers, shown under the title on the
     * course screen. The title names the lesson; this says what you will be able to do after it.
     */
    val tagline: String = "",
    val estimatedMinutes: Int,
    val scope: LessonScope,
    val segments: List<AuthoredSegment>,
) {
    /** Teaching beats, for the course screen's "N teaching steps" line. */
    val teachingSteps: Int get() = segments.count { it is AuthoredSegment.Didactic }

    /**
     * The lesson's hand-written practice, in authoring order. This is the offline floor of a
     * non-morphological lesson, and the pool its warm-ups, checkups and micro-lessons draw from.
     */
    val authoredItems: List<AuthoredChoiceItem>
        get() = segments.filterIsInstance<AuthoredSegment.AuthoredDrill>().flatMap { it.items }

    /** Every card the sitting means to ask, engine drills, authored banks and LLM slots alike. */
    val practiceItems: Int
        get() = segments.sumOf {
            when (it) {
                is AuthoredSegment.EngineDrill -> it.itemCount
                is AuthoredSegment.LlmSlot -> it.itemCount
                is AuthoredSegment.AuthoredDrill -> it.itemCount
                is AuthoredSegment.Didactic -> 0
            }
        }
}

/**
 * An authored graduation card. [clozeText] is the card front — a target-language sentence with an
 * Anki-style `{{c1::…}}` deletion, which `ReviewScreen` already renders for any card kind — and
 * [back] is the native-language gloss shown as the extra field on reveal.
 */
data class GraduationCardSpec(
    val clozeText: String,
    val back: String,
)

/** Where a learner stands with one course. Names are persisted in `course_progress.status` — frozen. */
enum class CourseStatus {
    ACTIVE,
    GRADUATED,
    ;

    companion object {
        fun fromName(name: String?): CourseStatus? = entries.firstOrNull { it.name == name }
    }
}
