package com.copiosa.data.course

import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.local.CourseDao
import com.copiosa.data.local.CourseLessonStateEntity
import com.copiosa.data.local.CourseProgressEntity
import com.copiosa.data.local.CourseSessionEntity
import com.copiosa.domain.course.CheckupAnswer
import com.copiosa.domain.course.CheckupBucketing
import com.copiosa.domain.course.CheckupScheduler
import com.copiosa.domain.course.CourseCatalog
import com.copiosa.domain.course.CourseDefinition
import com.copiosa.domain.course.CourseStatus
import com.copiosa.domain.course.LessonDefinition
import com.copiosa.domain.course.LessonScope
import com.copiosa.domain.course.SessionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One course as the hub and Home render it: the authored definition joined to whatever the learner
 * has done with it. Everything derived is derived here, so the screens stay declarative.
 */
data class CourseOverview(
    val definition: CourseDefinition,
    val progress: CourseProgressEntity?,
    val lessonStates: Map<String, CourseLessonStateEntity>,
    /** Lesson ids with a sitting opened and left part-way through. */
    val startedLessons: Set<String> = emptySet(),
) {
    val started: Boolean get() = progress != null
    val graduated: Boolean get() = progress?.status == CourseStatus.GRADUATED.name

    val completedLessons: Int
        get() = definition.lessons.count { lessonStates[it.id]?.completedAt != null }

    /** Lessons finished as a fraction of the course, for the progress bar and its percentage. */
    val progressFraction: Float
        get() = if (definition.lessons.isEmpty()) 0f
        else completedLessons.toFloat() / definition.lessons.size

    val percentComplete: Int get() = (progressFraction * 100).toInt()

    /** The first lesson not yet completed — the "continue" target. Null once all are done. */
    val nextLesson: LessonDefinition?
        get() = definition.lessons.firstOrNull { lessonStates[it.id]?.completedAt == null }

    /**
     * Where the learner stands with one lesson. Three states, not two: a sitting abandoned half way
     * is neither untouched nor done, and showing it as untouched is what makes the hub feel like it
     * forgot. Completion wins — a finished lesson re-opened is still finished.
     */
    fun lessonStanding(lessonId: String): LessonStanding = when {
        lessonStates[lessonId]?.completedAt != null -> LessonStanding.DONE
        lessonId in startedLessons -> LessonStanding.IN_PROGRESS
        else -> LessonStanding.NOT_STARTED
    }

    /** Lessons a failed checkup flagged for review. */
    val flaggedLessons: List<LessonDefinition>
        get() = definition.lessons.filter { lessonStates[it.id]?.needsReview == true }

    fun checkupDue(now: Long): Boolean =
        graduated && (progress?.nextCheckupAt?.let { it <= now } == true)

    /** Prerequisite courses (by id) the learner has not graduated. Soft — a notice, never a lock. */
    fun missingPrerequisites(all: List<CourseOverview>): List<CourseDefinition> =
        definition.prerequisites.mapNotNull { prereqId ->
            all.firstOrNull { it.definition.id == prereqId }
                ?.takeIf { !it.graduated }?.definition
        }
}

/** How far the learner has got with one lesson, as the hub's list draws it. */
enum class LessonStanding { NOT_STARTED, IN_PROGRESS, DONE }

/**
 * The course mode's durable store: enrolment and per-lesson standing, plus the session rows the
 * player steps through. The *content* of a session is built by [CourseSessionManager]; this class
 * only owns state.
 */
