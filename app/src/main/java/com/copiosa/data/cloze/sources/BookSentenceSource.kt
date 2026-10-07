package com.copiosa.data.cloze.sources

import com.copiosa.data.cloze.ClozeSource
import com.copiosa.data.local.BookDao
import com.copiosa.data.local.BookParagraphEntity
import com.copiosa.domain.book.nativeText
import com.copiosa.domain.book.targetText
import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.highlight.normalizeWord
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
 */
@Singleton
class BookSentenceSource @Inject constructor(
    private val bookDao: BookDao,
) : ClozeSource {

    override val id = "book"

    override suspend fun mine(ctx: ClozeMineContext, limit: Int): List<ClozeCandidate> {
        val out = ArrayList<ClozeCandidate>(limit)
        for (book in bookDao.booksFor(ctx.lang)) {
            if (out.size >= limit) break
            if (book.posParagraph <= 0) continue

            val paragraphs = bookDao.readParagraphs(book.id, book.posParagraph, PARAGRAPHS_PER_BOOK)
            for (paragraph in paragraphs) {
                if (out.size >= limit) break
                out += candidatesIn(paragraph, book.direction, book.id, ctx, limit - out.size)
            }
        }
        return out
    }

    private fun candidatesIn(
        paragraph: BookParagraphEntity,
        direction: BookDirection,
        bookId: Long,
        ctx: ClozeMineContext,
        room: Int,
    ): List<ClozeCandidate> {
        val studyText = paragraph.targetText(direction)?.takeIf { it.isNotBlank() } ?: return emptyList()
        val sentences = Sentences.split(studyText)
        val glosses = glossesFor(paragraph, direction, sentences.size)

        val out = ArrayList<ClozeCandidate>()
        for ((index, sentence) in sentences.withIndex()) {
            if (out.size >= room) break
            val hit = TOKEN.findAll(sentence)
                .firstOrNull { ctx.formToCard.containsKey(normalizeWord(it.value)) } ?: continue
            val form = normalizeWord(hit.value)
            val cardId = ctx.formToCard[form] ?: continue
            // The lemma is the card's own headword form, so every sentence for one word shares a
            // cap key regardless of which inflection happened to appear here.
            val lemma = ctx.formsByCard[cardId]?.minByOrNull { it.length } ?: form
            if (!ctx.hasRoomFor(lemma)) continue

            out += ClozeCandidate(
                sentence = sentence,
                answer = hit.value,
                answerStart = hit.range.first,
                lemma = lemma,
                cardId = cardId,
                translation = glosses?.getOrNull(index),
                source = "$id:$bookId",
            )
        }
        return out
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

        val TOKEN = Regex("[\\p{L}\\p{M}\\p{Nd}'’-]+")
    }
}
