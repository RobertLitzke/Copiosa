package com.copiosa.data.generation

import android.content.Context
import com.copiosa.R
import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.SectionSize
import com.copiosa.data.repository.AudioRepository
import com.copiosa.data.repository.BookRepository
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.book.BookChapter
import com.copiosa.domain.book.ChapterProgress
import com.copiosa.domain.book.chunkChapter
import com.copiosa.domain.book.trackTitle
import com.copiosa.domain.model.BookDirection
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** What "Generate audio" would produce right now, for the confirmation dialog. */
data class BookAudioPlan(
    val bookId: Long,
    /** The folder these tracks would land in — an existing one, or the book's title. */
    val tag: String,
    val readyChapters: Int,
    val totalChapters: Int,
    /** Tracks that would be created by generating now. Zero means everything ready already exists. */
    val newTracks: Int,
    val existingTracks: Int,
    val estimatedMs: Long,
    val estimatedBytes: Long,
) {
    /** Whether the frontier still has chapters to reach — the "the rest will follow" case. */
    val partial: Boolean get() = readyChapters < totalChapters
}

/**
 * Turns an imported book into playlist items: one track per chapter (split when a chapter runs long,
 * see [chunkChapter]), all sharing a tag so the Audio pane can present them as a folder.
 *
 * This only *enqueues*. Rendering stays with [AudioGenerationManager]'s single
 * [com.copiosa.data.tts.TtsRenderQueue] — the whole playlist drains through one consumer, because
 * two would mean two synthesis jobs on one native engine, and `AudioItemDao.unrendered` already
 * orders untagged items first so a novel cannot starve a chat reply.
 *
 * A track's text is snapshotted into the row at enqueue, exactly as the playlist does today, so the
 * render path never touches the book and a later re-translation cannot silently change what a queued
 * track will say.
 */
@Singleton
class BookAudioManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bookRepo: BookRepository,
    private val audioRepo: AudioRepository,
    private val settingsRepo: SettingsRepository,
    private val audioGen: AudioGenerationManager,
) {

    /** What generating now would add, without adding it. Null when the book is gone. */
    suspend fun plan(bookId: Long): BookAudioPlan? {
        val book = bookRepo.getById(bookId) ?: return null
        val chapters = bookRepo.tableOfContents(bookId, book.direction, book.translatedSections)
        val done = audioRepo.chaptersWithAudio(bookId).toSet()
        val sizes = sizeMap(bookRepo.sectionSizes(bookId), book.direction)

        val pending = chapters.filter { it.progress == ChapterProgress.FULL && it.index !in done }
        val chunks = pending.flatMap { chunkChapter(it, sizes) }
        val chars = chunks.sumOf { chunk -> chunk.sections.sumOf { sizes[it] ?: 0 }.toLong() }
        val ms = estimatedMs(chars)

        return BookAudioPlan(
            bookId = bookId,
            tag = tagFor(book),
            readyChapters = chapters.count { it.progress == ChapterProgress.FULL },
            totalChapters = chapters.size,
            newTracks = chunks.size,
            existingTracks = done.size,
            estimatedMs = ms,
            estimatedBytes = ms / 1000 * BYTES_PER_SECOND,
        )
    }

    /**
     * Enqueues every fully-translated chapter that has no track yet, and returns how many tracks
     * were added. Idempotent: re-running it after the translation frontier moves adds only the
     * chapters that became readable in between, which is exactly what
     * [com.copiosa.data.maintenance.tasks.BookAudioTask] relies on.
     */
    suspend fun enqueueReady(bookId: Long): Int {
        val book = bookRepo.getById(bookId) ?: return 0
        val chapters = bookRepo.tableOfContents(bookId, book.direction, book.translatedSections)
        val done = audioRepo.chaptersWithAudio(bookId).toSet()
        val sizes = sizeMap(bookRepo.sectionSizes(bookId), book.direction)
        val tag = tagFor(book)
        // Snapshot the voice at enqueue, as the playlist does, so a whole book speaks in one voice
        // even if the setting changes while its backlog renders.
        val sid = settingsRepo.settings.first().ttsVoiceSid

        var added = 0
        for (chapter in chapters) {
            if (chapter.progress != ChapterProgress.FULL || chapter.index in done) continue
            for (chunk in chunkChapter(chapter, sizes)) {
                val text = bookRepo.speechText(bookId, chunk.sections, book.direction)
                // A plate section, or a chapter that is nothing but a table: nothing to say.
                if (text.isBlank()) continue
                audioRepo.enqueue(
                    text = text,
                    title = trackTitle(chunk, chapter.title, fallbackTitle(chapter)),
                    langCode = book.targetLanguage,
                    sid = sid,
                    tag = tag,
                    sourceBookId = bookId,
                    sourceChapterIndex = chapter.index,
                    // Ordered within the folder rather than appended to the global playlist, so the
                    // book reads in chapter order however its tracks interleave with everything else.
                    sortOrder = chapter.index * CHAPTER_STRIDE + chunk.partIndex,
                )
                added++
            }
        }
        if (added > 0) audioGen.kick()
        return added
    }

    /**
     * Books that already have audio and whose translation frontier has since moved past a chapter
     * with no track — the maintenance backlog. Books that were never asked for audio are left alone:
     * generating a novel unasked is hours of CPU nobody requested.
     */
    suspend fun booksNeedingMoreAudio(lang: String): List<BookEntity> =
        bookRepo.observeBooks(lang).first()
            .filter { audioRepo.chaptersWithAudio(it.id).isNotEmpty() }
            .filter { book -> (plan(book.id)?.newTracks ?: 0) > 0 }

    private fun fallbackTitle(chapter: BookChapter): String =
        context.getString(R.string.book_chapter_n, chapter.index + 1)

    /** Keeps the folder this book's earlier tracks already opened; only names a new one from scratch. */
    private suspend fun tagFor(book: BookEntity): String =
        audioRepo.tagForBook(book.id)
            ?: book.title.ifBlank { book.originalTitle }.ifBlank { book.sourceFileName.orEmpty() }
                .ifBlank { context.getString(R.string.book_audio_untitled) }

    /** Section sizes on the side that will actually be spoken. */
    private fun sizeMap(sizes: List<SectionSize>, direction: BookDirection): Map<Int, Int> =
        sizes.associate { size ->
            size.sectionIndex to when (direction) {
                BookDirection.FORWARD -> size.translatedChars
                BookDirection.REVERSE -> size.originalChars
            }
        }

    private fun estimatedMs(chars: Long): Long =
        (chars / CHARS_PER_WORD / WORDS_PER_MINUTE * 60_000).toLong()

    private companion object {
        /** Room for 100 parts per chapter, which no chapter will ever need. */
        const val CHAPTER_STRIDE = 100.0

        // Rough enough for a dialog that says "about 11 hours": prose averages ~5.5 characters per
        // word including its space, and synthesized speech lands near 150 words a minute.
        const val CHARS_PER_WORD = 5.5
        const val WORDS_PER_MINUTE = 150.0

        /** M4aWriter encodes at 80 kbps. */
        const val BYTES_PER_SECOND = 10_000L
    }
}
