package com.copiosa.ui.listening

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.cloze.ClozeRepository
import com.copiosa.data.listening.ListenRebuildRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.listening.RebuildItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The screen outside a sitting. */
data class RebuildHomeUi(
    val loading: Boolean = true,
    val lang: String = "",
    val bankSize: Int = 0,
    val voice: TtsModelState = TtsModelState.NotDownloaded,
    /** Picking sentences and rendering the first clip. */
    val preparing: Boolean = false,
    /** The last start found no banked sentence of a tappable length. */
    val noneFit: Boolean = false,
) {
    val voiceReady: Boolean get() = voice is TtsModelState.Ready
}

/** A running or finished sitting. */
data class RebuildSessionUi(
    val items: List<RebuildItem>,
    val answered: Int = 0,
    val firstTime: Int = 0,
    val finished: Boolean = false,
)

@HiltViewModel
class ListenRebuildViewModel @Inject constructor(
    private val repo: ListenRebuildRepository,
    private val clozeRepo: ClozeRepository,
    private val modelStore: SupertonicModelStore,
) : ViewModel() {

    private val _home = MutableStateFlow(RebuildHomeUi())
    val home: StateFlow<RebuildHomeUi> = _home.asStateFlow()

    private val _session = MutableStateFlow<RebuildSessionUi?>(null)
    val session: StateFlow<RebuildSessionUi?> = _session.asStateFlow()

    /** Renders the sitting's remaining clips while the learner works on the first. */
    private var renderJob: Job? = null

    init {
        viewModelScope.launch {
            val lang = clozeRepo.activeLanguage()
            _home.update { it.copy(loading = false, lang = lang) }
            launch { clozeRepo.observeActiveCount(lang).collect { n -> _home.update { it.copy(bankSize = n) } } }
        }
        viewModelScope.launch { modelStore.state.collect { s -> _home.update { it.copy(voice = s) } } }
    }

    fun downloadVoice() = modelStore.download()

    /**
     * Starts a sitting as soon as its *first* clip exists, and renders the rest behind it. Rendering
     * all of them up front would put several seconds of spinner between the tap and the first word;
     * one clip is about the time it takes the screen to change.
     */
    fun start() {
        if (_home.value.preparing) return
        renderJob?.cancel()
        viewModelScope.launch {
            _home.update { it.copy(preparing = true, noneFit = false) }
            try {
                val lang = _home.value.lang.ifEmpty { clozeRepo.activeLanguage() }
                val picked = repo.pick(lang, SESSION_SIZE)
                // The first clip is the one the learner waits on, so its failure is worth a second
                // sentence's try rather than an empty sitting.
                val first = picked.withIndex().firstNotNullOfOrNull { (i, item) ->
                    render(item, lang)?.let { i to it }
                }
                if (first == null) {
                    _home.update { it.copy(noneFit = true) }
                    return@launch
                }
                val items = listOf(first.second) + picked.drop(first.first + 1)
                _session.value = RebuildSessionUi(items = items)
                renderJob = viewModelScope.launch { renderRest(items, lang) }
            } finally {
                _home.update { it.copy(preparing = false) }
            }
        }
    }

    /**
     * Fills in the clips after the first, one at a time and in order, so the next sentence is always
     * the next one rendered. A clip that fails stays missing; that item's play button keeps spinning
     * and Skip or Show answer moves past it.
     */
    private suspend fun renderRest(items: List<RebuildItem>, lang: String) {
        for (i in 1 until items.size) {
            val rendered = render(items[i], lang) ?: continue
            _session.update { s ->
                if (s == null || s.items.size != items.size) s
                else s.copy(items = s.items.toMutableList().also { it[i] = rendered })
            }
        }
    }

    private suspend fun render(item: RebuildItem, lang: String): RebuildItem? = try {
        repo.withClip(item, lang)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Clip failed for sentence ${item.sentenceId}", e)
        null
    }

    fun onAnswer(correct: Boolean) {
        _session.update { s ->
            s?.copy(answered = s.answered + 1, firstTime = s.firstTime + if (correct) 1 else 0)
        }
    }

    fun onSessionComplete() {
        renderJob?.cancel()
        _session.update { it?.copy(finished = true) }
    }

    fun dismissSession() {
        renderJob?.cancel()
        _session.value = null
    }

    private companion object {
        const val TAG = "ListenRebuild"
        const val SESSION_SIZE = 8
    }
}
