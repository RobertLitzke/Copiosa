package com.copiosa.ui.conjugation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.conjugation.DrillSummary
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.conjugation.ConjugatedForm
import com.copiosa.domain.conjugation.ConjugationDrillConfig
import com.copiosa.domain.conjugation.ConjugationSessionStats
import com.copiosa.domain.conjugation.DrillAnswer
import com.copiosa.domain.conjugation.DrillGrade
import com.copiosa.domain.conjugation.DrillItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The drill screen outside a session: its saved setup and how the learner is doing overall. */
data class ConjugationHomeUi(
    val loading: Boolean = true,
    val supported: Boolean = true,
    val config: ConjugationDrillConfig = ConjugationDrillConfig(),
    val summary: DrillSummary = DrillSummary.EMPTY,
)

/** A running or finished session. */
data class ConjugationSessionUi(
    val items: List<DrillItem>,
    val answered: Int = 0,
    val correct: Int = 0,
    val finished: Boolean = false,
    val stats: ConjugationSessionStats? = null,
)

@HiltViewModel
class ConjugationDrillViewModel @Inject constructor(
    private val repo: ConjugationDrillRepository,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _home = MutableStateFlow(ConjugationHomeUi())
    val home: StateFlow<ConjugationHomeUi> = _home.asStateFlow()

    private val _session = MutableStateFlow<ConjugationSessionUi?>(null)
    val session: StateFlow<ConjugationSessionUi?> = _session.asStateFlow()

    /** Answers of the session in flight, for the end-of-session splits. */
    private val answers = mutableListOf<DrillAnswer>()

    init {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val settings = settingsRepo.settings.first()
        val lang = settings.targetLanguage
        val supported = repo.supports(lang)
        _home.value = ConjugationHomeUi(
            loading = false,
            supported = supported,
            // Nothing saved yet means a first visit: seed vosotros from the profile's country rather
            // than making the learner find the toggle.
            config = ConjugationDrillConfig.decode(settings.conjugationDrillConfigJson)
                ?: ConjugationDrillConfig.forCountry(settings.defaultCountry),
            summary = if (supported) repo.summary(lang) else DrillSummary.EMPTY,
        )
    }

    fun setConfig(config: ConjugationDrillConfig) {
        val clean = config.sanitized()
        _home.value = _home.value.copy(config = clean)
        viewModelScope.launch {
            settingsRepo.update { it.copy(conjugationDrillConfigJson = clean.encode()) }
        }
    }

    fun startSession() {
        viewModelScope.launch {
            val lang = settingsRepo.settings.first().targetLanguage
            val items = repo.selectItems(lang, _home.value.config)
            answers.clear()
            _session.value = if (items.isEmpty()) null else ConjugationSessionUi(items = items)
        }
    }

    fun onGrade(item: DrillItem, form: ConjugatedForm, grade: DrillGrade, latencyMs: Long) {
        answers += DrillAnswer(item, form, grade, latencyMs)
        _session.value = _session.value?.let {
            it.copy(answered = it.answered + 1, correct = it.correct + if (grade.correct) 1 else 0)
        }
        viewModelScope.launch {
            val lang = settingsRepo.settings.first().targetLanguage
            repo.record(lang, item, form, grade, latencyMs)
        }
    }

    fun onSessionComplete() {
        _session.value = _session.value?.copy(
            finished = true,
            stats = ConjugationSessionStats.from(answers.toList()),
        )
        viewModelScope.launch { refresh() }
    }

    fun dismissSession() {
        _session.value = null
        answers.clear()
        viewModelScope.launch { refresh() }
    }
}