@Singleton
class CourseRepository @Inject constructor(
    private val dao: CourseDao,
    private val drillRepo: ConjugationDrillRepository,
    private val minter: GraduationCardMinter,
) {

    fun courses(lang: String): List<CourseDefinition> = CourseCatalog.forTag(lang)

    fun supports(lang: String): Boolean = CourseCatalog.forTag(lang).isNotEmpty()

    fun observeOverviews(lang: String): Flow<List<CourseOverview>> = combine(
        dao.observeProgress(lang),
        dao.observeLessonStates(lang),
        dao.observeUnfinishedLessonSessions(lang),
    ) { progress, lessonStates, openSittings ->
        val progressById = progress.associateBy { it.courseId }
        val statesByCourse = lessonStates.groupBy { it.courseId }
        val openByCourse = openSittings.groupBy { it.courseId }
        courses(lang).map { course ->
            CourseOverview(
                definition = course,
                progress = progressById[course.id],
                lessonStates = statesByCourse[course.id].orEmpty().associateBy { it.lessonId },
                startedLessons = openByCourse[course.id].orEmpty().mapNotNullTo(mutableSetOf()) { it.lessonId },
            )
        }
    }

    /** Marks a course started on first contact; a no-op after that. */
    suspend fun ensureStarted(courseId: String, lang: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            if (dao.progress(courseId, lang) == null) {
                dao.insertProgress(
                    CourseProgressEntity(courseId = courseId, targetLanguage = lang, startedAt = now),
                )
            }
        }

    /**
     * Records a finished lesson sitting. Returns true when this completion graduates the course —
     * the caller's cue to mint the retention flashcards and open the checkup ladder.
     */
    suspend fun recordLessonComplete(
        courseId: String,
        lessonId: String,
        lang: String,
        accuracy: Double?,
        now: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        val existing = dao.lessonState(courseId, lessonId, lang)
        val updated = (existing ?: CourseLessonStateEntity(courseId = courseId, lessonId = lessonId, targetLanguage = lang))
            .copy(
                completedAt = now,
                passes = (existing?.passes ?: 0) + 1,
                lastAccuracy = accuracy ?: existing?.lastAccuracy,
                needsReview = false,
            )
        if (existing == null) dao.insertLessonState(updated) else dao.updateLessonState(updated)

        val course = CourseCatalog.course(lang, courseId) ?: return@withContext false
        val states = dao.lessonStates(courseId, lang).associateBy { it.lessonId }
        val allDone = course.lessons.all { states[it.id]?.completedAt != null }
        val progress = dao.progress(courseId, lang) ?: return@withContext false
        if (!allDone || progress.status == CourseStatus.GRADUATED.name) return@withContext false

        // Graduating is where the course hands the material over: flashcards take it from here, and
        // the checkup ladder opens on its first rung.
        dao.updateProgress(
            progress.copy(
                status = CourseStatus.GRADUATED.name,
                graduatedAt = now,
                checkupStage = 0,
                nextCheckupAt = CheckupScheduler.firstCheckup(now),
            ),
        )
        minter.mint(course, lang, now)
        true
    }

    /**
     * Applies a finished checkup: the ladder advances or pulls back, and any lesson the results
     * implicate is flagged so the hub can offer its micro-lesson.
     */
    suspend fun recordCheckup(
        courseId: String,
        lang: String,
        answers: List<CheckupAnswer>,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        val progress = dao.progress(courseId, lang) ?: return@withContext
        val course = CourseCatalog.course(lang, courseId) ?: return@withContext
        val accuracy = if (answers.isEmpty()) 1.0 else answers.count { it.correct }.toDouble() / answers.size
        val outcome = CheckupScheduler.afterCheckup(progress.checkupStage, accuracy, now)
        dao.updateProgress(
            progress.copy(
                checkupStage = outcome.stage,
                nextCheckupAt = outcome.nextCheckupAt,
                lastCheckupAt = now,
            ),
        )

        val flagged = CheckupScheduler.lessonsToFlag(
            CheckupBucketing.accuracyByLesson(course, answers, drillRepo.specFor(lang)),
        )
        val states = dao.lessonStates(courseId, lang).associateBy { it.lessonId }
        for (lessonId in flagged) {
            val existing = states[lessonId]
            if (existing == null) {
                dao.insertLessonState(
                    CourseLessonStateEntity(
                        courseId = courseId,
                        lessonId = lessonId,
                        targetLanguage = lang,
                        needsReview = true,
                    ),
                )
            } else if (!existing.needsReview) {
                dao.updateLessonState(existing.copy(needsReview = true))
            }
        }
    }

    /** A retaken micro-lesson clears its checkup flag without counting as a fresh completion. */
    suspend fun clearNeedsReview(courseId: String, lessonId: String, lang: String) =
        withContext(Dispatchers.IO) {
            dao.lessonState(courseId, lessonId, lang)?.let {
                if (it.needsReview) dao.updateLessonState(it.copy(needsReview = false))
            }
        }

    /**
     * Everything the profile's ACTIVE courses have taught so far — the scope the routine engine's
     * conjugation drills are biased toward, so routines reinforce the course without running ahead
     * of it. Null when nothing is actively being taught.
     */
    suspend fun activeCourseScope(lang: String): LessonScope? = withContext(Dispatchers.IO) {
        val active = dao.progressWithStatus(lang, CourseStatus.ACTIVE.name)
        val scopes = active.mapNotNull { progress ->
            val course = CourseCatalog.course(lang, progress.courseId) ?: return@mapNotNull null
            val states = dao.lessonStates(progress.courseId, lang).associateBy { it.lessonId }
            val completed = course.lessons.filter { states[it.id]?.completedAt != null }
            LessonScope.union(completed.map { it.scope })
        }
        LessonScope.union(scopes)
    }

    // --- sessions (state only; content is the manager's) ---

    suspend fun session(id: Long): CourseSessionEntity? =
        withContext(Dispatchers.IO) { dao.session(id) }

    fun observeSession(id: Long): Flow<CourseSessionEntity?> = dao.observeSession(id)

    suspend fun setCurrentIndex(id: Long, index: Int) =
        withContext(Dispatchers.IO) { dao.setCurrentIndex(id, index) }

    /**
     * Finishes a sitting and applies what it means: a LESSON completion updates the lesson's
     * standing (and reports graduation), a MICRO_LESSON clears its review flag. Checkup outcomes
     * are richer and handled by their own path when the checkup phase lands.
     */
    suspend fun completeSession(
        session: CourseSessionEntity,
        accuracy: Double?,
        answers: List<CheckupAnswer> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): Boolean = withContext(Dispatchers.IO) {
        dao.markCompleted(session.id, now)
        when (SessionKind.fromName(session.kind)) {
            SessionKind.LESSON -> session.lessonId?.let {
                recordLessonComplete(session.courseId, it, session.targetLanguage, accuracy, now)
            } ?: false

            SessionKind.MICRO_LESSON -> {
                session.lessonId?.let { clearNeedsReview(session.courseId, it, session.targetLanguage) }
                false
            }

            SessionKind.CHECKUP -> {
                recordCheckup(session.courseId, session.targetLanguage, answers, now)
                false
            }

            else -> false
        }
    }
}
