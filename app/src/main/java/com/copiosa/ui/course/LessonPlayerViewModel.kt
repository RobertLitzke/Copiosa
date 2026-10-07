package com.copiosa.ui.course

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.course.CourseRepository
import com.copiosa.data.course.CourseSessionManager
import com.copiosa.data.local.CourseSessionEntity
import com.copiosa.data.routine.RoutineAdaptation
import com.copiosa.domain.conjugation.ConjugatedForm
import com.copiosa.domain.conjugation.ConjugationSpec
import com.copiosa.domain.conjugation.DrillGrade
import com.copiosa.domain.conjugation.DrillItem
import com.copiosa.domain.course.CheckupAnswer
import com.copiosa.domain.course.CourseCatalog
import com.copiosa.domain.course.CourseDefinition
import com.copiosa.domain.course.CourseSessionStatus
import com.copiosa.domain.course.LessonPlan
import com.copiosa.domain.course.LessonPlanCodec
import com.copiosa.domain.course.WrapUpSegment
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the lesson player renders: one of preparing / failed / playing / finished. */
data class LessonPlayerUi(
    val loading: Boolean = true,
    /** The session row exists but its plan is still being built. */
    val preparing: Boolean = false,
    val failed: Boolean = false,
    val plan: LessonPlan? = null,
    val spec: ConjugationSpec? = null,
    val index: Int = 0,
    val courseTitle: String = "",
    val lessonTitle: String = "",
    /**
     * The authored course this sitting came from, so the teaching screens can ask it what a learner
     * arriving here already knows — a paradigm table draws taught, being-taught and not-yet-taught
     * cells differently, and only the catalog can say which is which.
     */
    val course: CourseDefinition? = null,
    /** Where this lesson sits in its course, 1-based. Zero for a checkup, which spans the course. */
    val lessonNumber: Int = 0,
    val lessonCount: Int = 0,
    /** The course part this lesson belongs to; blank when the course declares no parts. */
    val partTitle: String = "",
    val finished: Boolean = false,
    /** Graded answers this sitting, warm-up excluded — the lesson's own accuracy. */
    val correct: Int = 0,
    val total: Int = 0,
    /** Set when finishing this sitting graduated the whole course. */
    val graduated: Boolean = false,
    /** The LLM's diagnosis of this sitting's mistakes; null when unavailable or nothing was missed. */
    val feedback: String? = null,
    val feedbackLoading: Boolean = false,
)

