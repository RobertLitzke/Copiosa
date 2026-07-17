package com.copiosa.ui.flashcards

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.anki.AnkiExportService
import com.copiosa.data.anki.AnkiImportService
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.domain.srs.ReviewWindows
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Due-date filter for the vocabulary list: everything, or only cards coming up within a window. */
enum class DueWindow(val windowMs: Long?) {
    ALL(null),
    DAY(ReviewWindows.DAY_MS),
    WEEK(7 * ReviewWindows.DAY_MS),
}

/** Progress of the batched "fill explanations" action. */
data class FillState(val running: Boolean = false, val done: Int = 0, val total: Int = 0)

/** State of an Anki import: in flight, finished (with a [summary]), or failed. */
data class ImportState(
    val running: Boolean = false,
    val summary: AnkiImportService.Summary? = null,
    val error: String? = null,
)

/** State of an Anki export: in flight, finished (with a [summary]), or failed. */
data class ExportState(
    val running: Boolean = false,
    val summary: AnkiExportService.Summary? = null,
    val error: String? = null,
)

@HiltViewModel
class FlashcardsViewModel @Inject constructor(
    private val repo: FlashcardRepository,
    private val importService: AnkiImportService,
    private val exportService: AnkiExportService,
) : ViewModel() {

    val due: StateFlow<List<FlashcardEntity>> =
        repo.observeDue().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val all: StateFlow<List<FlashcardEntity>> =
        repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _markedOnly = MutableStateFlow(false)
    val markedOnly: StateFlow<Boolean> = _markedOnly.asStateFlow()

    private val _dueWindow = MutableStateFlow(DueWindow.ALL)
    val dueWindow: StateFlow<DueWindow> = _dueWindow.asStateFlow()

    /**
     * The list filtered by the search box (front + back, case-insensitive), the "marked only" toggle,
     * and the due-window selector. The due-window excludes frozen (suspended) cards, matching the
     * review queue's notion of "coming up" (a frozen card isn't scheduled to be seen).
     */
    val filtered: StateFlow<List<FlashcardEntity>> =
        combine(all, _query, _markedOnly, _dueWindow) { cards, q, markedOnly, window ->
            val needle = q.trim()
            val maxDue = window.windowMs?.let { System.currentTimeMillis() + it }
            cards.filter { card ->
                (needle.isEmpty() ||
                    card.front.contains(needle, ignoreCase = true) ||
                    card.back.contains(needle, ignoreCase = true)) &&
                    (!markedOnly || card.marked) &&
                    (maxDue == null || (!card.suspended && card.dueAt <= maxDue))
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _fill = MutableStateFlow(FillState())
    val fill: StateFlow<FillState> = _fill.asStateFlow()

    private val _error = MutableStateFlow<LlmError?>(null)
    val error: StateFlow<LlmError?> = _error.asStateFlow()

    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import.asStateFlow()

    private val _export = MutableStateFlow(ExportState())
    val export: StateFlow<ExportState> = _export.asStateFlow()

    fun setQuery(q: String) { _query.value = q }

    fun setMarkedOnly(markedOnly: Boolean) { _markedOnly.value = markedOnly }

    fun setDueWindow(window: DueWindow) { _dueWindow.value = window }

    /** Freezes/unfreezes a card from the list's long-press menu. */
    fun setSuspended(card: FlashcardEntity, suspended: Boolean) {
        viewModelScope.launch { repo.setSuspended(card.id, suspended) }
    }

    fun delete(card: FlashcardEntity) {
        viewModelScope.launch { repo.delete(card.id) }
    }

    /** Imports the picked `.apkg`, preserving each card's Anki schedule + history. */
    fun importAnki(uri: Uri) {
        if (_import.value.running) return
        _import.value = ImportState(running = true)
        viewModelScope.launch {
            _import.value = try {
                ImportState(summary = importService.import(uri))
            } catch (e: Exception) {
                ImportState(error = e.message ?: "Import failed")
            }
        }
    }

    fun consumeImport() {
        _import.value = ImportState()
    }

    /** Exports the whole vocabulary to the chosen `.apkg`, preserving each card's schedule + history. */
    fun exportAnki(uri: Uri) {
        if (_export.value.running) return
        _export.value = ExportState(running = true)
        viewModelScope.launch {
            _export.value = try {
                ExportState(summary = exportService.export(uri))
            } catch (e: Exception) {
                ExportState(error = e.message ?: "Export failed")
            }
        }
    }

    fun consumeExport() {
        _export.value = ExportState()
    }

    /** Bundles every card missing an explanation into batched LLM calls and saves the results. */
    fun fillExplanations() {
        if (_fill.value.running) return
        _fill.value = FillState(running = true)
        viewModelScope.launch {
            try {
                repo.fillExplanations { done, total -> _fill.value = FillState(running = true, done = done, total = total) }
            } catch (e: LlmException) {
                _error.value = e.error
            } catch (e: Exception) {
                _error.value = LlmError.Unknown(e.message ?: "error")
            } finally {
                _fill.value = FillState()
            }
        }
    }

    fun consumeError() { _error.value = null }
}
