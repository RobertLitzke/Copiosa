package com.copiosa.ui.books

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.copiosa.data.generation.BookAudioManager
import com.copiosa.data.generation.BookAudioPlan
import com.copiosa.data.generation.BookAudioScheduler
import com.copiosa.data.generation.BookDownloadChoice
import com.copiosa.data.generation.BookDownloadController
import com.copiosa.data.generation.BookDownloadState
import com.copiosa.data.generation.BookTranslationManager
import com.copiosa.data.epub.BookEpubExportService
import com.copiosa.data.epub.BookExportRun
import com.copiosa.data.epub.BookExportState
import com.copiosa.data.epub.EpubExportVariant
import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.llmErrorOfCode
import com.copiosa.data.repository.BookRepository
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.repository.FlashcardRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.domain.book.BookChapter
import com.copiosa.domain.book.chapterIndexAt
import com.copiosa.domain.book.wantsTranslation
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.reading.ReadMode
import com.copiosa.domain.vocab.WordSource
import com.copiosa.ui.components.ReadingSelectionController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the book reader is showing beyond the text itself. */
sealed interface BookStatus {
    /** Sections are being translated right now. */
    data class Translating(val done: Int, val total: Int) : BookStatus

    /** The last attempt failed; [error] is the reason to surface, [done]/[total] where it stopped. */
    data class Failed(val error: LlmError?, val done: Int, val total: Int) : BookStatus

    /** Partially translated and nothing in flight (read-ahead off, or simply not started). */
    data class Paused(val done: Int, val total: Int) : BookStatus

    /** Fully translated. */
    data object Ready : BookStatus
}

