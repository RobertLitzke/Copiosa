package com.copiosa.data.conjugation

import com.copiosa.data.local.ConjugationAttemptEntity
import com.copiosa.data.local.ConjugationDrillDao
import com.copiosa.data.local.ConjugationItemStatEntity
import com.copiosa.data.routine.RoutineAdaptation
import com.copiosa.domain.conjugation.ConjugatedForm
import com.copiosa.domain.conjugation.ConjugationDrillConfig
import com.copiosa.domain.conjugation.ConjugationDrillSelector
import com.copiosa.domain.conjugation.ConjugationEngine
import com.copiosa.domain.conjugation.ConjugationMastery
import com.copiosa.domain.conjugation.DrillGrade
import com.copiosa.domain.conjugation.DrillItem
import com.copiosa.domain.conjugation.IrregularityFamily
import com.copiosa.domain.conjugation.ItemStat
import com.copiosa.domain.conjugation.VerbLexicon
import com.copiosa.domain.routine.AdaptationMath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * The conjugation drill's store: turns the bundled lexicon plus what the learner has done before into
 * a session, and records each graded answer.
 *
 * Two things are written per answer. The drill's own tables carry the durable history — the per-item
 * mastery the selector reads, and an append-only attempt log with the **raw** prompt-to-reveal
 * latency, so the mastery thresholds can be retuned later and the history re-scored. Separately the
 * answer feeds `activity_proficiency` through [RoutineAdaptation.recordStandalone], exactly as the
 * standalone quiz does, so practising outside a routine still moves what the planner sees.
 */
@Singleton
class ConjugationDrillRepository @Inject constructor(
    private val dao: ConjugationDrillDao,
    private val adaptation: RoutineAdaptation,
) {

    /** Whether the drill has anything to offer for a profile. Spanish-only in v1. */
    fun supports(lang: String): Boolean = VerbLexicon.forLanguage(lang).isNotEmpty()

    suspend fun selectItems(
        lang: String,
        config: ConjugationDrillConfig,
        count: Int = config.itemCount,
        random: Random = Random.Default,
        now: Long = System.currentTimeMillis(),
    ): List<DrillItem> = withContext(Dispatchers.IO) {
        val verbs = VerbLexicon.forLanguage(lang)
        if (verbs.isEmpty()) return@withContext emptyList()

        val stats = dao.itemStats(lang).associate {
            it.itemKey to ItemStat(it.attempts, it.mastery, it.lastSeenAt)
        }
        ConjugationDrillSelector(random).select(
            verbs = verbs,
            config = config,
            stats = stats,
            familyMastery = familyMastery(lang),
            seenVerbs = dao.seenInfinitives(lang).toSet(),
            now = now,
            count = count,
        )
    }

    /**
     * Records one graded answer.
     *
     * [updateProficiency] exists for the in-routine path: there the player already reports the answer
     * through `RoutinePlayerViewModel.recordAttempt`, which moves the same `activity_proficiency`
     * bucket, so letting this move it too would count every routine answer twice.
     */
    suspend fun record(
        lang: String,
        item: DrillItem,
        form: ConjugatedForm,
        grade: DrillGrade,
        latencyMs: Long,
        now: Long = System.currentTimeMillis(),
        updateProficiency: Boolean = true,
    ) = withContext(Dispatchers.IO) {
        dao.insertAttempt(
            ConjugationAttemptEntity(
                itemKey = item.itemKey,
                // The verb actually shown; a generalize card's key names its class instead.
                infinitive = item.infinitive,
                tense = item.target.tense.slug,
                person = item.target.person.slug,
                direction = item.direction.slug,
                family = form.family.slug,
                targetLanguage = lang,
                grade = grade.slug,
                correct = grade.correct,
                latencyMs = latencyMs,
                createdAt = now,
                epochDay = epochDay(now),
            ),
        )

        val existing = dao.itemStat(item.itemKey, lang)
        val attempts = (existing?.attempts ?: 0) + 1
        val correct = (existing?.correct ?: 0) + if (grade.correct) 1 else 0
        val averaged = (((existing?.avgLatencyMs ?: 0L) * (attempts - 1)) + latencyMs) / attempts
        val updated = (existing ?: ConjugationItemStatEntity(itemKey = item.itemKey, targetLanguage = lang))
            .copy(
                attempts = attempts,
                correct = correct,
                mastery = ConjugationMastery.next(
                    existing?.mastery ?: ItemStat.DEFAULT_MASTERY, grade, latencyMs,
                ),
                avgLatencyMs = averaged,
                lastLatencyMs = latencyMs,
                lastSeenAt = now,
            )
        if (existing == null) dao.insertStat(updated) else dao.updateStat(updated)

        // The routine engine's own fast threshold is nearly three times looser, so a drill latency is
        // rescaled before it gets there — otherwise a two-second answer and a seven-second one would
        // look identical to the planner.
        if (updateProficiency) {
            adaptation.recordStandalone(
                bucketKey = form.family.slug,
                targetLanguage = lang,
                correct = grade.correct,
                latencyMs = ConjugationMastery.scaleForRoutineAdaptation(latencyMs, AdaptationMath.FAST_MS),
                now = now,
            )
        }
    }

    /** Lifetime totals for the drill's home screen. */
    suspend fun summary(lang: String): DrillSummary = withContext(Dispatchers.IO) {
        val stats = dao.itemStats(lang)
        DrillSummary(
            itemsSeen = stats.size,
            itemsMastered = stats.count { it.mastery >= MASTERED },
            attempts = stats.sumOf { it.attempts },
            correct = stats.sumOf { it.correct },
        )
    }

    suspend fun clear(lang: String?) = withContext(Dispatchers.IO) {
        dao.clearAttempts(lang)
        dao.clearItemStats(lang)
    }

    /**
     * Mastery per irregularity family, read from the same `activity_proficiency` rows the routine
     * planner uses — so a family the learner has met inside a routine is already warm here.
     */
    private suspend fun familyMastery(lang: String): Map<IrregularityFamily, Double> =
        IrregularityFamily.entries.mapNotNull { family ->
            adaptation.masteryFor(family.slug, lang)?.let { family to it }
        }.toMap()

    private fun epochDay(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private companion object {
        /** Matches the threshold the stats screen reports against. */
        const val MASTERED = 0.85
    }
}

/** Lifetime drill totals, shown before a session starts. */
data class DrillSummary(
    val itemsSeen: Int,
    val itemsMastered: Int,
    val attempts: Int,
    val correct: Int,
) {
    val accuracyPercent: Int? get() = if (attempts == 0) null else (correct * 100) / attempts

    companion object {
        val EMPTY = DrillSummary(0, 0, 0, 0)
    }
}

/** Resolves a drill item to the form it is asking about, or null if the lexicon can't place it. */
fun DrillItem.conjugate(): ConjugatedForm? =
    VerbLexicon.byInfinitive(infinitive)?.let {
        ConjugationEngine.conjugate(it, target.tense, target.person)
    }

/** The source cell of a transform card, resolved the same way. */
fun DrillItem.Transform.conjugateSource(): ConjugatedForm? =
    VerbLexicon.byInfinitive(infinitive)?.let {
        ConjugationEngine.conjugate(it, source.tense, source.person)
    }