@HiltViewModel
class LessonPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val courseRepo: CourseRepository,
    private val sessionManager: CourseSessionManager,
    private val drillRepo: ConjugationDrillRepository,
    private val adaptation: RoutineAdaptation,
) : ViewModel() {

    private val sessionId: Long = savedStateHandle.get<Long>("sessionId") ?: 0L

    private val index = MutableStateFlow(0)
    private val outcome = MutableStateFlow(SessionOutcome())
    private var session: CourseSessionEntity? = null
    private var indexRestored = false

    private data class SessionOutcome(
        val correct: Int = 0,
        val total: Int = 0,
        val finished: Boolean = false,
        val graduated: Boolean = false,
        val feedback: String? = null,
        val feedbackLoading: Boolean = false,
    )

    /** One line per missed item, in the learner's own session order — the feedback call's input. */
    private val missed = mutableListOf<String>()
    private var feedbackRequested = false

    /** Every graded drill answer, for a checkup's per-lesson attribution. */
    private val checkupAnswers = mutableListOf<CheckupAnswer>()

    val state: StateFlow<LessonPlayerUi> = combine(
        courseRepo.observeSession(sessionId),
        index,
        outcome,
    ) { row, i, outcome ->
        session = row
        when {
            row == null -> LessonPlayerUi(loading = false, failed = true)
            row.status == CourseSessionStatus.FAILED.name -> LessonPlayerUi(loading = false, failed = true)
            row.planJson == null -> LessonPlayerUi(loading = false, preparing = true)
            else -> {
                val plan = LessonPlanCodec.decode(row.planJson)
                if (plan == null) {
                    LessonPlayerUi(loading = false, failed = true)
                } else {
                    // The resume point is restored once, from the row; after that the local index
                    // leads and the row follows (each advance persists it).
                    if (!indexRestored) {
                        indexRestored = true
                        index.value = row.currentIndex.coerceIn(0, plan.segments.lastIndex)
                    }
                    val course = CourseCatalog.course(plan.targetLanguage, plan.courseId)
                    LessonPlayerUi(
                        loading = false,
                        plan = plan,
                        spec = drillRepo.specFor(plan.targetLanguage),
                        index = index.value.coerceIn(0, plan.segments.lastIndex),
                        courseTitle = course?.title.orEmpty(),
                        lessonTitle = plan.lessonId?.let { course?.lesson(it)?.title }.orEmpty(),
                        course = course,
                        lessonNumber = plan.lessonId
                            ?.let { id -> course?.lessons?.indexOfFirst { it.id == id } }
                            ?.let { if (it >= 0) it + 1 else 0 } ?: 0,
                        lessonCount = course?.lessons?.size ?: 0,
                        partTitle = plan.lessonId?.let { course?.partOf(it)?.title }.orEmpty(),
                        finished = outcome.finished,
                        correct = outcome.correct,
                        total = outcome.total,
                        graduated = outcome.graduated,
                        feedback = outcome.feedback,
                        feedbackLoading = outcome.feedbackLoading,
                    )
                }
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LessonPlayerUi())

    /**
     * Discards this sitting and returns the id of its replacement, for the caller to navigate to.
     * Zero when there is nothing to restart. The replacement is a *rebuild*, so a lesson taken again
     * asks different questions — and a sitting whose build failed gets a second chance.
     */
    suspend fun restart(): Long = sessionManager.restart(sessionId)

    fun advance() {
        val plan = state.value.plan ?: return
        val next = (index.value + 1).coerceAtMost(plan.segments.lastIndex)
        if (next == index.value) return
        index.value = next
        viewModelScope.launch { courseRepo.setCurrentIndex(sessionId, next) }
        // Arriving at the wrap-up is what triggers the diagnosis, so it is already on screen by the
        // time the learner has read the score.
        if (plan.segments.getOrNull(next) is WrapUpSegment) requestFeedback(plan)
    }

    private fun requestFeedback(plan: LessonPlan) {
        val lessonId = plan.lessonId ?: return
        if (feedbackRequested || missed.isEmpty()) return
        feedbackRequested = true
        outcome.value = outcome.value.copy(feedbackLoading = true)
        viewModelScope.launch {
            val note = sessionManager.feedbackFor(plan.courseId, lessonId, missed.toList())
            outcome.value = outcome.value.copy(feedback = note, feedbackLoading = false)
        }
    }

    /** A drill answer: recorded exactly as the standalone drill records it. */
    fun onDrillGrade(item: DrillItem, form: ConjugatedForm, grade: DrillGrade, latencyMs: Long, warmUp: Boolean) {
        val lang = session?.targetLanguage ?: return
        if (!warmUp) {
            outcome.value = outcome.value.copy(
                correct = outcome.value.correct + if (grade.correct) 1 else 0,
                total = outcome.value.total + 1,
            )
        }
        if (!grade.correct) {
            missed += "${item.infinitive} — ${item.target.person.slug}, ${item.target.tense.slug}: ${form.form}"
        }
        if (!warmUp) {
            checkupAnswers += CheckupAnswer(item.infinitive, item.target, grade.correct, item.direction)
        }
        viewModelScope.launch { drillRepo.record(lang, item, form, grade, latencyMs) }
    }

    /**
     * A cloze/PI answer: moves the named proficiency bucket, the standalone-practice path.
     *
     * [warmUp] is the same exemption [onDrillGrade] makes. A course with no paradigm opens on items
     * from *earlier* lessons, and grading this sitting on how well the learner remembers the last
     * one would flag the wrong lesson and shorten the checkup ladder for the wrong reason. The
     * bucket still moves either way, because that measurement is about the grammar point rather
     * than about this sitting.
     */
    fun onChoiceAnswer(
        correct: Boolean,
        latencyMs: Long,
        category: String?,
        sentence: String? = null,
        warmUp: Boolean = false,
    ) {
        val lang = session?.targetLanguage ?: return
        if (!warmUp) {
            outcome.value = outcome.value.copy(
                correct = outcome.value.correct + if (correct) 1 else 0,
                total = outcome.value.total + 1,
            )
        }
        if (!correct && sentence != null && !warmUp) missed += sentence
        if (category != null) {
            viewModelScope.launch {
                adaptation.recordStandalone(
                    bucketKey = category,
                    targetLanguage = lang,
                    correct = correct,
                    latencyMs = latencyMs,
                )
            }
        }
    }

    /** Finishes the sitting (idempotent) and applies what it means to the course's standing. */
    fun finish() {
        val row = session ?: return
        if (outcome.value.finished) return
        val snapshot = outcome.value.copy(finished = true)
        outcome.value = snapshot
        viewModelScope.launch {
            val accuracy = if (snapshot.total > 0) snapshot.correct.toDouble() / snapshot.total else null
            val graduated = courseRepo.completeSession(row, accuracy, checkupAnswers.toList())
            if (graduated) outcome.value = outcome.value.copy(graduated = true)
        }
    }
}
