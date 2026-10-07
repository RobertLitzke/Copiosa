package com.copiosa.ui.reference

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.conjugation.ConjugationDrillRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.conjugation.ConjugationSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * What every screen in the reference section needs: the active profile's paradigm data, and which
 * topics that data supports.
 *
 * [resolved] is not cosmetic — a hub that renders an empty list before the spec has loaded says
 * "this language has no reference", which is a different claim from "still looking".
 */
data class ReferenceUi(
    val spec: ConjugationSpec? = null,
    val resolved: Boolean = false,
) {
    val topics: List<ReferenceTopic>
        get() = if (!resolved) emptyList()
        else ReferenceTopic.entries.filter { !it.needsConjugation || spec != null }
}

/**
 * One view model behind the whole reference section — the hub, the tense guide, and the conjugation
 * tables, whether those are reached through the hub or straight from the drill's shortcut.
 *
 * It resolves the profile itself rather than taking a language through a route argument, so a topic
 * screen can be deep-linked without the caller knowing what it will show. Resolution runs off the
 * main thread: `specFor` touches the lazy lexicon, which parses a few thousand generated entries the
 * first time it is asked.
 */
@HiltViewModel
class ReferenceViewModel @Inject constructor(
    repo: ConjugationDrillRepository,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    val ui: StateFlow<ReferenceUi> = settingsRepo.settings
        .map { it.targetLanguage }
        .distinctUntilChanged()
        .map { ReferenceUi(spec = repo.specFor(it), resolved = true) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReferenceUi())
}
