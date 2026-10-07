package com.copiosa.ui.library

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.article.ArticleExtractor
import com.copiosa.data.epub.BookEpubExportService
import com.copiosa.data.epub.BookExportRun
import com.copiosa.data.epub.BookExportState
import com.copiosa.data.epub.EpubExportVariant
import com.copiosa.data.generation.AudioGenerationManager
import com.copiosa.data.generation.BookAudioManager
import com.copiosa.data.generation.BookAudioPlan
import com.copiosa.data.generation.BookAudioScheduler
import com.copiosa.data.generation.BookDownloadChoice
import com.copiosa.data.generation.BookDownloadController
import com.copiosa.data.generation.BookDownloadState
import com.copiosa.data.generation.BookTranslationManager
import com.copiosa.data.generation.GenPhase
import com.copiosa.data.generation.GenerationManager
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.llmErrorOfCode
import com.copiosa.data.repository.BookRepository
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.domain.book.wantsTranslation
import com.copiosa.domain.model.Role
import com.copiosa.domain.reading.BilingualBlocks
import com.copiosa.ui.books.BookActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Per-row generation state shown for a chat. */
enum class RowStatus { QUEUED, GENERATING, FAILED }

/** A chat row: the conversation, a preview of its latest message, and any generation status. */
data class ConversationRow(
    val conversation: ConversationEntity,
    val snippet: String?,
    val status: RowStatus? = null,
)

/** Generation state of a translation (imported/pasted text) row. */
sealed interface TextStatus {
    data object Importing : TextStatus
    data class Translating(val done: Int, val total: Int) : TextStatus
    data object Failed : TextStatus
}

/** A reader item — an imported/pasted text translated for reading. */
data class TextItem(
    val id: Long,
    val title: String,
    /** Host of the source URL, or null for pasted text (the row then labels it "Pasted text"). */
    val source: String?,
    val level: String,
    val readingMinutes: Int,
    /** Recency key, so translations interleave with chats in the unified list. */
    val updatedAt: Long,
    val status: TextStatus? = null,
    val starred: Boolean = false,
    /** True once the reader has scrolled to the end; drives the list's read-status coloring. */
    val finishedReading: Boolean = false,
    /**
     * How far through the text the reader is, 0..1 — or null for a text short enough that "where
     * was I" is answered by opening it, which gets no progress rule at all.
     */
    val progress: Float? = null,
)

/** An imported EPUB book row. */
data class BookItem(
    val id: Long,
    val title: String,
    val author: String?,
    /** Cover filename in the book-covers store; null = no cover, the row shows a book icon. */
    val coverFile: String?,
    val level: String,
    /**
     * Whether this book is meant to end up fully translated. False for a reverse book with the
     * gloss switched off: it is complete as it stands, so the row shows neither a level badge nor
     * a translation shortfall.
     */
    val wantsTranslation: Boolean,
    val translatedSections: Int,
    val totalSections: Int,
    /** True while the manager is actively translating this book. */
    val translating: Boolean,
    /**
     * Why the last translation attempt failed, or null. Read from Room as well as the manager, so a
     * book that stalled in the background days ago still says what stopped it — the row is the only
     * place that stall is visible without opening the book.
     */
    val failure: LlmError?,
    val readingMinutes: Int,
    val updatedAt: Long,
    val starred: Boolean = false,
    val finishedReading: Boolean = false,
    /** How far through the book the reader is, 0..1, by paragraph. */
    val progress: Float = 0f,
)

/**
 * Which kinds of items the Library list shows; [LibraryFilter.ALL] is the merged default.
 * [LibraryFilter.AUDIO] is a segment rather than a filter — it swaps the list for the audio queue,
 * which has its own screen state, so it never produces [LibraryEntry] rows.
 */
enum class LibraryFilter { ALL, CHATS, TEXTS, BOOKS, AUDIO }

/** A row in the unified Library list — a chat, a translated text, or an imported book. */
sealed interface LibraryEntry {
    val id: Long
    val updatedAt: Long

    /**
     * Stable list key. Chats and translations share the conversations table, but books have their
     * own — so [id] alone is not unique across the merged list and a bare id key would collide.
     */
    val listKey: String

