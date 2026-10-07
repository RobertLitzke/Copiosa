package com.copiosa.data.cloze.sources

import com.copiosa.data.cloze.ClozeSource
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.highlight.HighlightTargetProvider
import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.domain.text.Sentences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mines the example sentence already stored on each card.
 *
 * The best source in the app and the cheapest: [FlashcardEntity.contextSentence] is either the
 * sentence the learner was reading when they saved the word, or one the enrichment pass wrote for
 * that exact word. Either way it is on-topic by construction, needs no dictionary pack, no network
 * and no book, and costs one query.
 *
 * It is also what makes the mode work on day one — the bank can be stocked from the deck alone, so
 * the screen is never empty waiting for a background pass.
 */
@Singleton
class ContextSentenceSource @Inject constructor(
    private val flashcardDao: FlashcardDao,
    private val highlight: HighlightTargetProvider,
) : ClozeSource {

    override val id = "context"

    override suspend fun mine(ctx: ClozeMineContext, limit: Int): List<ClozeCandidate> {
        // Only cards the bank has never taken a sentence from, so a run can't spend its whole
        // budget re-offering the same head of the deck — see FlashcardDao.clozeMineCandidates.
        // Over-read, because a card whose sentence doesn't actually spell the word yields nothing.
        val cards = flashcardDao.clozeMineCandidates(ctx.lang, limit * CARD_HEADROOM)
        if (cards.isEmpty()) return emptyList()
        val formsByCard = highlight.formsByCard(cards)

        val out = ArrayList<ClozeCandidate>(limit)
        for (card in cards) {
            if (out.size >= limit) break
            val lemma = normalizeWord(card.targetTerm)
            if (lemma.isEmpty() || !ctx.hasRoomFor(lemma)) continue
            val forms = formsByCard[card.id].orEmpty().ifEmpty { setOf(lemma) }

            // One card's context field can hold more than one sentence; take the first that actually
            // contains the word, since a two-sentence example often only uses it in one of them.
            val candidate = Sentences.split(card.contextSentence.orEmpty())
                .firstNotNullOfOrNull { sentence -> candidateFor(sentence, card, lemma, forms) }
            if (candidate != null) out += candidate
        }
        return out
    }

    /**
     * Locates the card's word in [sentence] and describes the blank, or null when the sentence
     * doesn't actually contain it — which happens often enough to matter, because the enrichment
     * pass sometimes illustrates a related word.
     */
    private fun candidateFor(
        sentence: String,
        card: FlashcardEntity,
        lemma: String,
        forms: Set<String>,
    ): ClozeCandidate? {
        val tokens = TOKEN.findAll(sentence).toList()
        val hit = tokens.firstOrNull { normalizeWord(it.value) in forms } ?: return null
        return ClozeCandidate(
            sentence = sentence.trim(),
            answer = hit.value,
            // The trim above can shift every offset, so measure against the trimmed string.
            answerStart = hit.range.first - (sentence.length - sentence.trimStart().length),
            lemma = lemma,
            cardId = card.id,
            // The card's back is a gloss of the *word*, not of the sentence, so it would be a
            // misleading translation. Better none than wrong; a later source can supply a real one.
            translation = null,
            source = id,
        )
    }

    private companion object {
        /** Words including accented letters and internal apostrophes ("d'accord", "año"). */
        val TOKEN = Regex("[\\p{L}\\p{M}\\p{Nd}'’-]+")

        /**
         * Cards read per candidate asked for. Roughly a fifth of the deck's example sentences do
         * not spell their own word in a form the card knows (multi-word terms, stem-changing
         * verbs), and those yield nothing; without the headroom a run would come back short of its
         * budget even with plenty of material left.
         */
        const val CARD_HEADROOM = 4
    }
}
