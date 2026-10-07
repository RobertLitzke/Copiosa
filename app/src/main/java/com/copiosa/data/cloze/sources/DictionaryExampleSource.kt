package com.copiosa.data.cloze.sources

import com.copiosa.data.cloze.ClozeSource
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.translate.DictionaryResult
import com.copiosa.data.translate.LocalDictionary
import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.highlight.targetTerm
import com.copiosa.domain.text.Sentences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mines the example sentences the offline dictionary pack already carries per sense.
 *
 * Most senses have none, so this is a thin seam rather than a rich one — but the examples it does
 * find are lexicographer-written for that exact headword, which makes them unusually good cloze
 * material, and they cost nothing but a local SQLite read.
 *
 * The pack is an optional download. With no pack this returns nothing and the mining pass carries on
 * with its other sources, which is the app's standing rule for dictionary-dependent work.
 */
@Singleton
class DictionaryExampleSource @Inject constructor(
    private val flashcardDao: FlashcardDao,
    private val dictionary: LocalDictionary,
) : ClozeSource {

    override val id = "dict"

    override suspend fun mine(ctx: ClozeMineContext, limit: Int): List<ClozeCandidate> {
        if (!dictionary.isDownloaded(ctx.lang)) return emptyList()

        val out = ArrayList<ClozeCandidate>(limit)
        var lookups = 0
        for (card in flashcardDao.allActive(ctx.lang)) {
            if (out.size >= limit || lookups >= LOOKUPS_PER_RUN) break
            val lemma = normalizeWord(card.targetTerm)
            if (lemma.isEmpty() || !ctx.hasRoomFor(lemma)) continue
            // Multi-word phrases have no headword to look up.
            if (card.targetTerm.trim().any(Char::isWhitespace)) continue

            lookups++
            val result = dictionary.lookup(card.targetTerm, ctx.lang)
            if (result !is DictionaryResult.Found) continue
            val forms = ctx.formsByCard[card.id].orEmpty().ifEmpty { setOf(lemma) }

            val candidate = result.entries
                .flatMap { entry -> entry.definitions.mapNotNull { it.example } }
                .flatMap(Sentences::split)
                .firstNotNullOfOrNull { sentence -> candidateFor(sentence, card.id, lemma, forms) }
            if (candidate != null) out += candidate
        }
        return out
    }

    /**
     * Null unless the example actually spells the word. Wiktionary examples sometimes illustrate a
     * *related* term (a derived form, or the phrase the headword appears in), and blanking a word
     * that isn't there would produce an unanswerable item.
     */
    private fun candidateFor(
        sentence: String,
        cardId: Long,
        lemma: String,
        forms: Set<String>,
    ): ClozeCandidate? {
        val trimmed = sentence.trim()
        val hit = TOKEN.findAll(trimmed).firstOrNull { normalizeWord(it.value) in forms } ?: return null
        return ClozeCandidate(
            sentence = trimmed,
            answer = hit.value,
            answerStart = hit.range.first,
            lemma = lemma,
            cardId = cardId,
            // The pack is monolingual: its examples come with no translation of their own.
            translation = null,
            source = id,
        )
    }

    private companion object {
        /** Dictionary reads per pass, so a large deck doesn't turn one background run into a scan. */
        const val LOOKUPS_PER_RUN = 40

        val TOKEN = Regex("[\\p{L}\\p{M}\\p{Nd}'’-]+")
    }
}
