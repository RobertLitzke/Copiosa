package com.copiosa.data.routine

import com.copiosa.data.local.ActivityProficiencyEntity
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.AdaptationMath
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Updates the adaptation engine after a scored attempt: bumps the relevant bucket's mastery (per
 * [AdaptationMath]) and recomputes its difficulty level and selection weight, which the synthesizer
 * and planner read on the next routine. The bucket is the contrast category for cloze items, else the
 * activity type — so e.g. ser/estar adapts independently of por/para.
 */
@Singleton
class RoutineAdaptation @Inject constructor(
    private val repo: RoutineRepository,
) {
    suspend fun recordOutcome(
        activity: ActivityInstance,
        targetLanguage: String,
        correct: Boolean,
        latencyMs: Long?,
        speed: Float = 1.0f,
        now: Long = System.currentTimeMillis(),
    ) = recordStandalone(
        bucketKey = ActivityDefinition[activity.type].bucketKey(activity.category),
        targetLanguage = targetLanguage,
        correct = correct,
        latencyMs = latencyMs,
        speed = speed,
        now = now,
    )

    /**
     * The same mastery update keyed directly by bucket, for scored practice that happens outside a
     * routine run (the standalone rapid-fire quiz). No attempt row is logged — activity_attempts is
     * keyed by a run — but the proficiency the planner and generators read moves identically, so
     * standalone and in-routine practice train the same estimate.
     */
    suspend fun recordStandalone(
        bucketKey: String,
        targetLanguage: String,
        correct: Boolean,
        latencyMs: Long?,
        speed: Float = 1.0f,
        now: Long = System.currentTimeMillis(),
    ) {
        val existing = repo.getProficiency(bucketKey, targetLanguage)
        val oldMastery = existing?.mastery ?: ActivityProficiencyEntity(
            bucketKey = bucketKey, targetLanguage = targetLanguage,
        ).mastery
        val mastery = AdaptationMath.nextMastery(oldMastery, correct, latencyMs, speed)
        repo.upsertProficiency(
            (existing ?: ActivityProficiencyEntity(bucketKey = bucketKey, targetLanguage = targetLanguage)).copy(
                mastery = mastery,
                difficultyLevel = AdaptationMath.difficultyFor(mastery),
                selectionWeight = AdaptationMath.weightFor(mastery),
                attempts = (existing?.attempts ?: 0) + 1,
                lastSeenAt = now,
            ),
        )
    }
}
