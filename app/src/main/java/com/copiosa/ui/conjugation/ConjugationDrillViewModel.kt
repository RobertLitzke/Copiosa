package com.copiosa.ui.conjugation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.conjugation.DrillSummary
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.conjugation.ConjugatedForm
import com.copiosa.domain.conjugation.ConjugationDrillConfig
import com.copiosa.domain.conjugation.ConjugationSessionStats
import com.copiosa.domain.conjugation.ConjugationSpec
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
    /** The profile language's paradigm data; null when the drill has none for it. */
    val spec: ConjugationSpec? = null,
    val config: ConjugationDrillConfig = ConjugationDrillConfig(),
    val summary: DrillSummary = DrillSummary.EMPTY,
    /** The last Start found no verb fitting [config]; cleared by the next change to it. */
    val nothingToDrill: Boolean = false,
) {
    val supported: Boolean get() = spec != null
}

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
        val spec = repo.specFor(lang)
        _home.value = ConjugationHomeUi(
            loading = false,
            spec = spec,
            // Nothing saved yet means a first visit: seed vosotros from the profile's country rather
            // than making the learner find the toggle.
            config = ConjugationDrillConfig.decode(settings.conjugationDrillConfigJson)
                ?: ConjugationDrillConfig.forCountry(settings.defaultCountry),
            summary = if (spec != null) repo.summary(lang) else DrillSummary.EMPTY,
            // A refresh can land on either side of a Start (see "Drill again"); it must not unsay it.
            nothingToDrill = _home.value.nothingToDrill,
        )
    }

    fun setConfig(config: ConjugationDrillConfig) {
        val spec = _home.value.spec ?: return
        val clean = config.sanitized(spec)
        _home.value = _home.value.copy(config = clean, nothingToDrill = false)
        viewModelScope.launch {
            settingsRepo.update { it.copy(conjugationDrillConfigJson = clean.encode()) }
        }
    }

    fun startSession() {
        viewModelScope.launch {
            val lang = settingsRepo.settings.first().targetLanguage
            val items = repo.selectItems(lang, _home.value.config)
            answers.clear()
            // A setup can be valid chip by chip and still match no verb ("irregular only" in a tense
            // with no irregulars); the setup pane says so rather than leaving Start looking dead.
            _home.value = _home.value.copy(nothingToDrill = items.isEmpty())
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
