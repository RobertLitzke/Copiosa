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
import com.copiosa.domain.routine.RoutineRunStatus
import com.copiosa.domain.routine.RoutineType
import kotlinx.coroutines.flow.Flow
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

    suspend fun updateRun(run: RoutineRunEntity) = runDao.update(run)

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
}