/**
 * Backs the book reader for one imported EPUB. Paragraphs come as a paged stream over
 * `book_paragraphs` (placeholders on, so the LazyColumn index maps 1:1 onto [BookParagraphEntity.ord]
 * and RAM stays bounded on a long novel). The screen reports the first-visible ord; this persists it
 * as the reading position and drives auto read-ahead — asking the single-writer
 * [BookTranslationManager] to stay [READ_AHEAD_SECTIONS] past the section being read.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@HiltViewModel
class BookReadingViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepo: BookRepository,
    private val books: BookTranslationManager,
    private val downloads: BookDownloadController,
    private val epubExport: BookEpubExportService,
    private val bookAudio: BookAudioManager,
    private val bookAudioScheduler: BookAudioScheduler,
    private val ttsModelStore: SupertonicModelStore,
    private val settingsRepo: SettingsRepository,
    conversationRepo: ConversationRepository,
    flashcardRepo: FlashcardRepository,
    highlightProvider: HighlightTargetProvider,
) : ViewModel() {

    private val id: Long = savedStateHandle.get<Long>("bookId") ?: 0L

    val book: StateFlow<BookEntity?> =
        bookRepo.observeBook(id)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The saved reading position, read once so the pager starts near it and restore is exact. */
    val savedScroll: StateFlow<Pair<Int, Int>?> =
        book.map { b -> b?.let { it.posParagraph to it.posOffset } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The language version this book was last read in. Null only means the row hasn't loaded yet —
     * the reader restores on the first non-null value and then leaves this alone, so the mode it is
     * showing is never yanked out from under a tap that arrived while the book was still loading.
     */
    val savedMode: StateFlow<ReadMode?> =
        book.map { it?.readMode }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Paged paragraph rows. The pager waits for the saved position so its initial load lands there
     * (placeholders + Room's jumping support handle long-range flings without loading the gap).
     */
    val paragraphs: Flow<PagingData<BookParagraphEntity>> =
        flow { emit(bookRepo.getById(id)?.posParagraph ?: 0) }
            .flatMapLatest { startOrd ->
                Pager(
                    config = PagingConfig(
                        pageSize = PAGE_SIZE,
                        prefetchDistance = PAGE_SIZE,
                        enablePlaceholders = true,
                        maxSize = MAX_RESIDENT_ROWS,
                        initialLoadSize = PAGE_SIZE * 2,
                        jumpThreshold = PAGE_SIZE * 3,
                    ),
                    initialKey = (startOrd - PAGE_SIZE / 2).coerceAtLeast(0),
                    pagingSourceFactory = { bookRepo.paragraphs(id) },
                ).flow
            }
            .cachedIn(viewModelScope)

    val status: StateFlow<BookStatus> =
        combine(book.filterNotNull(), books.active, books.errors, books.progress) { b, active, errors, progress ->
            val done = progress[id]?.first ?: b.translatedSections
            val total = b.totalSections
            // The live error if this session produced one, else the reason Room kept from whenever
            // the book last failed. A background stall is usually days old by the time it is looked
            // at and the manager's map died with the process that filled it, so without the stored
            // one the reader can only say that the count stopped moving.
            val error = errors[id] ?: b.lastError?.let(::llmErrorOfCode)
            when {
                // Ahead of the error now that a failure outlives its run: a book being retried is
                // translating, not failed.
                active[id] != null -> BookStatus.Translating(done, total)
                // A reverse book that never opted into the gloss is complete as it stands — its
                // untranslated sections are not a shortfall, so there is nothing to report or nag
                // about. Flipping the gloss on puts it back on the normal progress track.
                b.translatedSections >= total || !b.wantsTranslation -> BookStatus.Ready
                error != null -> BookStatus.Failed(error, b.translatedSections, total)
                else -> BookStatus.Paused(b.translatedSections, total)
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookStatus.Ready)

    /**
     * The chapter list behind the reader's table of contents. Keyed on the translation frontier
     * rather than on raw paragraph writes: the chapter *structure* is fixed at import, so this only
     * needs re-running when a completed section could have changed a chapter's readable extent —
     * once every few seconds at most, instead of on every paragraph the LLM returns.
     */
    val chapters: StateFlow<List<BookChapter>> =
        book.filterNotNull()
            .map { it.direction to it.translatedSections }
            .distinctUntilChanged()
            .map { (direction, frontier) -> bookRepo.tableOfContents(id, direction, frontier) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether this book translates ahead of the reader (drives the frontier hint row's wording). */
    val autoReadAhead: StateFlow<Boolean> =
        book.map { it?.downloadMode?.readsAhead ?: true }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Download-mode, EPUB-export and audio actions for this book, and their dialogs; see [BookActions]. */
    val actions = BookActions(viewModelScope, downloads, epubExport, bookAudio, bookAudioScheduler)

    fun openDownloadDialog() = actions.openDownload(id)

    fun openExportDialog() = actions.openExport(id)

    // --- audio: the voice gate, then the confirmation with the real numbers ---

    private val _needsTtsDownload = MutableStateFlow(false)
    val needsTtsDownload: StateFlow<Boolean> = _needsTtsDownload.asStateFlow()

    /** "Generate audio": the voice gate first, then the confirmation with the real numbers. */
    fun requestAudio() {
        if (ttsModelStore.isReady()) actions.openAudio(id) else _needsTtsDownload.value = true
    }

    /** The download prompt's confirm: the queue waits at PENDING until the voice arrives. */
    fun confirmTtsDownload() {
        _needsTtsDownload.value = false
        ttsModelStore.download()
        actions.openAudio(id)
    }

    fun dismissTtsDownload() { _needsTtsDownload.value = false }

    /** Once the frontier banner is waved away it stays gone; the overflow menu keeps "Continue". */
    val frontierHintDismissed: StateFlow<Boolean> =
        settingsRepo.settings.map { it.bookFrontierHintDismissed }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun dismissFrontierHint() {
        viewModelScope.launch { settingsRepo.update { it.copy(bookFrontierHintDismissed = true) } }
    }

    /**
     * The translated text of the section currently being read, fed to the selection controller for
     * passive vocabulary review — the paged list never holds the whole book, so this stands in for
     * the texts reader's full-body flow.
     */
    private val visibleSectionText = MutableStateFlow("")

    /** Latest reported reading position (ord to pixel offset); -1 until the reader reports one. */
    private val visiblePosition = MutableStateFlow(-1 to 0)

    /**
     * Whether due vocabulary is tinted in this book: the book's own override if it has one, else the
     * language's setting. Also drives the overflow menu's check mark.
     */
    val highlightWords: StateFlow<Boolean> =
        combine(book, settingsRepo.settings) { b, s -> b?.highlightWords ?: s.highlightSavedWords }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** The selectable-reader engine; see [ReadingSelectionController]. The screen drives it directly. */
    val readingSelection = ReadingSelectionController(
        scope = viewModelScope,
        conversationRepo = conversationRepo,
        flashcardRepo = flashcardRepo,
        highlightProvider = highlightProvider,
        source = { WordSource.book(id) },
        readingText = visibleSectionText,
        highlightEnabled = highlightWords,
    )

    /** The words saved while reading this book, newest first. */
    val savedWords: StateFlow<List<FlashcardEntity>> = flashcardRepo.observeSavedFrom(WordSource.book(id))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The footnote whose marker was last tapped, or null when no note is open.
     *
     * Looked up on demand rather than held with the paragraph: notes live at the end of their
     * chapter, which is nowhere near the marker in the paged list, and most readings never open one.
     */
    val openNote = MutableStateFlow<BookParagraphEntity?>(null)

    fun onFootnote(chapterIndex: Int, label: String) {
        viewModelScope.launch { openNote.value = bookRepo.note(id, chapterIndex, label) }
    }

    fun dismissNote() {
        openNote.value = null
    }

    /** Which chapter the reader is in, for the table of contents to mark; -1 before it is known. */
    val currentChapter: StateFlow<Int> =
        combine(chapters, visiblePosition) { toc, (ord, _) ->
            if (ord < 0) -1 else chapterIndexAt(toc, ord)
        }.distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    init {
        // A freshly imported (or reopened partial) book: make sure the first sections are coming so
        // the reader has something to show, regardless of the read-ahead setting. A reverse book
        // without the gloss opted in needs nothing — its text is already there.
        viewModelScope.launch {
            val b = book.filterNotNull().first()
            val complete = b.translatedSections >= b.totalSections
            if (b.wantsTranslation && !complete && b.translatedSections <= FIRST_OPEN_SECTIONS) {
                books.ensureTranslatedThrough(id, FIRST_OPEN_SECTIONS)
            }
            // A rapid download the OS killed (or one enqueued while offline) is re-kicked here:
            // opening the book is a foreground moment, which is what a data-sync foreground service
            // needs to start, and the unique work makes a still-running download a no-op.
            downloads.resumeIfRapid(id, b.title, b.downloadMode, complete)
        }
        // Persist the reading position. Debounced because the reader reports every scroll pixel and
        // this is a DB write; the position only has to be right by the time the screen goes away.
        viewModelScope.launch {
            visiblePosition
                .filter { (ord, _) -> ord >= 0 }
                .debounce(POSITION_SAVE_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { (ord, offset) -> bookRepo.saveScroll(id, ord, offset) }
        }
        // Auto read-ahead: whenever the reader crosses into a new section, ask for it + the next
        // READ_AHEAD_SECTIONS. The manager's per-book target makes repeated calls free, but the
        // section lookup is a query, so it rides the same debounce.
        viewModelScope.launch {
            visiblePosition
                .map { (ord, _) -> ord }
                .filter { it >= 0 }
                .distinctUntilChanged()
                .debounce(POSITION_SAVE_DEBOUNCE_MS)
                .map { ord -> bookRepo.sectionOf(id, ord) }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { section ->
                    refreshVisibleSectionText(section)
                    // Nothing to read ahead *to* on a reverse book without the gloss: the text the
                    // reader is scrolling through is the file's own. OFF asks for nothing at all.
                    val b = book.value
                    if (b?.wantsTranslation == true && b.downloadMode.readsAhead) {
                        books.ensureTranslatedThrough(id, section + READ_AHEAD_SECTIONS)
                    }
                }
        }
    }

    private suspend fun refreshVisibleSectionText(section: Int) {
        // The *target*-language text, which on a reverse book is the original — otherwise passive
        // vocabulary review would see nothing (or, worse, the learner's own language).
        val direction = book.value?.direction ?: BookDirection.FORWARD
        visibleSectionText.value = bookRepo.sectionStudyText(id, section, direction)
    }

    /** The screen reports the first visible paragraph; persisted (debounced) as the resume position. */
    fun onFirstVisible(ord: Int, offsetPx: Int) {
        visiblePosition.value = ord to offsetPx
    }

    /** Persists the language version being read, so reopening the book resumes in it. */
    fun saveReadMode(mode: ReadMode) {
        viewModelScope.launch { bookRepo.setReadMode(id, mode) }
    }

    /** Continue translating from the frontier (the Failed/Paused rows' action). */
    fun continueTranslating() {
        books.consumeError(id)
        val section = book.value?.translatedSections ?: 0
        books.ensureTranslatedThrough(id, section + READ_AHEAD_SECTIONS)
    }

    /**
     * Flip highlighting for this book. Written as an explicit override rather than cleared back to
     * "follow the setting": having just turned it off here, the learner would not expect a later
     * change to the language default to switch it back on.
     */
    fun toggleHighlightWords() {
        val next = !highlightWords.value
        viewModelScope.launch { bookRepo.setHighlightWords(id, next) }
    }

    fun toggleStar() {
        val next = !(book.value?.starred ?: false)
        viewModelScope.launch { bookRepo.setStarred(id, next) }
    }

    fun markFinishedReading() {
        if (book.value?.finishedReading == true) return
        viewModelScope.launch { bookRepo.setFinishedReading(id, true) }
    }

    fun delete() {
        books.cancel(id)
        viewModelScope.launch { bookRepo.delete(id) }
    }

    companion object {
        /** How many sections past the one being read the auto read-ahead keeps translated. */
        const val READ_AHEAD_SECTIONS = 2

        /** Sections ensured on open so a fresh book shows content quickly. */
        const val FIRST_OPEN_SECTIONS = 1

        const val PAGE_SIZE = 60
        const val MAX_RESIDENT_ROWS = 300

        /** Scroll settles before the position is written / the section looked up. */
        const val POSITION_SAVE_DEBOUNCE_MS = 400L
    }
}
