package com.copiosa.data.cloze.sources

import com.copiosa.domain.cloze.ClozeCandidate
import com.copiosa.domain.cloze.ClozeGate
import com.copiosa.domain.cloze.ClozeMineContext
import com.copiosa.domain.cloze.ClozeSelector
import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.vocab.freq.FrequencyPos

/**
 * Choosing the blank in a sentence the learner has read — shared by every source that mines reading
 * material (books, imported texts, chat replies), so they agree on what is worth blanking.
 *
 * Two kinds of blank, in this order:
 *
 * 1. **A word the learner has a card for**, as long as that word still wants sentences. This is what
 *    the bank is for.
 * 2. **A common word they have no card for** — a content word from the frequency list with nothing
 *    banked yet. One sentence each, and only as a minority of what a source offers
 *    ([Picked.carded] lets the caller ration them): these are what the common-words track walks, and
 *    the deck this was built against holds under a third of the top 2,000.
 */
internal object SentenceBlanks {

    /** Words including accented letters and internal apostrophes ("d'accord", "año"). */
    val TOKEN = Regex("[\\p{L}\\p{M}\\p{Nd}'’-]+")

    private val WHITESPACE = Regex("\\s+")

    /** Shortest form worth blanking for a word the learner has no card for. */
    private const val MIN_UNCARDED_LENGTH = 4

    class Picked(val candidate: ClozeCandidate, val carded: Boolean)

    private const val QUOTES = "\"“”«»"

    /**
     * A sentence cut out of running prose, tidied. Splitting dialogue leaves half a pair of quotation
     * marks on one side — *“No tiene sentido”, dijo* is fine, *pero apuesto a que sí”.* is not — so
     * when the marks don't pair up they all go. A byte-order mark, which some EPUBs carry into their
     * first paragraph, goes regardless.
     */
    fun clean(sentence: String): String {
        val text = sentence.replace("﻿", "").trim()
        return if (text.count { it in QUOTES } % 2 == 1) text.filterNot { it in QUOTES }.trim() else text
    }

    fun wordCount(sentence: String): Int = sentence.trim().split(WHITESPACE).count { it.isNotBlank() }

    /**
     * Whether a sentence from reading material is a comfortable length. Stricter than the gate's
     * ceiling on purpose: a card has only the one example sentence it has, but a book has thousands,
     * so reading sources can afford to take only the ones short enough to be heard as well as read.
     */
    fun comfortable(sentence: String): Boolean = wordCount(sentence) in ClozeGate.MIN_WORDS..ClozeSelector.PREFERRED_WORDS.last

    /**
     * The blank for [sentence], or null when it holds nothing worth blanking. [proposed] is the
     * lemmas this source has already offered in the current run, so one pass doesn't spend its
     * budget on the same word five times.
     */
    fun pick(
        sentence: String,
        ctx: ClozeMineContext,
        source: String,
        translation: String?,
        proposed: MutableSet<String>,
    ): Picked? {
        // The first word is never blanked — the gate refuses it, since options render lower case.
        val tokens = TOKEN.findAll(sentence).drop(1).toList()

        for (hit in tokens) {
            val form = normalizeWord(hit.value)
            val cardId = ctx.formToCard[form] ?: continue
            if (!ctx.wantsSentencesFor(cardId)) continue
            // The lemma is the card's own headword form, so every sentence for one word shares a
            // cap key regardless of which inflection happened to appear here.
            val lemma = ctx.formsByCard[cardId]?.minByOrNull { it.length } ?: form
            if (!ctx.hasRoomFor(lemma) || !proposed.add(lemma)) continue
            return Picked(ClozeCandidate(sentence, hit.value, hit.range.first, lemma, cardId, translation, source), carded = true)
        }

        val list = ctx.list ?: return null
        for (hit in tokens) {
            val form = normalizeWord(hit.value)
            // Very short forms are the auxiliaries and copulas — *he*, *ser*, *voy* — where a blank
            // tests grammar rather than a word, and there is nothing of the same shape to offer.
            if (form.length < MIN_UNCARDED_LENGTH) continue
            if (form in ctx.functionWords || ctx.formToCard.containsKey(form)) continue
            val match = list.matchOfForm(form) ?: continue
            if (FrequencyPos.cardPosFor(match.pos) !in ClozeGate.CONTENT_POS) continue
            val lemma = normalizeWord(list.entries[match.rank - 1].lemma)
            // One sentence is enough to place an uncarded word on the track.
            if ((ctx.banked[lemma] ?: 0) > 0 || !proposed.add(lemma)) continue
            return Picked(ClozeCandidate(sentence, hit.value, hit.range.first, lemma, null, translation, source), carded = false)
        }
        return null
    }
}
