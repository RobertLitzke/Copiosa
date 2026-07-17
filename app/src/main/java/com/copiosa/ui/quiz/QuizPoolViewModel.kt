package com.copiosa.ui.quiz

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.quiz.PoolProgress
import com.copiosa.data.quiz.PoolSeedState
import com.copiosa.data.quiz.QuizAudioGenerationManager
import com.copiosa.data.quiz.QuizPoolRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.quiz.QuizPoolConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class QuizPoolUi(
    val loading: Boolean = true,
    val lang: String = "",
    val modelReady: Boolean = false,
    val modelDownloading: Boolean = false,
    val progress: PoolProgress? = null,
    val seedState: PoolSeedState = PoolSeedState.Idle,
    val diskUsageBytes: Long = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class QuizPoolViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val poolRepo: QuizPoolRepository,
    private val generator: QuizAudioGenerationManager,
    private val settingsRepo: SettingsRepository,
    private val modelStore: SupertonicModelStore,
) : ViewModel() {

    /** The single cue type this pool screen manages (Numbers or Times), from the nav route. */
    val cueType: CueType = savedStateHandle.get<String>("type")
        ?.let { runCatching { CueType.valueOf(it) }.getOrNull() } ?: CueType.NUMBER

    val state: StateFlow<QuizPoolUi> =
        settingsRepo.activeProfileTag.flatMapLatest { lang ->
            combine(
                modelStore.state,
                poolRepo.observeProgress(lang, cueType),
                generator.seedState,
            ) { model, progress, seed ->
                QuizPoolUi(
                    loading = false,
                    lang = lang,
                    modelReady = model is TtsModelState.Ready,
                    modelDownloading = model is TtsModelState.Downloading,
                    progress = progress,
                    seedState = seed,
                    // Cheap directory listing, recomputed when any count changes.
                    diskUsageBytes = poolRepo.diskUsageBytes(),
                )
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), QuizPoolUi())

    fun downloadModel() = modelStore.download()

    fun buildPool(config: QuizPoolConfig) {
        viewModelScope.launch {
            val lang = settingsRepo.activeProfileTag.first()
            generator.buildPool(config.copy(langCode = lang, cueType = cueType))
        }
    }

    fun deletePool() {
        viewModelScope.launch {
            generator.deletePool(settingsRepo.activeProfileTag.first(), cueType)
        }
    }

    fun retryFailed() {
        viewModelScope.launch {
            generator.retryFailed(settingsRepo.activeProfileTag.first(), cueType)
        }
    }
}
