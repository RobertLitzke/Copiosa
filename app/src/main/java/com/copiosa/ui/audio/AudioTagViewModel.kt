package com.copiosa.ui.audio

import androidx.lifecycle.SavedStateHandle
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * One folder's tracks. Ordering here is [AudioItemEntity.sortOrder] alone — unlike the main queue,
 * played tracks do not sink, because a book has to stay in chapter order to be listenable at all.
 */
@HiltViewModel
class AudioTagViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: AudioRepository,
    private val playback: PlaybackController,
    private val modelStore: SupertonicModelStore,
    private val audioGen: AudioGenerationManager,
) : ViewModel() {

    val tag: String = savedStateHandle.get<String>("tag").orEmpty()

    val tracks: StateFlow<List<AudioItemEntity>> =
        repo.observeTag(tag).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val modelState: StateFlow<TtsModelState> = modelStore.state

    val playbackState: StateFlow<PlaybackUiState> = playback.state

    /** Where "Continue" lands: the first unheard track, or the start once the folder is finished. */
    fun continueTrack(): AudioItemEntity? {
        val ready = readyTracks()
        return ready.firstOrNull { !it.played } ?: ready.firstOrNull()
    }

    fun playFrom(id: Long) = playback.play(readyTracks(), id)

    fun playContinue() = continueTrack()?.let { playFrom(it.id) }

    fun playFromStart() = readyTracks().firstOrNull()?.let { playFrom(it.id) }

    fun onPlayPause() {
        if (playbackState.value.currentId == null) playContinue() else playback.togglePlayPause()
    }

    fun next() = playback.next()

    fun previous() = playback.previous()

    fun markUnplayed(id: Long) = viewModelScope.launch { repo.markUnplayed(id) }

    fun delete(id: Long) = viewModelScope.launch { repo.delete(id) }

    /** Deletes the whole folder — rows and files. The screen confirms first. */
    fun deleteTag() = viewModelScope.launch { repo.deleteTag(tag) }

    fun retry(id: Long) = audioGen.retry(id)

    fun downloadModel() = modelStore.download()

    private fun readyTracks(): List<AudioItemEntity> =
        tracks.value.filter { it.status == AudioStatus.READY }
}
