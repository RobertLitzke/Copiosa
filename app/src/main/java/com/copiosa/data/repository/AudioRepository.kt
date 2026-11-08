package com.copiosa.data.repository

import android.content.Context
import com.copiosa.data.local.AudioItemDao
import com.copiosa.data.local.AudioItemEntity
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.listening.RadioScript
import com.copiosa.domain.model.AudioStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage for the listening playlist. Wraps [AudioItemDao] and owns the on-disk WAVs under
 * `filesDir/audio`. Ordering is ([AudioItemEntity.played] ASC, [AudioItemEntity.sortOrder] ASC), so
 * played items sink to the bottom of the list while each group keeps a user-arrangeable order. New
 * and just-played items get a [AudioItemEntity.sortOrder] larger than everything else so they append
 * to the end of their group; a drag-reorder rewrites only the unplayed group's keys.
 */
@Singleton
class AudioRepository @Inject constructor(
    private val dao: AudioItemDao,
    private val settingsRepo: SettingsRepository,
    @ApplicationContext private val context: Context,
) {
    val audioDir: File by lazy { File(context.filesDir, "audio").apply { mkdirs() } }

    /** The active profile's playlist; re-emits when the user switches language profiles. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeAll(): Flow<List<AudioItemEntity>> =
        settingsRepo.activeProfileTag.flatMapLatest { lang -> dao.observeAll(lang) }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeReady(): Flow<List<AudioItemEntity>> =
        settingsRepo.activeProfileTag.flatMapLatest { lang -> dao.observeReady(lang) }

    /** How many rendered-and-unheard items await in the active profile, for the Home screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeUnplayedReadyCount(): Flow<Int> =
        settingsRepo.activeProfileTag.flatMapLatest { lang -> dao.observeUnplayedReadyCount(lang) }

    suspend fun getById(id: Long): AudioItemEntity? = dao.getById(id)

    suspend fun unrendered(): List<AudioItemEntity> = dao.unrendered()

    /** One folder's tracks in track order, for the tag screen. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTag(tag: String): Flow<List<AudioItemEntity>> =
        settingsRepo.activeProfileTag.flatMapLatest { lang -> dao.observeTag(tag, lang) }

    fun observeForBook(bookId: Long): Flow<List<AudioItemEntity>> = dao.observeForBook(bookId)

    suspend fun chaptersWithAudio(bookId: Long): List<Int> = dao.chaptersWithAudio(bookId)

    suspend fun tagForBook(bookId: Long): String? = dao.tagForBook(bookId)

    /**
     * Queues a new audio item (status PENDING); returns its id.
     *
     * [sortOrder] is explicit for grouped items, which order themselves within their [tag] (chapter
     * order, for a book) rather than appending to the end of the global playlist.
     */
    suspend fun enqueue(
        text: String,
        title: String,
        langCode: String,
        sid: Int = 0,
        sourceConversationId: Long? = null,
        sourceMessageId: Long? = null,
        tag: String? = null,
        sourceBookId: Long? = null,
        sourceChapterIndex: Int? = null,
        sortOrder: Double? = null,
        kind: String = RadioScript.KIND_ARTICLE,
    ): Long {
        val order = sortOrder ?: ((dao.maxSortOrder() ?: 0.0) + 1.0)
        return dao.insert(
            AudioItemEntity(
                title = title.ifBlank { defaultTitle(text) },
                text = text,
                langCode = langCode,
                sid = sid,
                status = AudioStatus.PENDING,
                sortOrder = order,
                createdAt = System.currentTimeMillis(),
                sourceConversationId = sourceConversationId,
                sourceMessageId = sourceMessageId,
                tag = tag,
                sourceBookId = sourceBookId,
                sourceChapterIndex = sourceChapterIndex,
                kind = kind,
            ),
        )
    }

    suspend fun setStatus(id: Long, status: AudioStatus) = dao.setStatus(id, status)

    suspend fun setReady(id: Long, file: File, durationMs: Long?) =
        dao.setResult(id, AudioStatus.READY, file.absolutePath, durationMs, null)

    suspend fun setFailed(id: Long, error: String?) =
        dao.setResult(id, AudioStatus.FAILED, null, null, error)

    /**
     * Marks an item played, stamps the retention clock, and sinks it to the very bottom of the list.
     *
     * A tagged item keeps its position instead: its [AudioItemEntity.sortOrder] is its place in a
     * book, and sinking finished chapters one by one would leave the folder shuffled into the order
     * they happened to be heard in. Either way the resume point is cleared, so a re-listen starts
     * from the top.
     */
    suspend fun markPlayed(id: Long) {
        val item = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        if (item.tag != null) {
            dao.setPlayedKeepingOrder(id, played = true, playedAt = now)
        } else {
            dao.setPlayedAndOrder(
                id,
                played = true,
                sortOrder = (dao.maxSortOrder() ?: 0.0) + 1.0,
                playedAt = now,
            )
        }
    }

    /** "Mark unlistened": returns the item to the unplayed group (at its current order position). */
    suspend fun markUnplayed(id: Long) = dao.setPlayed(id, played = false)

    /** Persists a track's resume point; see [com.copiosa.playback.PlaybackController]. */
    suspend fun setPosition(id: Long, ms: Long) = dao.setPosition(id, ms)

    /** Persists a drag-reorder of the unplayed group as a contiguous ascending key sequence. */
    suspend fun persistUnplayedOrder(orderedIds: List<Long>) {
        orderedIds.forEachIndexed { index, id -> dao.setSortOrder(id, index.toDouble()) }
    }

    suspend fun delete(id: Long) {
        dao.getById(id)?.filePath?.let { runCatching { File(it).delete() } }
        dao.delete(id)
    }

    /** Deletes a whole group — the folder screen's "delete" and what a deleted book takes with it. */
    suspend fun deleteTag(tag: String) {
        val lang = settingsRepo.activeProfileTag.first()
        dao.taggedItems(tag, lang).forEach { item -> item.filePath?.let { runCatching { File(it).delete() } } }
        dao.deleteTag(tag, lang)
    }

    /**
     * Removes every track generated from [bookId], across profiles. Called when the book itself is
     * deleted: there is no foreign key to cascade, and a novel's audio is hundreds of megabytes to
     * leave orphaned on disk.
     */
    suspend fun deleteForBook(bookId: Long) {
        for (item in dao.forBook(bookId)) {
            item.filePath?.let { runCatching { File(it).delete() } }
            dao.delete(item.id)
        }
    }

    /** Clears the active profile's played items only; other languages' playlists are untouched. */
    suspend fun deleteAllPlayed() {
        val lang = settingsRepo.activeProfileTag.first()
        dao.playedItems(lang).forEach { item -> item.filePath?.let { runCatching { File(it).delete() } } }
        dao.deletePlayed(lang)
    }

    /**
     * Retention pass for one language: deletes items played longer than [days] ago. No-op when
     * [days] is 0 ("keep forever"). Both arguments are explicit because this runs from background
     * maintenance for every profile, not just the active one.
     */
    suspend fun pruneOldPlayed(lang: String, days: Int) {
        if (days <= 0) return
        val cutoff = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        for (item in dao.playedBefore(lang, cutoff)) {
            item.filePath?.let { runCatching { File(it).delete() } }
            dao.delete(item.id)
        }
    }

    private fun defaultTitle(text: String): String =
        text.trim().lineSequence().firstOrNull { it.isNotBlank() }?.take(60)?.trim().orEmpty()
            .ifBlank { "Audio" }
}
