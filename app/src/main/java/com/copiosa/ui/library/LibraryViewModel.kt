package com.copiosa.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copiosa.data.article.ArticleExtractor
import com.copiosa.data.generation.AudioGenerationManager
import com.copiosa.data.generation.GenPhase
import com.copiosa.data.generation.GenerationManager
import com.copiosa.data.local.ConversationEntity
import com.copiosa.data.repository.ConversationRepository
import com.copiosa.data.tts.SupertonicModelStore
import com.copiosa.domain.model.Role
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
)

/** Which kinds of items the Library list shows; [LibraryFilter.ALL] is the merged default. */
enum class LibraryFilter { ALL, CHATS, TRANSLATIONS }

/** A row in the unified Library list — either a chat or a translated text. */
sealed interface LibraryEntry {
    val id: Long
    val updatedAt: Long

    data class Chat(val row: ConversationRow) : LibraryEntry {
        override val id get() = row.conversation.id
        override val updatedAt get() = row.conversation.updatedAt
    }

    data class Translation(val item: TextItem) : LibraryEntry {
        override val id get() = item.id
        override val updatedAt get() = item.updatedAt
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: ConversationRepository,
    private val generation: GenerationManager,
    private val audioGeneration: AudioGenerationManager,
    private val ttsModelStore: SupertonicModelStore,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _filter = MutableStateFlow(LibraryFilter.ALL)
    val filter: StateFlow<LibraryFilter> = _filter.asStateFlow()

    /**
     * All chats and translations, merged and sorted by recency. The [_filter] is applied downstream so
     * toggling segments doesn't re-hit the database.
     */
    private val allEntries: StateFlow<List<LibraryEntry>> =
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
                    LibraryEntry.Translation(
                        TextItem(
                            id = c.id,
                            title = c.title,
                            source = c.sourceUrl?.let { ArticleExtractor.hostOf(it) },
                            level = c.level.name,
                            readingMinutes = readingMinutes(c.originalText),
                            updatedAt = c.updatedAt,
                            status = status,
                            starred = c.starred,
                            finishedReading = c.finishedReading,
                        ),
                    )
                }
                (chats + translations).sortedByDescending { it.updatedAt }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many rows the list shows before requiring "Show more"; grows a page at a time. */
    private val _limit = MutableStateFlow(PAGE_SIZE)

    /** [allEntries] narrowed to the selected [filter] (pre-pagination). */
    private val filteredEntries: StateFlow<List<LibraryEntry>> =
        combine(allEntries, _filter) { all, filter ->
            when (filter) {
                LibraryFilter.ALL -> all
                LibraryFilter.CHATS -> all.filterIsInstance<LibraryEntry.Chat>()
                LibraryFilter.TRANSLATIONS -> all.filterIsInstance<LibraryEntry.Translation>()
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

    /** Whether the on-device voice is installed, so the screen can prompt to download it if not. */
    fun isTtsReady(): Boolean = ttsModelStore.isReady()

    /** Starts the (opt-in) voice download — called from the "Generate audio" download prompt. */
    fun startTtsDownload() = ttsModelStore.download()

    /** Queues a chat's assistant turns for on-device TTS as a single audio item (renders when ready). */
    fun sendChatToAudio(id: Long) {
        viewModelScope.launch {
            val conv = repo.getConversation(id) ?: return@launch
            val body = repo.observeMessages(id).first()
                .filter { it.role == Role.ASSISTANT && it.content.isNotBlank() }
                .joinToString("\n\n") { it.content.trim() }
                .trim()
            if (body.isBlank()) return@launch
            // Match the list: use the target-language title, not the native-language subject.
            val title = conv.title
            audioGeneration.enqueue(
                text = body,
                title = title,
                langCode = conv.targetLanguage,
                sourceConversationId = id,
            )
        }
    }

    /** Queues a translation's translated body for on-device TTS (renders once the voice is ready). */
    fun sendTextToAudio(id: Long) {
        viewModelScope.launch {
            val conv = repo.getConversation(id) ?: return@launch
            // The translated body is stored as the text's first assistant message.
            val body = repo.observeMessages(id).first()
                .firstOrNull { it.role == Role.ASSISTANT }?.content?.trim().orEmpty()
            if (body.isBlank()) return@launch
            // Match the list: use the target-language title, not the source-language originalTitle.
            val title = conv.title
            audioGeneration.enqueue(
                text = body,
                title = title,
                langCode = conv.targetLanguage,
                sourceConversationId = id,
            )
        }
    }

    private fun readingMinutes(text: String?): Int {
        if (text.isNullOrBlank()) return 0
        val words = text.trim().split(Regex("\\s+")).size
        return (words / WORDS_PER_MINUTE).coerceAtLeast(1)
    }

    private companion object {
        const val WORDS_PER_MINUTE = 200

        /** Rows shown per "page" of the Library list. */
        const val PAGE_SIZE = 50
    }
}
