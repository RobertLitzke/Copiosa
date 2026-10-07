package com.copiosa.ui.vocab

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.anki.AnkiExportService
import com.copiosa.data.anki.AnkiImportService
import com.copiosa.data.cardjson.CardJsonExportService
import com.copiosa.data.cardjson.CardJsonImportPlan
import com.copiosa.data.cardjson.CardJsonImportService
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.noteKey
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.cloze.LegacyClozeConverter
import com.copiosa.domain.cloze.ClozeConversionPlan
import com.copiosa.data.vocab.FrequencySeeder
import com.copiosa.data.vocab.SeedError
import com.copiosa.data.vocab.SeedResult
import com.copiosa.data.vocab.SeedSummary
import com.copiosa.domain.srs.ReviewWindows
import com.copiosa.domain.vocab.freq.FrequencyList
import com.copiosa.domain.vocab.freq.FrequencyLists
import com.copiosa.domain.vocab.freq.VocabCoverage
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.Collator
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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

/** State of a JSON export: in flight, finished (with the [count] of cards written), or failed. */
data class JsonExportState(
    val running: Boolean = false,
    val count: Int? = null,
    val error: String? = null,
)

/**
 * State of a JSON import: reading the file, waiting on the learner to agree to a [plan], writing it,
 * finished (with a [summary]), or failed.
 */
data class JsonImportState(
    val running: Boolean = false,
    val plan: CardJsonImportPlan? = null,
    val summary: CardJsonImportService.Summary? = null,
    val error: String? = null,
)

/** Progress and outcome of the frequency-list seeding action. */
data class SeedState(
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val summary: SeedSummary? = null,
    val error: SeedError? = null,
    /** Set instead of [summary] when the last action was a removal rather than a seed. */
    val removed: Int? = null,
)

/**
 * The card that stands for its note in a list: the one that will come up next. That is the soonest
 * due of the note's unfrozen cards, so the row's dot and due label answer "when do I see this word
 * again" rather than describing one direction arbitrarily.
 *
 * A note reads as frozen only when every one of its cards is — the returned row carries that, not
 * the lead card's own flag, which is what lets the list dim a word set aside whole while still
 * showing one that merely has a direction parked.
 */
internal fun leadCard(cards: List<FlashcardEntity>): FlashcardEntity {
    val active = cards.filterNot { it.suspended }
    val lead = active.minByOrNull { it.dueAt } ?: cards.first()
    return lead.copy(suspended = active.isEmpty())
}

