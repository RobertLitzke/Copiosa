package com.copiosa.data.routine

import com.copiosa.data.local.ActivityAttemptDao
import com.copiosa.data.local.ActivityAttemptEntity
import com.copiosa.data.local.ActivityProficiencyDao
import com.copiosa.data.local.ActivityProficiencyEntity
import com.copiosa.data.local.RoutineRunDao
import com.copiosa.data.local.RoutineRunEntity
import com.copiosa.data.local.StoryThreadDao
import com.copiosa.data.local.StoryThreadEntity
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.RoutinePlanCodec
import com.copiosa.domain.routine.RoutineRunStatus
import com.copiosa.domain.routine.RoutineType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistence for the routines feature: the synthesized [RoutineRunEntity] (its plan stored as JSON in
 * [RoutineRunEntity.planJson]), the silent [ActivityAttemptEntity] log, and the adaptation engine's
 * per-bucket [ActivityProficiencyEntity]. Content generation, planning, and adaptation math live
 * elsewhere; this type is just the durable store.
 */
@Singleton
class RoutineRepository @Inject constructor(
    private val runDao: RoutineRunDao,
    private val attemptDao: ActivityAttemptDao,
    private val proficiencyDao: ActivityProficiencyDao,
    private val storyThreadDao: StoryThreadDao,
) {
    /** The running serialized-story thread for [lang] (null until the first chapter is generated). */
    suspend fun getStoryThread(lang: String): StoryThreadEntity? = storyThreadDao.get(lang)

    /** Advances the story thread for [lang] to the next chapter with a fresh recap. */
    suspend fun advanceStoryThread(lang: String, chapter: Int, summary: String) {
        val now = System.currentTimeMillis()
        val existing = storyThreadDao.get(lang)
        if (existing == null) {
            storyThreadDao.insert(StoryThreadEntity(targetLanguage = lang, chapter = chapter, summary = summary, updatedAt = now))
        } else {
            storyThreadDao.update(existing.copy(chapter = chapter, summary = summary, updatedAt = now))
        }
    }
    /** Creates a run in [RoutineRunStatus.SYNTHESIZING]; the plan is filled in by [savePlan]. */
    suspend fun createRun(
        routineType: RoutineType,
        durationMinutes: Int,
        targetLanguage: String,
        level: Cefr,
        topic: String? = null,
        topicContext: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Long = runDao.insert(
        RoutineRunEntity(
            routineType = routineType,
            durationMinutes = durationMinutes,
            topic = topic,
            topicContext = topicContext,
            targetLanguage = targetLanguage,
            level = level,
            status = RoutineRunStatus.SYNTHESIZING,
            createdAt = now,
        ),
    )

    suspend fun getRun(id: Long): RoutineRunEntity? = runDao.getById(id)

    fun observeRun(id: Long): Flow<RoutineRunEntity?> = runDao.observe(id)

    /** Stores the synthesized plan and flips the run to [RoutineRunStatus.READY]. */
    suspend fun savePlan(id: Long, planJson: String) {
        val run = runDao.getById(id) ?: return
        runDao.update(run.copy(planJson = planJson, status = RoutineRunStatus.READY, error = null))
    }

    suspend fun setStatus(id: Long, status: RoutineRunStatus, error: String? = null) {
        val run = runDao.getById(id) ?: return
        runDao.update(run.copy(status = status, error = error))
    }

    suspend fun markStarted(id: Long, now: Long = System.currentTimeMillis()) {
        val run = runDao.getById(id) ?: return
        runDao.update(run.copy(status = RoutineRunStatus.IN_PROGRESS, startedAt = run.startedAt ?: now))
    }

    suspend fun markCompleted(id: Long, now: Long = System.currentTimeMillis()) {
        val run = runDao.getById(id) ?: return
        runDao.update(run.copy(status = RoutineRunStatus.COMPLETED, completedAt = now))
    }

    suspend fun setCurrentIndex(id: Long, index: Int) = runDao.setCurrentIndex(id, index)

    /**
     * The run in [lang] worth offering to return to, if there is one: the newest unfinished run
     * younger than [RESUME_WINDOW_MS]. Past that a wind-down has stopped being tonight's, and its
     * row just waits to be cleared by the next run that starts (see [unfinishedIds]).
     */
    fun observeResumable(lang: String, now: Long = System.currentTimeMillis()): Flow<ResumableRoutine?> =
        runDao.observeLatestInProgress(lang, now - RESUME_WINDOW_MS).map { it?.let(::resumableOf) }

    /** Runs in [lang] that never finished — what starting a new one clears away. */
    suspend fun unfinishedIds(lang: String): List<Long> = runDao.unfinishedIds(lang)

    /**
     * Removes a run that is being abandoned. Its attempt log stays: `activity_attempts` only
     * indexes the run id, and what the learner answered before giving up still happened.
     */
    suspend fun deleteRun(id: Long) = runDao.delete(id)

    suspend fun lastCompleted(type: RoutineType, lang: String): RoutineRunEntity? =
        runDao.lastCompleted(type, lang)

    // --- Attempts (silent assessment log) ---

    suspend fun logAttempt(
        routineRunId: Long,
        activityType: ActivityType,
        targetLanguage: String,
        category: String? = null,
        correct: Boolean? = null,
        latencyMs: Long? = null,
        difficulty: Int = 0,
        now: Long = System.currentTimeMillis(),
    ): Long = attemptDao.insert(
        ActivityAttemptEntity(
            routineRunId = routineRunId,
            activityType = activityType,
            category = category,
            targetLanguage = targetLanguage,
            correct = correct,
            latencyMs = latencyMs,
            difficulty = difficulty,
            createdAt = now,
        ),
    )

    // --- Proficiency (adaptation state) ---

    suspend fun proficiencyFor(targetLanguage: String): List<ActivityProficiencyEntity> =
        proficiencyDao.forLanguage(targetLanguage)

    fun observeProficiency(targetLanguage: String): Flow<List<ActivityProficiencyEntity>> =
        proficiencyDao.observeForLanguage(targetLanguage)

    suspend fun getProficiency(bucketKey: String, targetLanguage: String): ActivityProficiencyEntity? =
        proficiencyDao.get(bucketKey, targetLanguage)

    /** Inserts a new proficiency row or updates the existing one in place (id preserved). */
    suspend fun upsertProficiency(row: ActivityProficiencyEntity) {
        val existing = proficiencyDao.get(row.bucketKey, row.targetLanguage)
        if (existing == null) proficiencyDao.insert(row) else proficiencyDao.update(row.copy(id = existing.id))
    }

    companion object {
        /** How long an unfinished run stays on offer. A day: last night's wind-down, not last week's. */
        const val RESUME_WINDOW_MS = 24L * 60 * 60 * 1000
    }
}

/**
 * An unfinished run as the "continue" cards show it. [total] is 0 while the run is still being
 * prepared — there is no plan to count yet — and [step] is the 1-based step it would open on.
 */
data class ResumableRoutine(
    val runId: Long,
    val type: RoutineType,
    val preparing: Boolean,
    val step: Int,
    val total: Int,
) {
    /** Prepared but never opened, as against left part-way through. */
    val notStarted: Boolean get() = !preparing && step == 1
}

/**
 * [run] as something to return to, or null when it can't be: a plan that no longer decodes, or one
 * whose resume point is already past its last step, would open on nothing.
 */
internal fun resumableOf(run: RoutineRunEntity): ResumableRoutine? {
    if (run.status == RoutineRunStatus.SYNTHESIZING) {
        return ResumableRoutine(run.id, run.routineType, preparing = true, step = 1, total = 0)
    }
    val total = RoutinePlanCodec.decode(run.planJson)?.activities?.size ?: return null
    if (run.currentIndex !in 0 until total) return null
    return ResumableRoutine(run.id, run.routineType, preparing = false, step = run.currentIndex + 1, total = total)
}
