package com.copiosa.data.cloze.sources

import com.copiosa.data.cloze.ClozeSource
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.translate.DictionaryResult
import com.copiosa.data.translate.LocalDictionary
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
    private val dictionary: LocalDictionary,
) : ClozeSource {

    override val id = "context"

    override suspend fun mine(ctx: ClozeMineContext, limit: Int): List<ClozeCandidate> {
        // Only cards the bank has never taken a sentence from, so a run can't spend its whole
        // budget re-offering the same head of the deck — see FlashcardDao.clozeMineCandidates.
        // Over-read, because a card whose sentence doesn't actually spell the word yields nothing.
        val cards = flashcardDao.clozeMineCandidates(ctx.lang, limit * CARD_HEADROOM)
        if (cards.isEmpty()) return emptyList()
        val formsByCard = highlight.formsByCard(cards)

        // Asking the dictionary which headword a form belongs to is the fallback for forms the
        // card's cached list doesn't carry; without a pack there is simply no fallback.
        val canLemmatise = dictionary.isDownloaded(ctx.lang)
        var lookups = 0

        val out = ArrayList<ClozeCandidate>(limit)
        for (card in cards) {
            if (out.size >= limit) break
            if (!ctx.wantsSentencesFor(card.id)) continue
            val term = termOf(card)
            val lemma = normalizeWord(term)
            if (lemma.isEmpty() || !ctx.hasRoomFor(lemma)) continue
            val forms = formsByCard[card.id].orEmpty().ifEmpty { setOf(lemma) }
            val sentences = Sentences.split(card.contextSentence.orEmpty())

            // One card's context field can hold more than one sentence; take the first that actually
            // contains the word, since a two-sentence example often only uses it in one of them.
            var candidate = sentences.firstNotNullOfOrNull { sentence ->
                if (lemma.any(Char::isWhitespace)) phraseIn(sentence, term, card, lemma)
                else wordIn(sentence, card, lemma) { normalizeWord(it) in forms }
            }
            // A stem-changing verb's example says "duerme" where the card's forms say "dormir" and
            // its regular endings. The dictionary knows which headword every form belongs to.
            if (candidate == null && canLemmatise && lemma.none(Char::isWhitespace) && lookups < LOOKUPS_PER_RUN) {
                candidate = sentences.firstNotNullOfOrNull { sentence ->
                    val hit = TOKEN.findAll(sentence).drop(1).firstOrNull { token ->
                        token.value.length > 2 && lookups++ < LOOKUPS_PER_RUN && headwordsOf(token.value, ctx.lang).contains(lemma)
                    }
                    hit?.let { wordIn(sentence, card, lemma) { form -> form == it.value } }
                }
            }
            if (candidate != null) out += candidate
        }
        return out
    }

    /** The card's term without a trailing hint in brackets: "callejero (papel)" teaches "callejero". */
    private fun termOf(card: FlashcardEntity): String = card.targetTerm.replace(TRAILING_HINT, "").trim()

    private suspend fun headwordsOf(form: String, lang: String): Set<String> =
        (dictionary.lookup(form, lang) as? DictionaryResult.Found)
            ?.entries?.mapTo(HashSet()) { normalizeWord(it.lemma) }
            .orEmpty()

    /**
     * Locates the card's word in [sentence] and describes the blank, or null when the sentence
     * doesn't actually contain it — which happens often enough to matter, because the enrichment
     * pass sometimes illustrates a related word.
     */
    private inline fun wordIn(
        sentence: String,
        card: FlashcardEntity,
        lemma: String,
        matches: (String) -> Boolean,
    ): ClozeCandidate? {
        val hit = TOKEN.findAll(sentence).firstOrNull { matches(it.value) } ?: return null
        return candidate(sentence, hit.value, hit.range.first, card, lemma)
    }

    /**
     * A multi-word term, found whole. Only a literal run is matched — "a pesar de", "sin embargo" —
     * so a phrase whose verb is conjugated in the example ("me di cuenta" for *darse cuenta*) is
     * left alone rather than half-blanked.
     */
    private fun phraseIn(sentence: String, term: String, card: FlashcardEntity, lemma: String): ClozeCandidate? {
        val pattern = Regex("(?<![\\p{L}\\p{M}])" + Regex.escape(term) + "(?![\\p{L}\\p{M}])", RegexOption.IGNORE_CASE)
        val hit = pattern.find(sentence) ?: return null
        return candidate(sentence, hit.value, hit.range.first, card, lemma)
    }

    private fun candidate(sentence: String, answer: String, start: Int, card: FlashcardEntity, lemma: String) =
        ClozeCandidate(
            sentence = sentence.trim(),
            answer = answer,
            // The trim above can shift every offset, so measure against the trimmed string.
            answerStart = start - (sentence.length - sentence.trimStart().length),
            lemma = lemma,
            cardId = card.id,
            // The card's back is a gloss of the *word*, not of the sentence, so it would be a
            // misleading translation. Better none than wrong; the translator fills it at banking.
            translation = null,
            source = id,
        )

    private companion object {
        /** Words including accented letters and internal apostrophes ("d'accord", "año"). */
        val TOKEN = Regex("[\\p{L}\\p{M}\\p{Nd}'’-]+")

        val TRAILING_HINT = Regex("\\s*\\([^)]*\\)\\s*$")

        /** Dictionary reads per pass for the headword fallback, so one run stays a trickle. */
        const val LOOKUPS_PER_RUN = 400

        /**
         * Cards read per candidate asked for. Roughly a fifth of the deck's example sentences do
         * not spell their own word in a form the card knows (multi-word terms, stem-changing
         * verbs), and those yield nothing; without the headroom a run would come back short of its
         * budget even with plenty of material left.
         */
        const val CARD_HEADROOM = 4
    }
}
