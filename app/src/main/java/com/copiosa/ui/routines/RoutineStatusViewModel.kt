package com.copiosa.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.ActivityProficiencyEntity
import com.copiosa.data.routine.RoutineRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.routine.ActivityRegistry
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.AppearFrequency
import com.copiosa.domain.routine.ComfortBand
import com.copiosa.domain.routine.DifficultyTier
import com.copiosa.domain.routine.RoutineComfort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * One scored bucket's comfort readout — a contrast-cloze category (then [label] is the category name)
 * or referential PI (then [label] is null and the card's own title names it). [scorePercent] is null
 * until the bucket has actually been practiced.
 */
data class BucketStatus(
    val label: String?,
    val band: ComfortBand,
    val scorePercent: Int?,
    val tier: DifficultyTier,
    val frequency: AppearFrequency,
    val attempts: Int,
    val lastSeenAt: Long?,
)

/**
 * The routine status readout: the adaptation engine's per-bucket comfort for the Spanish-only scored
 * drills (the five contrast-cloze categories + referential PI), plus the input level that drives the
 * unscored affective-PI difficulty.
 */
data class RoutineStatusUi(
    val loading: Boolean = true,
    val isSpanish: Boolean = true,
    val clozeCategories: List<BucketStatus> = emptyList(),
    val referentialPi: BucketStatus? = null,
    val multipleChoiceCloze: BucketStatus? = null,
    /** Rapid-fire listening comfort per cue type (all languages, not just Spanish). */
    val rapidFire: List<BucketStatus> = emptyList(),
    /** Conjugation comfort per irregularity family (Spanish only). */
    val conjugation: List<BucketStatus> = emptyList(),
    val level: Cefr = Cefr.B2,
)

/**
 * Backs the routine status screen: joins the live `activity_proficiency` rows
 * ([RoutineRepository.observeProficiency]) for the current target language onto the expected scored
 * buckets, mapping each to a learner-facing comfort readout via [RoutineComfort].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class RoutineStatusViewModel @Inject constructor(
    settingsRepo: SettingsRepository,
    routineRepo: RoutineRepository,
) : ViewModel() {

    val state: StateFlow<RoutineStatusUi> =
        settingsRepo.settings.flatMapLatest { settings ->
            routineRepo.observeProficiency(settings.targetLanguage).map { rows ->
                val byBucket = rows.associateBy { it.bucketKey }
                // Categorized types enumerate their buckets on their descriptors, so a new category
                // (or categorized activity) surfaces here without touching this ViewModel.
                RoutineStatusUi(
                    loading = false,
                    isSpanish = settings.targetLanguage == "es",
                    clozeCategories = ActivityRegistry[ActivityType.CONTRAST_CLOZE].statusCategories.map { cat ->
                        readout(cat.label, byBucket[cat.slug])
                    },
                    referentialPi = readout(label = null, byBucket[ActivityType.REFERENTIAL_PI.name]),
                    multipleChoiceCloze = readout(label = null, byBucket[ActivityType.MULTIPLE_CHOICE_CLOZE.name]),
                    rapidFire = ActivityRegistry[ActivityType.RAPID_FIRE_LISTENING].statusCategories.map { cat ->
                        readout(cat.label, byBucket[cat.slug])
                    },
                    conjugation = ActivityRegistry[ActivityType.CONJUGATION_DRILL].statusCategories.map { cat ->
                        readout(cat.label, byBucket[cat.slug])
                    },
                    level = settings.defaultLevel,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutineStatusUi())

    private fun readout(label: String?, row: ActivityProficiencyEntity?): BucketStatus {
        val mastery = row?.mastery ?: 0.0
        val attempts = row?.attempts ?: 0
        return BucketStatus(
            label = label,
            band = RoutineComfort.band(mastery, attempts),
            scorePercent = RoutineComfort.scorePercent(mastery, attempts),
            tier = RoutineComfort.tier(row?.difficultyLevel ?: 0),
            frequency = RoutineComfort.frequency(row?.selectionWeight ?: 1.0),
            attempts = attempts,
            lastSeenAt = row?.lastSeenAt,
        )
    }
}
