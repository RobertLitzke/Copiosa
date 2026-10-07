package com.copiosa.ui.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.generation.AudioGenerationManager
import com.copiosa.data.local.AudioItemEntity
import com.copiosa.data.repository.AudioRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.model.AudioStatus
import com.copiosa.playback.PlaybackController
import com.copiosa.playback.PlaybackUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioViewModel @Inject constructor(
    private val repo: AudioRepository,
    private val playback: PlaybackController,
    private val modelStore: SupertonicModelStore,
    private val audioGen: AudioGenerationManager,
) : ViewModel() {

    val items: StateFlow<List<AudioItemEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The same queue with tagged items collapsed into folders — what the list actually renders. */
    val entries: StateFlow<List<AudioListEntry>> = items
        .map(::groupByTag)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val modelState: StateFlow<TtsModelState> = modelStore.state

    val playbackState: StateFlow<PlaybackUiState> = playback.state

    /** Transport play/pause: starts the playlist on first press, then toggles. */
    fun onPlayPause() {
        if (playbackState.value.currentId == null) playAll() else playback.togglePlayPause()
    }

    fun playAll() {
        val ready = readyItems()
        val start = ready.firstOrNull { !it.played } ?: ready.firstOrNull()
        playback.play(ready, start?.id)
    }

    fun play(id: Long) = playback.play(readyItems(), id)

    /**
     * The loose queue only: a folder's tracks play from the folder screen, in their own order.
     * Playing a whole book from a tap on a chat reply would be a surprising amount of audio.
     */
    private fun readyItems(): List<AudioItemEntity> =
        items.value.filter { it.status == AudioStatus.READY && it.tag == null }

    fun next() = playback.next()

    fun previous() = playback.previous()

    fun markUnplayed(id: Long) = viewModelScope.launch { repo.markUnplayed(id) }

    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }

    fun deleteAllPlayed() = viewModelScope.launch { repo.deleteAllPlayed() }

    fun retry(id: Long) = audioGen.retry(id)

    fun downloadModel() = modelStore.download()

    /** Persists the dragged order of the unplayed group. */
    fun persistOrder(orderedUnplayedIds: List<Long>) =
        viewModelScope.launch { repo.persistUnplayedOrder(orderedUnplayedIds) }
}