    data class Chat(val row: ConversationRow) : LibraryEntry {
        override val id get() = row.conversation.id
        override val updatedAt get() = row.conversation.updatedAt
        override val listKey get() = "c$id"
    }

    data class Text(val item: TextItem) : LibraryEntry {
        override val id get() = item.id
        override val updatedAt get() = item.updatedAt
        override val listKey get() = "t$id"
    }

    data class Book(val item: BookItem) : LibraryEntry {
        override val id get() = item.id
        override val updatedAt get() = item.updatedAt
        override val listKey get() = "b$id"
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ConversationRepository,
    private val bookRepo: BookRepository,
    private val generation: GenerationManager,
    private val books: BookTranslationManager,
    private val downloads: BookDownloadController,
    private val epubExport: BookEpubExportService,
    private val audioGeneration: AudioGenerationManager,
    private val bookAudio: BookAudioManager,
    private val bookAudioScheduler: BookAudioScheduler,
    private val ttsModelStore: SupertonicModelStore,
    settingsRepo: SettingsRepository,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // Seeded from the nav arg so Home's "unplayed audio" card can land directly on the Audio pane.
    private val _filter = MutableStateFlow(
        savedStateHandle.get<String>("filter")
            ?.let { runCatching { LibraryFilter.valueOf(it) }.getOrNull() }
            ?: LibraryFilter.ALL,
    )
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    /** Book rows for the active profile, with live status from the translation manager. */
    private val bookEntries: Flow<List<LibraryEntry>> =
        settingsRepo.settings.map { it.targetLanguage }.distinctUntilChanged().flatMapLatest { lang ->
            combine(
                bookRepo.observeBooks(lang),
                books.active,
                books.progress,
                books.errors,
                _query,
            ) { rows, active, progress, errors, q ->
                rows
                    .filter { b ->
                        q.isBlank() || b.title.contains(q, ignoreCase = true) ||
                            b.originalTitle.contains(q, ignoreCase = true) ||
                            b.author?.contains(q, ignoreCase = true) == true
                    }
                    .map { b ->
                        LibraryEntry.Book(
                            BookItem(
                                id = b.id,
                                title = b.title,
                                author = b.author,
                                coverFile = b.coverFile,
                                level = b.level.name,
                                wantsTranslation = b.wantsTranslation,
                                translatedSections = progress[b.id]?.first ?: b.translatedSections,
                                totalSections = b.totalSections,
                                translating = active[b.id] != null,
                                failure = errors[b.id] ?: b.lastError?.let(::llmErrorOfCode),
                                readingMinutes = (b.wordCount / WORDS_PER_MINUTE).coerceAtLeast(1),
                                updatedAt = b.updatedAt,
                                starred = b.starred,
                                finishedReading = b.finishedReading,
                                progress = readProgress(b.posParagraph, b.totalParagraphs, b.finishedReading),
                            ),
                        )
                    }
            }
        }

