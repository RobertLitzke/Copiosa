package com.copiosa.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.local.TopicEntity
import com.copiosa.data.routine.ResumableRoutine
import com.copiosa.data.routine.RoutineRepository
import com.copiosa.data.routine.RoutineSynthesisManager
import com.copiosa.data.routine.TopicRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.course.CourseCatalog
import com.copiosa.domain.routine.RoutineType
import com.copiosa.domain.vocab.freq.FrequencyLists
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the Routines tab: offers the available routines, pre-fills topic suggestions from the
 * interest table, and creates + kicks off synthesis for a chosen routine. The player screen observes
 * the run that [startRoutine] creates.
 */
@HiltViewModel
class RoutinesViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val routineRepo: RoutineRepository,
    private val topicRepo: TopicRepository,
    private val synthesizer: RoutineSynthesisManager,
    private val drillRepo: ConjugationDrillRepository,
) : ViewModel() {

    /** Topic seeds per routine type — each orders the same interest table to suit its own session. */
    private val _suggestions = MutableStateFlow<Map<RoutineType, List<TopicEntity>>>(emptyMap())
    val suggestions: StateFlow<Map<RoutineType, List<TopicEntity>>> = _suggestions.asStateFlow()

    /** Whether the conjugation drill has a verb lexicon for the active profile — Spanish only in v1. */
    private val _conjugationAvailable = MutableStateFlow(false)
    val conjugationAvailable: StateFlow<Boolean> = _conjugationAvailable.asStateFlow()

    /** Whether the active profile's language ships a frequency list to drill from. */
    private val _frequencyDrillAvailable = MutableStateFlow(false)
    val frequencyDrillAvailable: StateFlow<Boolean> = _frequencyDrillAvailable.asStateFlow()

    /** Whether the active profile's language has authored tense courses. */
    private val _coursesAvailable = MutableStateFlow(false)
    val coursesAvailable: StateFlow<Boolean> = _coursesAvailable.asStateFlow()

    /** The routine left unfinished in the active language, if any — what the "continue" card offers. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val resumable: StateFlow<ResumableRoutine?> =
        settingsRepo.activeProfileTag
            .flatMapLatest { lang -> routineRepo.observeResumable(lang) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Refresh suggestions whenever the active language profile changes, not just once.
        viewModelScope.launch {
            settingsRepo.activeProfileTag.collect { lang ->
                _suggestions.value = RoutineType.entries.associateWith { type ->
                    topicRepo.suggestions(lang, type, limit = SUGGESTION_LIMIT)
                }
                _conjugationAvailable.value = drillRepo.supports(lang)
                _frequencyDrillAvailable.value = FrequencyLists.supports(lang)
                _coursesAvailable.value = drillRepo.supports(lang) && CourseCatalog.forTag(lang).isNotEmpty()
            }
        }
    }

    /**
     * How many active cards a frequent-word drill scoped to [maxRank] could draw on — the figure the
     * picker shows. Read through the synthesizer, which already owns this join for the routine
     * activity of the same name, so the card and the planned step agree on what "available" means.
     */
    suspend fun frequentWordCount(maxRank: Int): Int =
        synthesizer.frequentWordCardCount(settingsRepo.activeProfileTag.first(), maxRank)

    /** Throws away an unfinished run from the "continue" card, stopping its synthesis if it has one. */
    fun discard(runId: Long) {
        viewModelScope.launch { synthesizer.discard(runId) }
    }

    /**
     * Creates the run, starts background synthesis, and returns its id for navigation to the player.
     *
     * Starting a routine replaces whatever was left unfinished in this language. One "continue" card
     * can only point at one run, so an older one would otherwise be hidden behind the new one and
     * then reappear, a day stale, the moment the new one was finished.
     */
    suspend fun startRoutine(
        type: RoutineType,
        durationMinutes: Int,
        topic: String?,
        topicContext: String?,
    ): Long {
        val settings = settingsRepo.settings.first()
        routineRepo.unfinishedIds(settings.targetLanguage).forEach { synthesizer.discard(it) }
        val runId = routineRepo.createRun(
            routineType = type,
            durationMinutes = durationMinutes,
            targetLanguage = settings.targetLanguage,
            level = settings.defaultLevel,
            topic = topic?.trim()?.ifBlank { null },
            topicContext = topicContext?.trim()?.ifBlank { null },
        )
        synthesizer.synthesize(runId)
        return runId
    }

    private companion object {
        const val SUGGESTION_LIMIT = 8
    }
}
