package com.copiosa.ui.flashcards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.repository.ImageCandidate
import com.copiosa.data.repository.ImageSearchRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Walking the imageless-card backlog: the current card and the user's position in the fixed list. */
data class VocabImagesState(
    val loading: Boolean = true,
    val current: FlashcardEntity? = null,
    val position: Int = 0,
    val total: Int = 0,
)

/**
 * Drives the "Add images" stepping flow: a one-time snapshot of active lexical cards that still have
 * no image (newest first), advanced one at a time as the user picks an image or skips.
 */
@HiltViewModel
class VocabImagesViewModel @Inject constructor(
    private val repo: FlashcardRepository,
    private val imageRepo: ImageSearchRepository,
) : ViewModel() {

    private val cards = MutableStateFlow<List<FlashcardEntity>>(emptyList())
    private val index = MutableStateFlow(0)
    private val loading = MutableStateFlow(true)
    private var started = false

    val state: StateFlow<VocabImagesState> =
        combine(cards, index, loading) { list, i, isLoading ->
            VocabImagesState(
                loading = isLoading,
                current = list.getOrNull(i),
                position = (i + 1).coerceAtMost(list.size),
                total = list.size,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VocabImagesState())

    fun start() {
        if (started) return
        started = true
        viewModelScope.launch {
            cards.value = repo.imagelessCards()
            loading.value = false
        }
    }

    /** Advances to the next card (after attaching an image, or on Skip). */
    fun next() {
        index.value = index.value + 1
    }

    suspend fun searchImages(query: String): List<ImageCandidate> = imageRepo.search(query)

    suspend fun attachImage(candidate: ImageCandidate): String {
        val card = cards.value.getOrNull(index.value) ?: error("No current card")
        return imageRepo.attachImage(card.id, candidate)
    }
}