    /**
     * All chats, translations and books, merged and sorted by recency. The [_filter] is applied
     * downstream so toggling segments doesn't re-hit the database.
     */
    private val chatAndTextEntries: Flow<List<LibraryEntry>> =
        _query.flatMapLatest { q ->
            combine(
                repo.observeConversations(q),
                repo.observeLatestPreviews(),
                repo.observeTexts(q),
                generation.active,
                generation.progress,
            ) { convs, previews, texts, active, progress ->
                val previewById = previews.associateBy { it.conversationId }
                val chats = convs.map { conv ->
                    val preview = previewById[conv.id]
                    // QUEUED/GENERATING come from the manager; a chat whose last turn is still the
                    // user's (and isn't generating) failed/was interrupted — derived from Room so it's
                    // correct even after a restart.
                    val status = when {
                        active[conv.id] == GenPhase.GENERATING -> RowStatus.GENERATING
                        active[conv.id] == GenPhase.QUEUED -> RowStatus.QUEUED
                        preview?.role == Role.USER -> RowStatus.FAILED
                        else -> null
                    }
                    LibraryEntry.Chat(ConversationRow(conv, preview?.snippet, status))
                }
                val translations = texts.map { c ->
                    // Importing (fetch) until the chunker reports progress; then Translating. "Done" is
                    // the persisted chunk count reaching the total; anything else that isn't actively
                    // running is a stalled/failed import the user can tap to continue.
                    val complete = c.totalChunks > 0 && c.translatedChunks >= c.totalChunks
                    val status = when {
                        active[c.id] != null && progress[c.id] != null ->
                            progress.getValue(c.id).let { (d, t) -> TextStatus.Translating(d, t) }
                        active[c.id] != null -> TextStatus.Importing
                        complete -> null
                        else -> TextStatus.Failed
                    }
                    val words = wordCount(c.originalText)
                    LibraryEntry.Text(
                        TextItem(
                            id = c.id,
                            title = c.title,
                            source = c.sourceUrl?.let { ArticleExtractor.hostOf(it) },
                            level = c.level.name,
                            readingMinutes = if (words > 0) (words / WORDS_PER_MINUTE).coerceAtLeast(1) else 0,
                            updatedAt = c.updatedAt,
                            status = status,
                            starred = c.starred,
                            finishedReading = c.finishedReading,
                            progress = if (words >= LONG_TEXT_WORDS) textProgress(c) else null,
                        ),
                    )
                }
                chats + translations
            }
        }

