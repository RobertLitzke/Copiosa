package com.copiosa.ui.cloze

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.cloze.ClozeMineResult
import com.copiosa.data.cloze.ClozeRepository
import com.copiosa.data.cloze.ClozeSource
import com.copiosa.data.cloze.sources.BookSentenceSource
import com.copiosa.data.cloze.sources.ContextSentenceSource
import com.copiosa.data.cloze.sources.DictionaryExampleSource
import com.copiosa.data.local.ClozeSentenceEntity
import com.copiosa.domain.cloze.ClozeSessionItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The screen outside a sitting: how big the bank is, and what the learner has set aside. */
data class ClozeHomeUi(
    val loading: Boolean = true,
    val lang: String = "",
    val bankSize: Int = 0,
    val stocking: Boolean = false,
    /** What the last on-demand stocking run did, so the button can account for itself. */
    val lastStock: ClozeMineResult? = null,
    val flagged: List<ClozeSentenceEntity> = emptyList(),
    /** The last Start found sentences but none it could build answer options for. */
    val noneUsable: Boolean = false,
) {
    val canStart: Boolean get() = bankSize > 0
}

/** A running or finished sitting. */
data class ClozeSessionUi(
    val items: List<ClozeSessionItem>,
    val answered: Int = 0,
    val correct: Int = 0,
    val finished: Boolean = false,
    /** Words missed this sitting, for the results pane to send the learner back to. */
    val missed: List<String> = emptyList(),
)

@HiltViewModel
class ClozeViewModel @Inject constructor(
    private val repo: ClozeRepository,
    private val contextSentences: ContextSentenceSource,
    private val dictionaryExamples: DictionaryExampleSource,
    private val bookSentences: BookSentenceSource,
) : ViewModel() {

    private val _home = MutableStateFlow(ClozeHomeUi())
    val home: StateFlow<ClozeHomeUi> = _home.asStateFlow()

    private val _session = MutableStateFlow<ClozeSessionUi?>(null)
    val session: StateFlow<ClozeSessionUi?> = _session.asStateFlow()

    /**
     * Cards already credited this sitting.
     *
     * The bank holds up to three sentences per word, so without this a single sitting could nudge
     * one card three times — three quarters of a review for one word. Mirrors how
     * `ReadingSelectionController` guards passive review while reading.
     */
    private val nudged = mutableSetOf<Long>()

    init {
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val lang = repo.activeLanguage()
        _home.value = _home.value.copy(loading = false, lang = lang)
        viewModelScope.launch {
            repo.observeActiveCount(lang).collect { n -> _home.value = _home.value.copy(bankSize = n) }
        }
        viewModelScope.launch {
            repo.observeFlagged(lang).collect { rows -> _home.value = _home.value.copy(flagged = rows) }
        }
    }

    /**
     * Fills the bank from the deck's own example sentences.
     *
     * Run on demand from the setup pane rather than left to the background pass, because a bank that
     * is only ever stocked in the background means the first tap on this screen shows nothing and
     * reads as broken. The sources used here are local, so this costs no network and no tokens.
     */
    fun stock() {
        if (_home.value.stocking) return
        viewModelScope.launch {
            _home.value = _home.value.copy(stocking = true, lastStock = null, noneUsable = false)
            try {
                val lang = _home.value.lang.ifEmpty { repo.activeLanguage() }
                val ctx = repo.buildContext(lang)
                val result = repo.mine(localSources(), ctx, limitPerSource = STOCK_PER_RUN)
                _home.value = _home.value.copy(lastStock = result)
            } finally {
                _home.value = _home.value.copy(stocking = false)
            }
        }
    }

    /** The same local sources the background pass uses — no network, no model, no tokens. */
    private fun localSources(): List<ClozeSource> =
        listOf(contextSentences, dictionaryExamples, bookSentences)

    fun startSession() {
        viewModelScope.launch {
            val lang = _home.value.lang.ifEmpty { repo.activeLanguage() }
            val items = repo.session(lang, SESSION_SIZE)
            nudged.clear()
            // A sentence with too few wrong options to offer is dropped rather than padded (see
            // ClozeRepository.session), so a small bank can come back empty; the pane says so.
            _home.value = _home.value.copy(noneUsable = items.isEmpty())
            _session.value = if (items.isEmpty()) null else ClozeSessionUi(items = items)
        }
    }

    fun onAnswer(index: Int, correct: Boolean, latencyMs: Long) {
        val current = _session.value ?: return
        val item = current.items.getOrNull(index) ?: return
        _session.value = current.copy(
            answered = current.answered + 1,
            correct = current.correct + if (correct) 1 else 0,
            missed = if (correct) current.missed else current.missed + item.answer,
        )
        // One nudge per card per sitting; the sentence's own schedule always moves.
        val shouldNudge = item.cardId != null && nudged.add(item.cardId)
        viewModelScope.launch { repo.record(item, correct, latencyMs, nudgeCard = shouldNudge) }
    }

    fun onSessionComplete() {
        _session.value = _session.value?.copy(finished = true)
    }

    fun dismissSession() {
        _session.value = null
    }

    // --- throwing an item out ------------------------------------------------

    fun flagAmbiguous(index: Int) {
        val id = _session.value?.items?.getOrNull(index)?.sentenceId ?: return
        viewModelScope.launch { repo.flagAmbiguous(id) }
    }

    fun flagWrong(index: Int) {
        val id = _session.value?.items?.getOrNull(index)?.sentenceId ?: return
        viewModelScope.launch { repo.flagWrong(id) }
    }

    fun unflag(id: Long) {
        viewModelScope.launch { repo.unflag(id) }
    }

    fun deleteFlagged(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    private companion object {
        const val SESSION_SIZE = 15

        /** Sentences one on-demand stocking pass may add — enough for several sittings, not a stall. */
        const val STOCK_PER_RUN = 60
    }
}
