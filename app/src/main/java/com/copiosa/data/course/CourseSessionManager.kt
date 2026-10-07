package com.copiosa.data.course

import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.local.CourseDao
import com.copiosa.data.local.CourseSessionEntity
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.remote.JsonShape
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.remote.tryLlm
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.di.AppScope
import com.copiosa.domain.course.AuthoredSegment
import com.copiosa.domain.course.CourseCatalog
import com.copiosa.domain.course.CourseContentParser
import com.copiosa.domain.course.CourseContentPrompt
import com.copiosa.domain.course.CourseContentValidator
import com.copiosa.domain.course.CourseDefinition
import com.copiosa.domain.course.CourseSessionStatus
import com.copiosa.domain.course.DrillSelector
import com.copiosa.domain.course.LessonDefinition
import com.copiosa.domain.course.LessonPlan
import com.copiosa.domain.course.LessonPlanCodec
import com.copiosa.domain.course.LessonSegmentBuilder
import com.copiosa.domain.course.LlmSegments
import com.copiosa.domain.course.ScopeFormIndex
import com.copiosa.domain.course.SessionKind
import com.copiosa.domain.model.Role
import com.copiosa.domain.usage.LlmFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds a playable [LessonPlan] for a freshly-created `course_sessions` row, mirroring
 * `RoutineSynthesisManager`'s shape: the row is created first (SYNTHESIZING), the build runs on the
 * app-lifetime scope so navigation can't cancel it, and the player observes the row's status.
 *
 * The build is offline-first: didactic segments materialize from the authored catalog, drill
 * segments select from the bundled lexicon, and an LLM slot that can't be filled — no key, offline,
 * output rejected — is *dropped*, with the practice time it held handed to an extra engine drill
 * instead. A sitting therefore always plays; only its contextual garnish is conditional.
 */
