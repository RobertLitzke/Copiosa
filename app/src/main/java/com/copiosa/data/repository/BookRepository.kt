package com.copiosa.data.repository

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.copiosa.data.epub.BookMedia
import com.copiosa.data.local.AppDatabase
import com.copiosa.data.local.BookDao
import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.domain.book.BookChapter
import com.copiosa.domain.book.buildToc
import com.copiosa.domain.book.targetText
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.Role
import com.copiosa.domain.prompt.BookSectionAligner
import com.copiosa.domain.prompt.PromptBuilder
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data access + section translation for imported books. The translation protocol lives here:
 * one LLM call per section of `[[P<ord>]]`-marked paragraphs (see PromptBuilder's
 * bookSectionSystemPrompt), with the *previous* section riding along as a user/assistant exchange
 * so names and recurring vocabulary keep the translations they already got.
 *
 * Persistence follows runTextTranslation's crash-safe ordering: every paragraph's translation is
 * stored first, and only then does the frontier counter advance — a crash never claims more than
 * is actually saved. Callers should only ever translate the frontier section
 * ([BookEntity.translatedSections]); BookTranslationManager is the single writer that guarantees it.
 */
@Singleton
class BookRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val bookDao: BookDao,
    private val providers: ProviderRegistry,
    private val prompts: PromptBuilder,
) {
    fun observeBooks(lang: String): Flow<List<BookEntity>> = bookDao.observeBooks(lang)

    fun observeBook(id: Long): Flow<BookEntity?> = bookDao.observeBook(id)

    suspend fun getById(id: Long): BookEntity? = bookDao.getById(id)

    fun paragraphs(bookId: Long): PagingSource<Int, BookParagraphEntity> = bookDao.paragraphs(bookId)

    suspend fun sectionOf(bookId: Long, ord: Int): Int? = bookDao.sectionOf(bookId, ord)

    /**
     * The target-language text of one section (for passive vocab review of what's being read).
     * Direction-aware: on a REVERSE book the studied text is the EPUB's own, which is why this is
     * not simply "the translated text" — a reverse book may have no translation at all.
     */
    suspend fun sectionStudyText(bookId: Long, section: Int, direction: BookDirection): String =
        bookDao.sectionParagraphs(bookId, section)
            .mapNotNull { it.targetText(direction)?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")

    /** The reader's chapter list, with each chapter's readable extent against [frontier]. */
    suspend fun tableOfContents(
        bookId: Long,
        direction: BookDirection,
        frontier: Int,
    ): List<BookChapter> = buildToc(
        direction = direction,
        frontier = frontier,
        spans = bookDao.chapterSpans(bookId),
        headings = bookDao.headingRows(bookId),
    )

    suspend fun saveScroll(id: Long, ord: Int, offset: Int) = bookDao.saveScroll(id, ord, offset)

    suspend fun setStarred(id: Long, starred: Boolean) = bookDao.setStarred(id, starred)

    suspend fun setFinishedReading(id: Long, finished: Boolean) = bookDao.setFinishedReading(id, finished)

    suspend fun setAutoTranslate(id: Long, on: Boolean) = bookDao.setAutoTranslate(id, on)

    suspend fun recordAutoRetry(id: Long, now: Long) = bookDao.recordAutoRetry(id, now)

    suspend fun pendingSlowTranslate(lang: String, maxAttempts: Int, limit: Int): List<BookEntity> =
        bookDao.pendingSlowTranslate(lang, maxAttempts, limit)

    /** Deletes the book, its paragraphs (cascade) and its cover file. Cancel any job upstream first. */
    suspend fun delete(id: Long) {
        bookDao.getById(id)?.coverFile?.let { BookMedia.file(context, it).delete() }
        bookDao.delete(id)
    }

    /**
     * Translates exactly one [section] of [bookId] and advances the frontier past it. On the first
     * section the book's title is translated too (its own small call, the article-title idiom).
     * A misaligned reply gets ONE corrective retry; whatever alignment survives is persisted —
     * unmatched paragraphs keep a null translation and render as muted original text.
     */
    suspend fun translateSection(bookId: Long, section: Int) {
        val book = bookDao.getById(bookId) ?: return
        if (section >= book.totalSections) return
        val provider = providers.forId(book.provider)
        val now = { System.currentTimeMillis() }

        // A reverse book's title is already in the target language — translating it into the
        // learner's own language and writing it back would rename the book on its own shelf.
        val translateTitle = book.direction == BookDirection.FORWARD &&
            book.title == book.originalTitle &&
            book.originalTitle.isNotBlank()
        if (section == 0 && translateTitle) {
            val translatedTitle = provider.chat(
                LlmRequest(
                    systemPrompt = prompts.textTranslationSystemPrompt(
                        country = book.country,
                        targetLang = book.targetLanguage,
                        nativeLang = book.nativeLanguage,
                        level = book.level,
                    ),
                    messages = listOf(LlmMessage(Role.USER, book.originalTitle.trim())),
                    model = book.model,
                    maxTokens = 256,
                ),
            ).text.trim()
            if (translatedTitle.isNotBlank()) bookDao.setTitle(bookId, translatedTitle.take(120))
            delay(TITLE_PACING_MS)
        }

        val paragraphs = bookDao.sectionParagraphs(bookId, section)
        if (paragraphs.isEmpty()) {
            // Shouldn't happen (sections are dense), but never let an empty section wedge the loop.
            bookDao.setTranslatedSections(bookId, section + 1, now())
            return
        }

        val systemPrompt = prompts.bookSectionSystemPrompt(
            country = book.country,
            targetLang = book.targetLanguage,
            nativeLang = book.nativeLanguage,
            level = book.level,
            direction = book.direction,
            bookTitle = book.title,
            part = section + 1,
            totalParts = book.totalSections,
        )
        val sectionMessage = markedUp(paragraphs) { it.originalText }
        val history = continuityHistory(bookId, section)
        val expectedOrds = paragraphs.map { it.ord }

        val raw = provider.chat(
            LlmRequest(
                systemPrompt = systemPrompt,
                messages = history + LlmMessage(Role.USER, sectionMessage),
                model = book.model,
                maxTokens = SECTION_MAX_TOKENS,
            ),
        ).text

        var result = BookSectionAligner.parse(raw, expectedOrds)
        if (result is BookSectionAligner.Result.Misaligned) {
            delay(RETRY_PACING_MS)
            val correction = "Your previous output did not follow the marker format. Output EXACTLY " +
                "these markers once each, in this order, each on its own line followed by that " +
                "paragraph's translation: " + expectedOrds.joinToString(" ") { "[[P$it]]" }
            val retry = provider.chat(
                LlmRequest(
                    systemPrompt = systemPrompt,
                    messages = history + listOf(
                        LlmMessage(Role.USER, sectionMessage),
                        LlmMessage(Role.ASSISTANT, raw),
                        LlmMessage(Role.USER, correction),
                    ),
                    model = book.model,
                    maxTokens = SECTION_MAX_TOKENS,
                ),
            ).text
            val retried = BookSectionAligner.parse(retry, expectedOrds)
            if (retried is BookSectionAligner.Result.Aligned || retried.byOrd.size > result.byOrd.size) {
                result = retried
            }
        }

        val byOrd = result.byOrd
        db.withTransaction {
            // Content first, frontier second — the crash-safe ordering rule.
            for (p in paragraphs) {
                byOrd[p.ord]?.takeIf { it.isNotBlank() }?.let { bookDao.setTranslated(p.id, it) }
            }
            bookDao.setTranslatedSections(bookId, section + 1, now())
        }
    }

    /**
     * The previous section as a completed exchange — the model sees its own earlier translations
     * (names, recurring vocab) without being asked to re-translate them. Empty when [section] is 0
     * or the previous section has nothing usable.
     */
    private suspend fun continuityHistory(bookId: Long, section: Int): List<LlmMessage> {
        if (section == 0) return emptyList()
        val prev = bookDao.sectionParagraphs(bookId, section - 1)
        val translated = prev.filter { !it.translatedText.isNullOrBlank() }
        if (translated.isEmpty()) return emptyList()
        return listOf(
            LlmMessage(Role.USER, markedUp(prev) { it.originalText }),
            LlmMessage(Role.ASSISTANT, markedUp(translated) { it.translatedText.orEmpty() }),
        )
    }

    private fun markedUp(paragraphs: List<BookParagraphEntity>, text: (BookParagraphEntity) -> String): String =
        paragraphs.joinToString("\n\n") { "[[P${it.ord}]]\n${text(it)}" }

    companion object {
        /** Generous output room for ~4000 source chars of level-adapted translation + markers. */
        const val SECTION_MAX_TOKENS = 8192

        /** Gap after the title call / before a corrective retry (per-minute rate-cap safety). */
        const val TITLE_PACING_MS = 4_000L
        const val RETRY_PACING_MS = 4_000L
    }
}