@HiltViewModel
class VocabViewModel @Inject constructor(
    private val repo: FlashcardRepository,
    private val importService: AnkiImportService,
    private val exportService: AnkiExportService,
    private val jsonExportService: CardJsonExportService,
    private val jsonImportService: CardJsonImportService,
    private val seeder: FrequencySeeder,
    private val clozeConverter: LegacyClozeConverter,
    private val coverage: VocabCoverage,
    private val settingsRepo: SettingsRepository,
) : ViewModel() {

    /** Today's reviews, as the Review button counts them: see [FlashcardRepository.observeDueToday]. */
    val dueCount: StateFlow<Int> =
        repo.observeDueCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /**
     * The vocabulary as the list shows it: **one row per note**, not per card. A word studied both
     * ways is one word, so it is counted, searched and listed once. Each row is the note's
     * [leadCard] — see there for which card speaks for it.
     */
    val all: StateFlow<List<FlashcardEntity>> =
        repo.observeAll()
            .map { cards -> cards.groupBy { it.noteKey }.values.map(::leadCard) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(VocabFilter())

    /** What is narrowing the list right now: the search text and every filter. */
    val filter: StateFlow<VocabFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(VocabSort.NEWEST)
    val sort: StateFlow<VocabSort> = _sort.asStateFlow()

    private val _fill = MutableStateFlow(FillState())
    val fill: StateFlow<FillState> = _fill.asStateFlow()

    private val _error = MutableStateFlow<LlmError?>(null)
    val error: StateFlow<LlmError?> = _error.asStateFlow()

    private val _import = MutableStateFlow(ImportState())
    val import: StateFlow<ImportState> = _import.asStateFlow()

    private val _export = MutableStateFlow(ExportState())
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private val _jsonExport = MutableStateFlow(JsonExportState())
    val jsonExport: StateFlow<JsonExportState> = _jsonExport.asStateFlow()

    private val _jsonImport = MutableStateFlow(JsonImportState())
    val jsonImport: StateFlow<JsonImportState> = _jsonImport.asStateFlow()

    /** The long-running actions in flight, kept so their progress dialogs can cancel them. */
    private var importJob: Job? = null
    private var jsonImportJob: Job? = null
    private var exportJob: Job? = null
    private var jsonExportJob: Job? = null
    private var seedJob: Job? = null

    private val _seed = MutableStateFlow(SeedState())
    val seed: StateFlow<SeedState> = _seed.asStateFlow()

    /**
     * How many of the active profile's cards the frequency seeder made. Counted off the observed deck
     * rather than queried, so it drops to zero the moment the removal lands and the menu item hides
     * itself without a refresh.
     */
    /** The pending conversion plan, non-null while its confirmation dialog is up. */
    private val _clozeConversion = MutableStateFlow<ClozeConversionPlan?>(null)
    val clozeConversion: StateFlow<ClozeConversionPlan?> = _clozeConversion.asStateFlow()

    /** How many sentences the last conversion created, for the confirmation toast. */
    private val _clozeConverted = MutableStateFlow<Int?>(null)
    val clozeConverted: StateFlow<Int?> = _clozeConverted.asStateFlow()

    val autoAddedCount: StateFlow<Int> =
        all.map { cards -> cards.count { it.autoSource == FrequencySeeder.SOURCE } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The active profile's frequency list, or null for a language that ships none. */
    private val frequencyList: StateFlow<FrequencyList?> =
        settingsRepo.activeProfileTag
            .map { FrequencyLists.forTag(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Whether the frequency actions and chips apply at all to the language being studied. */
    val hasFrequencyList: StateFlow<Boolean> =
        frequencyList.map { it != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Frequency rank per card id, for the list's band chips. Recomputed with the deck rather than
     * stored on the card, so it stays right after an edit changes a headword and needs no migration
     * when a list is regenerated.
     */
    val ranks: StateFlow<Map<Long, Int>> =
        combine(all, frequencyList) { cards, list ->
            list?.let { coverage.ranksByCard(cards, it) }.orEmpty()
        }
            // Off the main thread: matching walks every card's cached form set, and a large imported
            // deck would otherwise do that work on the UI thread on every edit.
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // Declared after [ranks] on purpose: property initializers run in order, and the list reads it.
    /** The part-of-speech keys the deck actually has, for the filter to offer. */
    val partsOfSpeech: StateFlow<List<String>> =
        all.map(::partsOfSpeechIn).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The list as shown: [all] narrowed by [filter], ordered by [sort] — see [listVocab]. */
    val filtered: StateFlow<List<FlashcardEntity>> =
        combine(all, _filter, _sort, ranks) { cards, filter, sort, ranks ->
            val collator = Collator.getInstance(Locale.forLanguageTag(cards.firstOrNull()?.targetLanguage ?: "es"))
            listVocab(cards, filter, sort, System.currentTimeMillis(), ranks, collator)
        }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Peel any crammed usage note out of the translation field for older cards, once.
        viewModelScope.launch { repo.ensureNotesBackfilled() }
    }

    fun setQuery(q: String) = _filter.update { it.copy(query = q) }

    fun setMarkedOnly(markedOnly: Boolean) = _filter.update { it.copy(markedOnly = markedOnly) }

    fun setDueWindow(window: DueWindow) = _filter.update { it.copy(dueWindow = window) }

    fun setStatus(status: CardStatus?) = _filter.update { it.copy(status = status) }

    fun setPartOfSpeech(pos: String?) = _filter.update { it.copy(partOfSpeech = pos) }

    fun setExplained(explained: ExplainedFilter) = _filter.update { it.copy(explained = explained) }

    fun setSort(sort: VocabSort) { _sort.value = sort }

    /** Drops every filter and the search text; the sort order is the learner's choice and stays. */
    fun clearFilters() { _filter.value = VocabFilter() }

    /**
     * Freezes/unfreezes a whole word from the list's long-press menu — every direction of it. One
     * direction on its own is frozen from the word's detail screen.
     */
    fun setSuspended(card: FlashcardEntity, suspended: Boolean) {
        viewModelScope.launch { repo.setNoteSuspended(card.id, suspended) }
    }

    fun delete(card: FlashcardEntity) {
        viewModelScope.launch { repo.delete(card.id) }
    }

    /** Imports the picked `.apkg`, preserving each card's Anki schedule + history. */
    fun importAnki(uri: Uri) {
        if (_import.value.running) return
        _import.value = ImportState(running = true)
        importJob = viewModelScope.launch {
            _import.value = try {
                ImportState(summary = importService.import(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ImportState(error = e.message ?: "Import failed")
            }
        }
    }

    /**
     * Stops an Anki import in flight. The deck's cards are written in a single transaction, so a
     * stopped import has changed either nothing or — if it was already done — everything.
     */
    fun cancelImport() {
        if (!_import.value.running) return
        importJob?.cancel()
        _import.value = ImportState()
    }

    fun consumeImport() {
        _import.value = ImportState()
    }

    /** Exports the whole vocabulary to the chosen `.apkg`, preserving each card's schedule + history. */
    fun exportAnki(uri: Uri) {
        if (_export.value.running) return
        _export.value = ExportState(running = true)
        exportJob = viewModelScope.launch {
            _export.value = try {
                ExportState(summary = exportService.export(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ExportState(error = e.message ?: "Export failed")
            }
        }
    }

    /**
     * Stops an Anki export in flight. Stopped before the file is opened it writes nothing (and the
     * empty file the picker made is removed); past that point the deck is finished regardless — a
     * whole file the learner didn't wait for beats half of one.
     */
    fun cancelExport() {
        if (!_export.value.running) return
        exportJob?.cancel()
        _export.value = ExportState()
    }

    fun consumeExport() {
        _export.value = ExportState()
    }

    /** Writes the whole vocabulary to the chosen file as readable JSON — for skimming, not re-import. */
    fun exportJson(uri: Uri) {
        if (_jsonExport.value.running) return
        _jsonExport.value = JsonExportState(running = true)
        jsonExportJob = viewModelScope.launch {
            _jsonExport.value = try {
                JsonExportState(count = jsonExportService.export(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JsonExportState(error = e.message ?: "Export failed")
            }
        }
    }

    /** Stops a JSON export in flight, on the same terms as [cancelExport]. */
    fun cancelJsonExport() {
        if (!_jsonExport.value.running) return
        jsonExportJob?.cancel()
        _jsonExport.value = JsonExportState()
    }

    fun consumeJsonExport() {
        _jsonExport.value = JsonExportState()
    }

    /**
     * Reads the picked file and works out what merging it would change, without changing anything —
     * the dialog needs real counts before the learner agrees to a file something else edited.
     */
    fun previewJsonImport(uri: Uri) {
        if (_jsonImport.value.running) return
        _jsonImport.value = JsonImportState(running = true)
        jsonImportJob = viewModelScope.launch {
            _jsonImport.value = try {
                JsonImportState(plan = jsonImportService.preview(uri))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JsonImportState(error = e.message ?: "Import failed")
            }
        }
    }

    /** Carries out the previewed merge: edits matched cards in place and adds the new ones. */
    fun applyJsonImport() {
        val plan = _jsonImport.value.plan ?: return
        _jsonImport.value = JsonImportState(running = true)
        jsonImportJob = viewModelScope.launch {
            _jsonImport.value = try {
                JsonImportState(summary = jsonImportService.apply(plan))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                JsonImportState(error = e.message ?: "Import failed")
            }
        }
    }

    /**
     * Stops a JSON import in flight — the read, or the write. Unlike the Anki one the write goes
     * note by note, so stopping it keeps the notes already merged; importing the same file again
     * picks up the rest.
     */
    fun cancelJsonImport() {
        if (!_jsonImport.value.running) return
        jsonImportJob?.cancel()
        _jsonImport.value = JsonImportState()
    }

    fun consumeJsonImport() {
        _jsonImport.value = JsonImportState()
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

    /** How many words ranked at or above [maxRank] have no card yet — the seed dialog's headline. */
    suspend fun missingCount(maxRank: Int): Int = seeder.missingCount(maxRank)

    /**
     * Generates cards for the missing words up to [maxRank], introducing [perDay] of them a day.
     * Progress and the outcome land in [seed]; the dialog stays up until the user dismisses it.
     */
    fun seedFrequentWords(maxRank: Int, perDay: Int) {
        if (_seed.value.running) return
        _seed.value = SeedState(running = true)
        seedJob = viewModelScope.launch {
            _seed.value = when (
                val result = seeder.seed(maxRank, perDay) { done, total ->
                    // Reported from the seeder's own thread, so a count can arrive just after a
                    // stop; it must not put the dialog back up over a run that is no longer there.
                    if (isActive) {
                        _seed.update { if (it.running) SeedState(running = true, done = done, total = total) else it }
                    }
                }
            ) {
                is SeedResult.Done -> SeedState(summary = result.summary)
                is SeedResult.Failed -> SeedState(error = result.error)
            }
        }
    }

    /**
     * Stops a seeding run. The cards it has already made stay — each is a finished card, and the
     * dialog was counting them as they landed — so running it again simply carries on from there.
     */
    fun stopSeed() {
        if (!_seed.value.running) return
        seedJob?.cancel()
        _seed.value = SeedState()
    }

    fun consumeSeed() {
        _seed.value = SeedState()
    }

    /**
     * Loads what converting this profile's hand-made cloze cards would do, without changing
     * anything — the dialog needs real counts before the learner agrees to delete cards.
     */
    fun previewClozeConversion() {
        viewModelScope.launch {
            val lang = settingsRepo.settings.first().targetLanguage
            _clozeConversion.value = clozeConverter.preview(lang)
        }
    }

    fun dismissClozeConversion() {
        _clozeConversion.value = null
    }

    /**
     * Moves the planned sentences into the cloze bank and deletes the cards they came from.
     *
     * Destructive, so the screen confirms first. The schedule and the per-review history travel with
     * the sentence, so nothing about what the learner has already learned is lost — only the cards
     * themselves go.
     */
    fun convertClozeCards() {
        val plan = _clozeConversion.value ?: return
        _clozeConversion.value = null
        viewModelScope.launch {
            val lang = settingsRepo.settings.first().targetLanguage
            val created = clozeConverter.convert(plan, lang)
            _clozeConverted.value = created
        }
    }

    fun consumeClozeConverted() {
        _clozeConverted.value = null
    }

    /**
     * Deletes every card the frequency seeder made for this profile. Destructive — the screen
     * confirms before calling this. Reports the count back through [seed] so the user sees what
     * happened rather than just watching the list shrink.
     */
    fun removeAutoAdded() {
        if (_seed.value.running) return
        viewModelScope.launch {
            val removed = repo.removeAutoAdded(FrequencySeeder.SOURCE)
            _seed.value = SeedState(removed = removed)
        }
    }
}
