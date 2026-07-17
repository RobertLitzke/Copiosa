package com.copiosa.ui.batch

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.generation.GenerationManager
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Bridges the global batch-entry sheet (rendered in the nav scaffold, outside the screen VMs) to the
 * [GenerationManager]. The manager owns the work, so the batch keeps running regardless of this VM.
 */
@HiltViewModel
class BatchViewModel @Inject constructor(
    private val generation: GenerationManager,
    settingsRepo: SettingsRepository,
) : ViewModel() {
    /** Current defaults (model/level/country) the batch will run with, shown in the sheet header. */
    val settings: StateFlow<AppSettings?> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startChatBatch(topics: List<String>) = generation.startChatBatch(topics)
}
