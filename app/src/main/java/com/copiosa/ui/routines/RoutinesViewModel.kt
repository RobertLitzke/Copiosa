package com.copiosa.ui.routines

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.TopicEntity
import com.copiosa.data.routine.RoutineRepository
import com.copiosa.data.routine.RoutineSynthesisManager
import com.copiosa.data.routine.TopicRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.routine.RoutineType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
) : ViewModel() {

    private val _suggestions = MutableStateFlow<List<TopicEntity>>(emptyList())
    val suggestions: StateFlow<List<TopicEntity>> = _suggestions.asStateFlow()

    init {
        // Refresh suggestions whenever the active language profile changes, not just once.
        viewModelScope.launch {
            settingsRepo.activeProfileTag.collect { lang ->
                _suggestions.value = topicRepo.suggestions(lang, limit = 8)
            }
        }
    }

    /** Creates the run, starts background synthesis, and returns its id for navigation to the player. */
    suspend fun startRoutine(
        type: RoutineType,
        durationMinutes: Int,
        topic: String?,
        topicContext: String?,
    ): Long {
        val settings = settingsRepo.settings.first()
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
}
