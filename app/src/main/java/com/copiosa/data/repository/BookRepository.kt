package com.copiosa.data.repository

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.withTransaction
import com.copiosa.data.epub.BookMedia
import com.copiosa.data.local.AppDatabase
import com.copiosa.data.local.BookDao
import com.copiosa.data.local.BookEntity
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.data.local.BookSectionEntity
import com.copiosa.data.local.ModelSectionCount
import com.copiosa.data.local.SectionSize
import com.copiosa.data.local.StaleSections
import com.copiosa.data.remote.LlmError
import com.copiosa.data.remote.LlmException
import com.copiosa.data.remote.LlmMessage
import com.copiosa.data.remote.LlmProvider
import com.copiosa.data.remote.LlmRequest
import com.copiosa.data.remote.ProviderRegistry
import com.copiosa.data.remote.chatOrEmpty
import com.copiosa.data.settings.SettingsRepository
import com.copiosa.domain.book.BookChapter
import com.copiosa.domain.book.buildToc
import com.copiosa.domain.book.speechTextOf
import com.copiosa.domain.book.targetText
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.model.BookDownloadMode
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.Role
import com.copiosa.domain.prompt.BookSectionAligner
import com.copiosa.domain.prompt.PromptBuilder
import com.copiosa.domain.reading.ReadMode
import com.copiosa.domain.usage.LlmFeature
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
    private val audioRepo: AudioRepository,
    private val providers: ProviderRegistry,
    private val prompts: PromptBuilder,
    private val settingsRepo: SettingsRepository,
) {
    fun observeBooks(lang: String): Flow<List<BookEntity>> = bookDao.observeBooks(lang)

    fun observeBook(id: Long): Flow<BookEntity?> = bookDao.observeBook(id)

    suspend fun getById(id: Long): BookEntity? = bookDao.getById(id)

    fun paragraphs(bookId: Long): PagingSource<Int, BookParagraphEntity> = bookDao.paragraphs(bookId)

    suspend fun sectionOf(bookId: Long, ord: Int): Int? = bookDao.sectionOf(bookId, ord)

    /** The note a `[^label]` marker in [chapterIndex] refers to, for the reader's footnote sheet. */
    suspend fun note(bookId: Long, chapterIndex: Int, label: String): BookParagraphEntity? =
        bookDao.noteRow(bookId, chapterIndex, "[^$label]:")

    /**
     * The target-language text of one section (for passive vocab review of what's being read).
     * Direction-aware: on a REVERSE book the studied text is the EPUB's own, which is why this is
     * not simply "the translated text" — a reverse book may have no translation at all.
     */
    suspend fun sectionStudyText(bookId: Long, section: Int, direction: BookDirection): String =
        bookDao.sectionParagraphs(bookId, section)
            .mapNotNull { it.targetText(direction)?.takeIf(String::isNotBlank) }
            .joinToString("\n\n")

    /**
     * The speakable text of a run of sections — one audio track's script. Blank sections drop out
     * rather than leaving a gap, so an under-translated or image-only run returns "" and the caller
     * can skip the track entirely.
     */
    suspend fun speechText(bookId: Long, sections: IntRange, direction: BookDirection): String =
        sections
            .map { speechTextOf(bookDao.sectionParagraphs(bookId, it), direction) }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")

    /** Per-section text volume, for sizing audio tracks without loading the whole book. */
    suspend fun sectionSizes(bookId: Long): List<SectionSize> = bookDao.sectionSizes(bookId)

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

    suspend fun saveScroll(id: Long, ord: Int, offset: Int) =
        bookDao.saveScroll(id, ord, offset, System.currentTimeMillis())

    /** Persists the language version this book was last read in, restored beside the position. */
    suspend fun setReadMode(id: Long, mode: ReadMode) = bookDao.setReadMode(id, mode)

    suspend fun setStarred(id: Long, starred: Boolean) = bookDao.setStarred(id, starred)

    suspend fun setFinishedReading(id: Long, finished: Boolean) = bookDao.setFinishedReading(id, finished)

    suspend fun setDownloadMode(id: Long, mode: BookDownloadMode) = bookDao.setDownloadMode(id, mode)

    /** Repoints later sections at another model; existing sections keep the model that made them. */
    suspend fun setModel(id: Long, provider: ProviderId, model: String) =
        bookDao.setModel(id, provider, model)

    /** Translated sections that did not come from [model] — the re-translate offer's input. */
    suspend fun staleSections(bookId: Long, model: String): StaleSections =
        bookDao.staleSections(bookId, model)

    suspend fun sectionsByModel(bookId: Long): List<ModelSectionCount> = bookDao.sectionsByModel(bookId)

    /** Per-book override of the language's highlight setting; null returns the book to following it. */
    suspend fun setHighlightWords(id: Long, on: Boolean?) = bookDao.setHighlightWords(id, on)

    suspend fun recordAutoRetry(id: Long, now: Long) = bookDao.recordAutoRetry(id, now)

    /** Records why the last attempt failed; cleared by the next section that stores. */
    suspend fun setLastError(id: Long, code: String?) = bookDao.setLastError(id, code)

    suspend fun pendingSlowTranslate(lang: String, limit: Int): List<BookEntity> =
        bookDao.pendingSlowTranslate(lang, limit)

    /**
     * Deletes the book, its paragraphs (cascade), its images and its generated audio. Cancel any job
     * upstream first.
     */
    suspend fun delete(id: Long) {
        bookDao.getById(id)?.coverFile?.let { BookMedia.file(context, it).delete() }
        // Body images are not in the database, so nothing cascades to them — an illustrated book
        // deleted without this leaves its whole plate section on disk forever.
        BookMedia.imagesDir(context, id).deleteRecursively()
        // Audio rows have no foreign key to books (see MIGRATION_35_36_SQL), and a novel's tracks are
        // hundreds of megabytes — the same reasoning as the images, one order of magnitude up.
        audioRepo.deleteForBook(id)
        bookDao.delete(id)
    }

    /**
     * Translates exactly one [section] of [bookId] and advances the frontier past it. On the first
     * section the book's title is translated too (its own small call, the article-title idiom).
     * A misaligned reply gets ONE corrective retry, and a reply that ran out of output room gets
     * ONE call for the paragraphs it did not reach (see [finishTruncated]); whatever alignment
     * survives is persisted — unmatched paragraphs keep a null translation and render as muted
     * original text.
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
            // A few words of translation: the background slot's kind of call. The book's own model
            // is the fallback, since that — not the profile's — is what its sections use.
            val titleModel = settingsRepo.settingsFor(book.targetLanguage)
                .backgroundModelFor(book.provider, fallback = book.model)
            val translatedTitle = provider.chatOrEmpty(
                LlmRequest(
                    systemPrompt = prompts.textTranslationSystemPrompt(
                        country = book.country,
                        targetLang = book.targetLanguage,
                        nativeLang = book.nativeLanguage,
                        level = book.level,
                    ),
                    messages = listOf(LlmMessage(Role.USER, book.originalTitle.trim())),
                    model = titleModel,
                    feature = LlmFeature.BOOK,
                    bookId = bookId,
                    maxTokens = 256,
                ),
            ).trim()
            if (translatedTitle.isNotBlank()) bookDao.setTitle(bookId, translatedTitle.take(120))
            delay(TITLE_PACING_MS)
        }

        val paragraphs = bookDao.sectionParagraphs(bookId, section).translatable()
        if (paragraphs.isEmpty()) {
            // Either a section that is somehow empty (sections are dense, so this shouldn't happen)
            // or — quite realistically, in a book with a plate section — one holding nothing but
            // images. Both have nothing to ask for; sending the request anyway would spend a call
            // on an empty message and then a second one on the misalignment retry.
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

        val reply = provider.chat(
            LlmRequest(
                systemPrompt = systemPrompt,
                messages = history + LlmMessage(Role.USER, sectionMessage),
                model = book.model,
                feature = LlmFeature.BOOK,
                bookId = bookId,
                maxTokens = SECTION_MAX_TOKENS,
            ),
        )
        val raw = reply.text

        var result = BookSectionAligner.parse(raw, expectedOrds)
        if (reply.truncated) {
            // Out of room, not out of line: the corrective retry below would ask for the whole
            // section again and be cut at the same place, paying twice for the same half.
            val done = BookSectionAligner.completedPrefix(raw, expectedOrds)
            if (done.isNotEmpty()) {
                delay(RETRY_PACING_MS)
                result = finishTruncated(provider, book, systemPrompt, history, paragraphs, done)
            }
        } else if (result is BookSectionAligner.Result.Misaligned) {
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
                    feature = LlmFeature.BOOK,
                    bookId = bookId,
                    maxTokens = SECTION_MAX_TOKENS,
                ),
            ).text
            val retried = BookSectionAligner.parse(retry, expectedOrds)
            if (retried is BookSectionAligner.Result.Aligned || retried.byOrd.size > result.byOrd.size) {
                result = retried
            }
        }

        val byOrd = result.byOrd
        // Nothing usable came back at all. A safety refusal or an empty candidate arrives as a 200
        // with no text, not as an HTTP error, so it reaches here looking like a successful call that
        // translated the section into nothing — and the write below would then advance the frontier
        // past it. The frontier is a high-water mark (see BookDao.setTranslatedSections), so that is
        // not a section the normal loop could ever come back for: it would be silently lost for the
        // life of the book. Fail instead, and let the caller's retry bookkeeping have it.
        if (byOrd.values.none { it.isNotBlank() }) throw LlmException(LlmError.Refused)

        db.withTransaction {
            // Content first, frontier second — the crash-safe ordering rule.
            for (p in paragraphs) {
                byOrd[p.ord]?.takeIf { it.isNotBlank() }?.let { bookDao.setTranslated(p.id, it) }
            }
            // Which model this section came from, in the same transaction as the text it describes,
            // so the provenance can never outlive (or precede) the translation it claims.
            bookDao.upsertSection(
                BookSectionEntity(
                    bookId = bookId,
                    sectionIndex = section,
                    provider = book.provider,
                    model = book.model,
                    translatedAt = now(),
                ),
            )
            bookDao.setTranslatedSections(bookId, section + 1, now())
        }
    }

    /**
     * Picks up a section whose reply ran out of output room: [done] is what it finished, and one
     * further call asks for only the paragraphs after that. One call, like the corrective retry —
     * if this one is cut short as well, what it finished is kept and the rest stay untranslated
     * rows, rather than chasing a section that will not fit.
     */
    private suspend fun finishTruncated(
        provider: LlmProvider,
        book: BookEntity,
        systemPrompt: String,
        history: List<LlmMessage>,
        paragraphs: List<BookParagraphEntity>,
        done: Map<Int, String>,
    ): BookSectionAligner.Result {
        val rest = paragraphs.filter { it.ord !in done }
        val restOrds = rest.map { it.ord }
        val more = provider.chat(
            LlmRequest(
                systemPrompt = systemPrompt,
                messages = history + LlmMessage(Role.USER, markedUp(rest) { it.originalText }),
                model = book.model,
                feature = LlmFeature.BOOK,
                bookId = book.id,
                maxTokens = SECTION_MAX_TOKENS,
            ),
        )
        val tail = if (more.truncated) {
            BookSectionAligner.completedPrefix(more.text, restOrds)
        } else {
            BookSectionAligner.parse(more.text, restOrds).byOrd
        }
        val merged = done + tail
        val complete = paragraphs.all { !merged[it.ord].isNullOrBlank() }
        return if (complete) BookSectionAligner.Result.Aligned(merged) else BookSectionAligner.Result.Misaligned(merged)
    }

    /**
     * The previous section as a completed exchange — the model sees its own earlier translations
     * (names, recurring vocab) without being asked to re-translate them. Empty when [section] is 0
     * or the previous section has nothing usable.
     */
    private suspend fun continuityHistory(bookId: Long, section: Int): List<LlmMessage> {
        if (section == 0) return emptyList()
        val prev = bookDao.sectionParagraphs(bookId, section - 1).translatable()
        val translated = prev.filter { !it.translatedText.isNullOrBlank() }
        if (translated.isEmpty()) return emptyList()
        return listOf(
            LlmMessage(Role.USER, markedUp(prev) { it.originalText }),
            LlmMessage(Role.ASSISTANT, markedUp(translated) { it.translatedText.orEmpty() }),
        )
    }

    private fun markedUp(paragraphs: List<BookParagraphEntity>, text: (BookParagraphEntity) -> String): String =
        paragraphs.joinToString("\n\n") { "[[P${it.ord}]]\n${text(it)}" }

    /**
     * The rows of a section that are actually asked for. Images are the same in every language and
     * are stored already "translated" at import, so sending them would spend tokens on a filename
     * and — worse, via [continuityHistory] — show the model an assistant turn in which it appeared
     * to emit image markdown, which is a fine way to teach it to invent some.
     */
    private fun List<BookParagraphEntity>.translatable(): List<BookParagraphEntity> =
        filterNot { it.kind == BookParagraphEntity.KIND_IMAGE }

    companion object {
        /** Generous output room for ~4000 source chars of level-adapted translation + markers. */
        const val SECTION_MAX_TOKENS = 8192

        /** Gap after the title call / before a corrective retry (per-minute rate-cap safety). */
        const val TITLE_PACING_MS = 4_000L
        const val RETRY_PACING_MS = 4_000L
    }
}
