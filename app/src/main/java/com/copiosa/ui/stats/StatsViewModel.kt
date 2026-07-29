package com.copiosa.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.stats.LearnerStats
import com.copiosa.data.stats.StatsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Whether the stats screen shows the active profile or every language combined. */
enum class StatsScope { ACTIVE, ALL }

data class StatsUi(
    val loaded: Boolean = false,
    val scope: StatsScope = StatsScope.ACTIVE,
    /** The active profile's tag — shown in the header and named in the clear-confirmation copy. */
    val activeLang: String = "",
    val stats: LearnerStats? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val statsRepo: StatsRepository,
) : ViewModel() {

    private val scope = MutableStateFlow(StatsScope.ACTIVE)

    val ui: StateFlow<StatsUi> =
        combine(settingsRepo.activeProfileTag, scope) { lang, sc -> lang to sc }
            .flatMapLatest { (lang, sc) ->
                // Null lang means "all profiles combined" (see StatsRepository).
                statsRepo.observeStats(if (sc == StatsScope.ALL) null else lang)
                    .map { stats -> StatsUi(loaded = true, scope = sc, activeLang = lang, stats = stats) }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUi())

    fun setScope(newScope: StatsScope) {
        scope.value = newScope
    }

    /** Clears focus-time history for whatever scope is currently shown. */
    fun clearStats() {
        viewModelScope.launch {
            val lang = if (scope.value == StatsScope.ALL) null else settingsRepo.activeProfileTag.first()
            statsRepo.clearFocusStats(lang)
        }
    }
}
