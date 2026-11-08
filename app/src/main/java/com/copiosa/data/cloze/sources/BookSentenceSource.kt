package com.copiosa.data.cloze.sources

import com.copiosa.data.cloze.ClozeSource
import com.copiosa.data.local.BookDao
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.book.nativeText
import com.copiosa.domain.book.targetText
import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.model.BookDirection
import com.copiosa.domain.text.Sentences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mines sentences out of the books the learner has actually read.
 *
 * This is the richest source in the app, for a structural reason: a book paragraph stores its
 * original and its translation **on the same row**, so a mined sentence can carry a real gloss
 * without any alignment work at the paragraph level.
 *
 * The scan is bounded to paragraphs before the reading position. That is not only a performance
 * bound — it is the "tied to study that's already happened" rule made literal. A sentence from
 * chapter thirty of an unread novel is exactly the kind of disconnected material this mode exists to
 * avoid.
 *
 * Which word is blanked is [SentenceBlanks]' decision, shared with the other reading sources.
 */
@Singleton
class BookSentenceSource @Inject constructor(
    private val bookDao: BookDao,
) : ClozeSource {

    override val id = "book"

    override suspend fun mine(ctx: ClozeMineContext, limit: Int): List<ClozeCandidate> {
        val carded = ArrayList<ClozeCandidate>(limit)
        val uncarded = ArrayList<ClozeCandidate>()
        val proposed = HashSet<String>()
        for (book in bookDao.booksFor(ctx.lang)) {
            if (carded.size >= limit) break
            if (book.posParagraph <= 0) continue

            val paragraphs = bookDao.readParagraphs(book.id, book.posParagraph, PARAGRAPHS_PER_BOOK)
            for (paragraph in paragraphs) {
                if (carded.size >= limit) break
                for (picked in candidatesIn(paragraph, book.direction, book.id, ctx, proposed)) {
                    if (picked.carded) carded += picked.candidate else uncarded += picked.candidate
                }
            }
        }
        // Words the learner is studying come first and take what they need; common words they have
        // no card for get a share, never the run.
        return carded.take(limit) + uncarded.take(maxOf(1, limit / UNCARDED_SHARE))
    }

    private fun candidatesIn(
        paragraph: BookParagraphEntity,
        direction: BookDirection,
        bookId: Long,
        ctx: ClozeMineContext,
        proposed: MutableSet<String>,
    ): List<SentenceBlanks.Picked> {
        val studyText = paragraph.targetText(direction)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val sentences = Sentences.split(studyText)
        val glosses = glossesFor(paragraph, direction, sentences.size)

        return sentences.withIndex().mapNotNull { (index, raw) ->
            val sentence = SentenceBlanks.clean(raw)
            if (!SentenceBlanks.comfortable(sentence)) return@mapNotNull null
            val gloss = glosses?.getOrNull(index)?.let(SentenceBlanks::clean)
            SentenceBlanks.pick(sentence, ctx, "$id:$bookId", gloss, proposed)
        }
    }

    /**
     * Per-sentence glosses, but **only when the two sides split into the same number of sentences**.
     *
     * The translation on the row is of the whole paragraph. Handing a learner one sentence with a
     * neighbouring sentence's gloss under it is worse than showing no gloss at all, and nothing here
     * aligns below paragraph level, so anything but an exact count match returns null.
     */
    private fun glossesFor(
        paragraph: BookParagraphEntity,
        direction: BookDirection,
        sentenceCount: Int,
    ): List<String>? {
        val native = paragraph.nativeText(direction)?.takeIf { it.isNotBlank() } ?: return null
        return Sentences.split(native).takeIf { it.size == sentenceCount }
    }

    private companion object {
        /** Recent paragraphs to scan per book per pass — a trickle, in the BookTranslateTask mould. */
        const val PARAGRAPHS_PER_BOOK = 120

        /** One uncarded common word for every this many candidates a run may offer. */
        const val UNCARDED_SHARE = 4
    }
}
