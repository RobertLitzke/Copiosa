package com.copiosa.domain.prompt

import com.copiosa.domain.highlight.normalizeWord

/**
 * A saved word supplied to a reply: its [front]/[back] drive the prompt's vocab list, while its
 * normalized surface [forms] (headword plus any cached inflections) are what [VocabUsageDetector]
 * scans the reply body for. Built by the conversation repository from the picked flashcards.
 */
data class VocabHint(val front: String, val back: String, val forms: Set<String>)

/**
 * Ground-truth check of which supplied saved words actually appear in a reply body — replacing the
 * model's unreliable self-report so the per-reply metadata reflects reality.
 *
 * Reuses the highlight matcher's semantics ([com.copiosa.ui.components.WordColorizer.spansFor]): the
 * body is tokenized into normalized single-word tokens (maximal `isLetterOrDigit()` runs), and a
 * single-word hint counts as used when any of its forms is among them. A multi-word phrase form (which
 * never reduces to one token) is instead matched by normalized-substring containment.
 *
 * Deliberately under-counts rather than over-counts: matching is accent-sensitive (a reply that drops
 * an accent won't register), and a card whose inflections weren't backfilled matches only its exact
 * headword, so a conjugated occurrence can be missed. Both err toward "unused", the safe direction for
 * a usage metric.
 */
object VocabUsageDetector {

    data class Result(val used: List<String>, val unused: List<String>)

    fun detect(body: String, supplied: List<VocabHint>): Result {
        if (supplied.isEmpty()) return Result(emptyList(), emptyList())
        val tokens = tokenize(body)
        val phraseHaystack = collapseToWords(body)
        val used = ArrayList<String>()
        val unused = ArrayList<String>()
        for (hint in supplied) {
            if (hint.appearsIn(tokens, phraseHaystack)) used += hint.front else unused += hint.front
        }
        return Result(used, unused)
    }

    private fun VocabHint.appearsIn(tokens: Set<String>, phraseHaystack: String): Boolean =
        forms.any { form ->
            when {
                form.isEmpty() -> false
                // Phrase form (kept its internal spaces through normalization): match on the collapsed
                // body so surrounding punctuation/whitespace don't block it.
                form.any(Char::isWhitespace) -> phraseHaystack.contains(collapseToWords(form))
                else -> form in tokens
            }
        }

    /** The body's word tokens, normalized — mirrors [WordColorizer.spansFor]'s tokenization. */
    private fun tokenize(text: String): Set<String> {
        val out = HashSet<String>()
        var i = 0
        while (i < text.length) {
            if (text[i].isLetterOrDigit()) {
                var j = i + 1
                while (j < text.length && text[j].isLetterOrDigit()) j++
                normalizeWord(text.substring(i, j)).takeIf(String::isNotEmpty)?.let(out::add)
                i = j
            } else {
                i++
            }
        }
        return out
    }

    /** Lowercases and collapses every run of non-alphanumerics to a single space, for phrase matching. */
    private fun collapseToWords(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text) {
            if (ch.isLetterOrDigit()) {
                if (pendingSpace && sb.isNotEmpty()) sb.append(' ')
                pendingSpace = false
                sb.append(ch.lowercaseChar())
            } else {
                pendingSpace = true
            }
        }
        return sb.toString()
    }
}
