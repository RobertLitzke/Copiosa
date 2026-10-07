package com.copiosa.ui.vocab

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.remote.LlmError
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.vocab.BulkCardCreator
import com.copiosa.data.vocab.CardDraft
import com.copiosa.domain.prompt.BulkCards
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One drafted card in the review list, with whether the learner has left it ticked. */
data class DraftRow(val draft: CardDraft, val included: Boolean)

/** Which step of the bulk add the sheet is showing. */
enum class BulkAddStep { INPUT, DRAFTING, REVIEW, SAVING }

data class BulkAddState(
    val step: BulkAddStep = BulkAddStep.INPUT,
    /** What is in the box, kept so stepping back from the review returns to it. */
    val input: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val rows: List<DraftRow> = emptyList(),
    val untranslated: List<String> = emptyList(),
    val onDevice: Int = 0,
    val llmError: LlmError? = null,
    /** The last run drafted nothing at all — shown on the input step, beside [llmError] if any. */
    val failed: Boolean = false,
) {
    val includedCount: Int get() = rows.count { it.included }
}

/** The active profile's two language tags, for the sheet's copy. */
data class BulkAddLanguages(val native: String, val target: String)

/**
 * Drives the bulk-add sheet: type a list, review the drafted cards, confirm.
 *
 * The state outlives the sheet on purpose. A bottom sheet is dismissed by a stray downward drag, and
 * the drafts cost a model call — so closing it keeps them, and only a confirmed add (or an explicit
 * start-over) clears the slate.
 */
@HiltViewModel
class BulkAddViewModel @Inject constructor(
    private val creator: BulkCardCreator,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(BulkAddState())
    val state: StateFlow<BulkAddState> = _state.asStateFlow()

    /**
     * How many cards the last confirm saved, until [consumeAdded]. Its own flow rather than a field
     * of [state] because the screen behind the sheet announces it, and that screen has no business
     * recomposing on every keystroke in the input box.
     */
    private val _added = MutableStateFlow<Int?>(null)
    val added: StateFlow<Int?> = _added.asStateFlow()

    val languages: StateFlow<BulkAddLanguages> =
        settingsRepo.settings
            .map { BulkAddLanguages(native = it.nativeLanguage, target = it.targetLanguage) }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BulkAddLanguages("en", "es"))

    fun setInput(text: String) {
        _state.update { it.copy(input = text, failed = false, llmError = null) }
    }

    /** Sends the typed list off to be drafted; lands on the review step, or back on input if nothing came of it. */
    fun draft() {
        val current = _state.value
        if (current.step != BulkAddStep.INPUT) return
        val items = BulkCards.splitInput(current.input)
        if (items.isEmpty()) return
        _state.value = current.copy(step = BulkAddStep.DRAFTING, done = 0, total = items.size, failed = false, llmError = null)
        viewModelScope.launch {
            val result = creator.draft(items) { done, total ->
                _state.update { it.copy(done = done, total = total) }
            }
            _state.update {
                if (result.drafts.isEmpty()) {
                    it.copy(step = BulkAddStep.INPUT, failed = true, llmError = result.llmError)
                } else {
                    it.copy(
                        step = BulkAddStep.REVIEW,
                        // A word the deck already teaches starts unticked: adding it again is
                        // sometimes wanted (a second sense), but never by default.
                        rows = result.drafts.map { draft -> DraftRow(draft, included = !draft.duplicate) },
                        untranslated = result.untranslated,
                        onDevice = result.onDevice,
                        llmError = result.llmError,
                    )
                }
            }
        }
    }

    fun setIncluded(index: Int, included: Boolean) = updateRow(index) { it.copy(included = included) }

    /** Applies the learner's correction to a draft before it is saved. */
    fun edit(index: Int, term: String, translation: String) = updateRow(index) {
        it.copy(draft = it.draft.copy(term = term, translation = translation))
    }

    private fun updateRow(index: Int, change: (DraftRow) -> DraftRow) {
        _state.update { s ->
            if (index !in s.rows.indices) s
            else s.copy(rows = s.rows.toMutableList().also { it[index] = change(it[index]) })
        }
    }

    /** Leaves the review for the input box, keeping what was typed so it can be amended and re-run. */
    fun backToInput() {
        _state.update {
            if (it.step == BulkAddStep.REVIEW) BulkAddState(input = it.input) else it
        }
    }

    /** Saves the ticked drafts. */
    fun confirm() {
        val current = _state.value
        if (current.step != BulkAddStep.REVIEW) return
        val chosen = current.rows.filter { it.included }.map { it.draft }
        if (chosen.isEmpty()) return
        _state.value = current.copy(step = BulkAddStep.SAVING)
        viewModelScope.launch {
            _added.value = creator.add(chosen)
            _state.value = BulkAddState()
        }
    }

    fun consumeAdded() {
        _added.value = null
    }
}