@Singleton
class CourseSessionManager @Inject constructor(
    private val dao: CourseDao,
    private val courseRepo: CourseRepository,
    private val drillRepo: ConjugationDrillRepository,
    private val settingsRepo: SettingsRepository,
    private val providers: ProviderRegistry,
    private val contentPrompt: CourseContentPrompt,
    private val flashcardDao: FlashcardDao,
    @AppScope private val scope: CoroutineScope,
) {

    /**
     * Opens (or resumes) a lesson sitting and returns its session id. An unfinished sitting of the
     * same lesson is reused — abandoning a lesson mid-way and tapping it again must continue, not
     * stack a second copy.
     */
    suspend fun startLesson(courseId: String, lessonId: String): Long =
        start(courseId, lessonId, SessionKind.LESSON)

    /** A didactic replay plus a short scoped drill — the checkup-failure remedial sitting. */
    suspend fun startMicroLesson(courseId: String, lessonId: String): Long =
        start(courseId, lessonId, SessionKind.MICRO_LESSON)

    /** The retention check: a few minutes across everything the course taught. */
    suspend fun startCheckup(courseId: String): Long =
        start(courseId, lessonId = null, kind = SessionKind.CHECKUP)

    /**
     * Throws away [sessionId] and opens a fresh sitting of the same thing, returning its id.
     *
     * Restarting rebuilds rather than rewinds: new drill items, another generation of contextual
     * sentences. That is the point — a lesson re-taken from the top with the identical twenty cards
     * would measure recognition of *this sitting*, and it is also the only way out of a sitting whose
     * build failed. Returns 0 when the row is gone, which the caller reads as "nothing to restart".
     */
    suspend fun restart(sessionId: Long): Long {
        val row = dao.session(sessionId) ?: return 0L
        val kind = SessionKind.fromName(row.kind) ?: SessionKind.LESSON
        dao.deleteSession(sessionId)
        return start(row.courseId, row.lessonId, kind)
    }

    private suspend fun start(courseId: String, lessonId: String?, kind: SessionKind): Long {
        val lang = settingsRepo.settings.first().targetLanguage
        val now = System.currentTimeMillis()
        dao.pruneUnstarted(olderThan = now - STALE_SESSION_MS)

        val open = dao.openSession(courseId, lang)
        if (open != null && open.lessonId == lessonId && open.kind == kind.name) {
            // A row still SYNTHESIZING long after it was created means the process died mid-build:
            // the coroutine that would have finished it is gone, so returning the row as-is would
            // park the player on a spinner with no retry path — the failure mode a stranded routine
            // run has. Rebuild it instead. The age check keeps this from racing a live build.
            if (open.status == CourseSessionStatus.SYNTHESIZING.name &&
                now - open.createdAt > BUILD_ABANDONED_MS
            ) {
                scope.launch { build(open.id) }
            }
            return open.id
        }

        courseRepo.ensureStarted(courseId, lang, now)
        val id = dao.insertSession(
            CourseSessionEntity(
                courseId = courseId,
                lessonId = lessonId,
                kind = kind.name,
                targetLanguage = lang,
                createdAt = now,
            ),
        )
        scope.launch { build(id) }
        return id
    }

    private suspend fun build(sessionId: Long) {
        val session = dao.session(sessionId) ?: return
        try {
            val lang = session.targetLanguage
            val course = CourseCatalog.course(lang, session.courseId)
                ?: error("unknown course '${session.courseId}' for $lang")
            val kind = SessionKind.fromName(session.kind) ?: SessionKind.LESSON
            val selector = drillSelectorFor(lang)

            val segments = if (kind == SessionKind.CHECKUP) {
                LessonSegmentBuilder.buildCheckup(course, lang, selector)
            } else {
                val lesson = session.lessonId?.let(course::lesson)
                    ?: error("unknown lesson '${session.lessonId}' in ${session.courseId}")
                if (kind == SessionKind.MICRO_LESSON) {
                    LessonSegmentBuilder.buildMicroLesson(lesson, lang, selector)
                } else {
                    LessonSegmentBuilder.buildLesson(
                        lesson = lesson,
                        priorLessons = taughtBefore(course, lesson, lang),
                        language = lang,
                        selector = selector,
                        llmSegments = generateSlots(course, lesson, lang),
                    )
                }
            }
            if (!LessonSegmentBuilder.isPlayable(segments)) {
                error("nothing playable for ${session.courseId}/${session.lessonId}")
            }

            val plan = LessonPlan(
                courseId = course.id,
                lessonId = session.lessonId,
                targetLanguage = lang,
                sessionKind = kind,
                segments = segments,
            )
            dao.savePlan(sessionId, LessonPlanCodec.encode(plan), CourseSessionStatus.READY.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.setSessionStatus(sessionId, CourseSessionStatus.FAILED.name, e.message)
        }
    }

    /** What the learner has actually finished, for [CourseDefinition.lessonsTaughtBefore]. */
    private suspend fun taughtBefore(
        course: CourseDefinition,
        lesson: LessonDefinition,
        lang: String,
    ): List<LessonDefinition> {
        val completed = dao.lessonStates(course.id, lang)
            .filter { it.completedAt != null }
            .mapTo(mutableSetOf()) { it.lessonId }
        return course.lessonsTaughtBefore(lesson.id, completed)
    }

    /** The builder's window onto the drill store, narrowed to one language. */
    private fun drillSelectorFor(lang: String) = DrillSelector { scope, itemCount, emphasis ->
        drillRepo.selectItems(
            lang,
            scope.drillConfig(itemCount, emphasis),
            scope = scope.verbFilter(),
        )
    }

    /**
     * Fills the lesson's practice slots in **one** consolidated call, validated down against the
     * conjugation engine. Every failure mode — no key, an offline device, an unparseable or rejected
     * response — returns an empty map, which the builder reads as "widen the engine drills": the
     * lesson still plays, just without contextual sentences. This is why the whole course works on a
     * plane, and why a bad generation can never put a wrong form in front of the learner.
     */
    private suspend fun generateSlots(
        course: CourseDefinition,
        lesson: LessonDefinition,
        lang: String,
    ): LlmSegments {
        val slots = lesson.segments.withIndex()
            .mapNotNull { (i, s) -> (s as? AuthoredSegment.LlmSlot)?.let { IndexedValue(i, it) } }
        if (slots.isEmpty()) return emptyMap()

        val settings = settingsRepo.settings.first()
        val provider = runCatching { providers.active() }.getOrNull() ?: return emptyMap()
        if (!settingsRepo.hasApiKey(provider.id)) return emptyMap()

        val spec = drillRepo.specFor(lang)
        val raw = tryLlm {
            provider.chat(
                LlmRequest(
                    systemPrompt = contentPrompt.systemPrompt(
                        targetLang = lang,
                        nativeLang = settings.nativeLanguage,
                        level = settings.defaultLevel,
                        country = settings.defaultCountry,
                    ),
                    messages = listOf(
                        LlmMessage(
                            Role.USER,
                            contentPrompt.userRequest(course, lesson, slots, spec, recentVocab(lang)),
                        ),
                    ),
                    model = settings.activeModel,
                    feature = LlmFeature.COURSE,
                    maxTokens = MAX_TOKENS,
                    json = JsonShape.OBJECT,
                ),
            ).text
        } ?: return emptyMap()

        val parsed = CourseContentParser.parse(raw)
        if (parsed.isEmpty()) return emptyMap()

        val forms = ScopeFormIndex.build(spec, lesson.scope)
        return slots.mapNotNull { (index, slot) ->
            val items = parsed[index]?.items ?: return@mapNotNull null
            CourseContentValidator.validate(slot, lesson.scope, items, forms)?.let { index to it }
        }.toMap()
    }

    /**
     * The optional second call: one short diagnosis of what went wrong this sitting, shown on the
     * wrap-up screen. Silent on every failure — feedback is a bonus, and a lesson that taught well
     * must not end on an error message. Returns null when there is nothing to diagnose.
     */
    suspend fun feedbackFor(courseId: String, lessonId: String, missed: List<String>): String? {
        if (missed.isEmpty()) return null
        val settings = settingsRepo.settings.first()
        val lang = settings.targetLanguage
        val lesson = CourseCatalog.course(lang, courseId)?.lesson(lessonId) ?: return null
        val provider = runCatching { providers.active() }.getOrNull() ?: return null
        if (!settingsRepo.hasApiKey(provider.id)) return null

        return tryLlm {
            val raw = provider.chat(
                LlmRequest(
                    systemPrompt = contentPrompt.systemPrompt(
                        targetLang = lang,
                        nativeLang = settings.nativeLanguage,
                        level = settings.defaultLevel,
                        country = settings.defaultCountry,
                    ),
                    messages = listOf(
                        LlmMessage(Role.USER, contentPrompt.feedbackRequest(lesson, missed)),
                    ),
                    model = settings.activeModel,
                    feature = LlmFeature.COURSE,
                    maxTokens = FEEDBACK_MAX_TOKENS,
                    json = JsonShape.OBJECT,
                ),
            ).text
            CourseContentParser.parseFeedback(raw)
        }
    }

    /**
     * Words to weave into the generated sentences: what is due soonest, so a lesson's practice
     * doubles as vocabulary recycling — the same principle behind the routine's vocab seeding.
     */
    private suspend fun recentVocab(lang: String): List<String> =
        flashcardDao.upcomingByLanguage(lang, System.currentTimeMillis() + VOCAB_WINDOW_MS, VOCAB_POOL)
            .filter { it.cardKind.isLexicalVocab }
            .map { it.front.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_VOCAB)

    private companion object {
        /** A sitting created but never opened is abandoned after a day. */
        const val STALE_SESSION_MS = 24 * 60 * 60 * 1000L

        /**
         * How long a build may plausibly still be running. A lesson build is one LLM call plus some
         * local selection, so well under a minute; past this, a SYNTHESIZING row is a corpse.
         */
        const val BUILD_ABANDONED_MS = 90_000L

        /** One lesson's slots comfortably fit the routine synthesis budget. */
        const val MAX_TOKENS = 4096

        /** Three sentences of diagnosis; the cap is a guard, not a target. */
        const val FEEDBACK_MAX_TOKENS = 512

        const val VOCAB_POOL = 60
        const val MAX_VOCAB = 10
        val VOCAB_WINDOW_MS = 14 * 24 * 60 * 60 * 1000L
    }
}
