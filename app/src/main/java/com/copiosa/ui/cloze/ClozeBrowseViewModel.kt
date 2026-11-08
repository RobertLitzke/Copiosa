package com.copiosa.ui.cloze

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.cloze.ClozeRepository
import com.copiosa.data.local.ClozeSentenceEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The bank as a list: search it, keep a sentence, fix one, throw one out.
 *
 * Until this existed the only sentences a learner could see outside a sitting were the ones they
 * had flagged, which made the bank something that happened to them rather than something they own.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class ClozeBrowseViewModel @Inject constructor(
    private val repo: ClozeRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    /** False after an edit the bank refused — the answer missing, or the sentence already banked. */
    private val _editRefused = MutableStateFlow(false)
    val editRefused: StateFlow<Boolean> = _editRefused.asStateFlow()

    val sentences: StateFlow<List<ClozeSentenceEntity>> =
        _query.debounce(200)
            .flatMapLatest { q -> flow { emitAll(repo.observeBrowse(repo.activeLanguage(), q)) } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setQuery(q: String) { _query.value = q }

    fun togglePinned(row: ClozeSentenceEntity) {
        viewModelScope.launch { repo.setPinned(row.id, !row.pinned) }
    }

    /** Sets a sentence aside, or brings one back. Set aside rather than deleted — see `flagWrong`. */
    fun toggleSetAside(row: ClozeSentenceEntity) {
        viewModelScope.launch { if (row.suspended) repo.unflag(row.id) else repo.flagWrong(row.id) }
    }

    fun delete(row: ClozeSentenceEntity) {
        viewModelScope.launch { repo.delete(row.id) }
    }

    fun edit(row: ClozeSentenceEntity, sentence: String, translation: String) {
        viewModelScope.launch { _editRefused.value = !repo.edit(row.id, sentence, translation) }
    }

    fun consumeEditRefused() { _editRefused.value = false }
}
