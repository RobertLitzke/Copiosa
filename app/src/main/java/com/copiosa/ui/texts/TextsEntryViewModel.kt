package com.copiosa.ui.texts

import androidx.lifecycle.ViewModel
import com.copiosa.data.generation.GenerationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * Bridges the global text-entry sheet (rendered in the nav scaffold, outside the screen VMs) to the
 * [GenerationManager]. Creating the draft is quick and returns the new id so the caller can navigate
 * straight to the reader; extraction + translation then run in the manager's app-lifetime scope.
 */
@HiltViewModel
class TextsEntryViewModel @Inject constructor(
    private val generation: GenerationManager,
) : ViewModel() {
    suspend fun importUrl(url: String): Long = generation.importUrl(url)
    suspend fun importText(text: String): Long = generation.importText(text)
}