    private val allEntries: StateFlow<List<LibraryEntry>> =
        combine(chatAndTextEntries, bookEntries) { ct, bk ->
            (ct + bk).sortedByDescending { it.updatedAt }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many rows the list shows before requiring "Show more"; grows a page at a time. */
    private val _limit = MutableStateFlow(PAGE_SIZE)

    /** [allEntries] narrowed to the selected [filter] (pre-pagination). */
    private val filteredEntries: StateFlow<List<LibraryEntry>> =
        combine(allEntries, _filter) { all, filter ->
            when (filter) {
                LibraryFilter.ALL -> all
                LibraryFilter.CHATS -> all.filterIsInstance<LibraryEntry.Chat>()
                LibraryFilter.TEXTS -> all.filterIsInstance<LibraryEntry.Text>()
                LibraryFilter.BOOKS -> all.filterIsInstance<LibraryEntry.Book>()
                LibraryFilter.AUDIO -> emptyList()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The list the screen renders: [filteredEntries] capped to the current page limit. */
    val entries: StateFlow<List<LibraryEntry>> =
        combine(filteredEntries, _limit) { filtered, limit ->
            filtered.take(limit)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many rows the page limit is hiding, for the "Show more" footer (0 hides it). */
    val moreCount: StateFlow<Int> =
        combine(filteredEntries, _limit) { filtered, limit ->
            (filtered.size - limit).coerceAtLeast(0)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Reveals the next page of rows. */
    fun showMore() { _limit.value += PAGE_SIZE }

    /** Fires when a chat batch is committed; the screen jumps the list to the new queued rows. */
    val batchStarted = generation.batchStarted

    /** How many chats are queued or actively generating, for the list's progress banner. */
    val pendingCount: StateFlow<Int> =
        generation.active
            .map { it.size }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setQuery(q: String) {
        _query.value = q
        _limit.value = PAGE_SIZE
    }

    fun setFilter(f: LibraryFilter) {
        _filter.value = f
        _limit.value = PAGE_SIZE
    }

    /** Regenerate the opening reply for a chat whose generation failed. */
    fun retryChat(id: Long) { generation.generateReply(id) }

    /** Re-runs translation for a text whose import/translation failed. */
    fun retryTranslation(id: Long) { generation.translate(id) }

    fun setStarred(id: Long, starred: Boolean) {
        viewModelScope.launch { repo.setStarred(id, starred) }
    }

    fun setRead(id: Long, read: Boolean) {
        viewModelScope.launch { repo.setRead(id, read) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun setBookStarred(id: Long, starred: Boolean) {
        viewModelScope.launch { bookRepo.setStarred(id, starred) }
    }

    /**
     * Flip a book's "read to the end" flag by hand, the same way a text can be. The reader sets it
     * on its own when you reach the last paragraph, but nothing else could ever clear it — a book
     * marked finished stayed finished, and so stayed out of Home's "continue reading".
     */
    fun setBookRead(id: Long, read: Boolean) {
        viewModelScope.launch { bookRepo.setFinishedReading(id, read) }
    }

    /** Download-mode, EPUB-export and audio actions for whichever book row was tapped; see [BookActions]. */
    val bookActions = BookActions(viewModelScope, downloads, epubExport, bookAudio, bookAudioScheduler)

    fun deleteBook(id: Long) {
        books.cancel(id)
        viewModelScope.launch { bookRepo.delete(id) }
    }

    /** Whether the on-device voice is installed, so the screen can prompt to download it if not. */
    fun isTtsReady(): Boolean = ttsModelStore.isReady()

    /** Starts the (opt-in) voice download — called from the "Generate audio" download prompt. */
    fun startTtsDownload() = ttsModelStore.download()

    /**
     * Queues a chat's assistant turns for on-device TTS as a single audio item (renders when ready).
     * [onResult] hears whether anything was queued — a chat still waiting on its first reply has
     * nothing to read — so the list can say which happened.
     */
    fun sendChatToAudio(id: Long, onResult: (queued: Boolean) -> Unit) {
        viewModelScope.launch {
            val conv = repo.getConversation(id)
            val body = repo.observeMessages(id).first()
                .filter { it.role == Role.ASSISTANT && it.content.isNotBlank() }
                .joinToString("\n\n") { it.content.trim() }
                .trim()
            if (conv == null || body.isBlank()) return@launch onResult(false)
            audioGeneration.enqueue(
                text = body,
                // Match the list: use the target-language title, not the native-language subject.
                title = conv.title,
                langCode = conv.targetLanguage,
                sourceConversationId = id,
            )
            onResult(true)
        }
    }

    /**
     * Queues a translation's translated body for on-device TTS (renders once the voice is ready).
     * [onResult] as in [sendChatToAudio]: a text whose translation has not arrived queues nothing.
     */
    fun sendTextToAudio(id: Long, onResult: (queued: Boolean) -> Unit) {
        viewModelScope.launch {
            val conv = repo.getConversation(id)
            // The translated body is stored as the text's first assistant message.
            val body = repo.observeMessages(id).first()
                .firstOrNull { it.role == Role.ASSISTANT }?.content?.trim().orEmpty()
            if (conv == null || body.isBlank()) return@launch onResult(false)
            audioGeneration.enqueue(
                text = body,
                // Match the list: use the target-language title, not the source-language originalTitle.
                title = conv.title,
                langCode = conv.targetLanguage,
                sourceConversationId = id,
            )
            onResult(true)
        }
    }

    private fun wordCount(text: String?): Int =
        if (text.isNullOrBlank()) 0 else text.trim().split(WHITESPACE).size

    /**
     * A text's progress from its saved scroll position. The reader's list is one row per source
     * block (see [BilingualBlocks.align], which anchors rows on the original) behind a title item,
     * so the stored first-visible index over that count is how far in the reader is — the same
     * item-granular measure the open reader's rule uses.
     */
    private fun textProgress(c: ConversationEntity): Float {
        val items = BilingualBlocks.split(c.originalText.orEmpty()).size + 1
        return readProgress(c.scrollIndex, items, c.finishedReading)
    }

    private companion object {
        const val WORDS_PER_MINUTE = 200

        /**
         * Where a text becomes long enough to lose your place in: about four phone screens at the
         * reading size. Below it the row carries no progress rule.
         */
        const val LONG_TEXT_WORDS = 600

        val WHITESPACE = Regex("\\s+")

        /** Rows shown per "page" of the Library list. */
        const val PAGE_SIZE = 50
    }
}

/**
 * How far through [total] items a saved position of [position] is, 0..1. A finished item reads as
 * full whatever its position says: the saved index is the *first* visible row, which on the last
 * screen of a text is still short of the end.
 */
internal fun readProgress(position: Int, total: Int, finished: Boolean): Float = when {
    finished -> 1f
    total <= 0 -> 0f
    else -> (position.toFloat() / total).coerceIn(0f, 1f)
}
