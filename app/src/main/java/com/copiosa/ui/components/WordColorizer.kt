package com.copiosa.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import com.copiosa.domain.highlight.HighlightTarget
import com.copiosa.domain.highlight.normalizeWord

/** A half-open character range `[start, end)` in some text that should be styled [WordColorizer.savedStyle]. */
data class HighlightSpan(val start: Int, val end: Int)

/**
 * Decides which stretches of rendered text should be styled [savedStyle] because they match the
 * learner's saved vocabulary. The style is a plain color tint in the stock theme; persona skins
 * substitute their own treatment (wash, pill, underline — see the theme system's FocusWordStyle).
 *
 * Matching is per-word, case/punctuation-insensitive (see [normalizeWord]), and inflection-aware: the
 * [targets] already carry every plural/conjugated form of each saved word (expanded upstream by
 * [com.copiosa.domain.highlight.HighlightTargetProvider]), so "gatos" lights up a saved "gato".
 * Multi-word phrases still don't match, which keeps common function words from lighting up.
 *
 * When a matched word is a gendered noun, [spansFor] also reaches back over a single space to include a
 * preceding agreeing article, so "Él fue a **la fiesta**" tints `la fiesta`, not just `fiesta`.
 */
class WordColorizer(
    targets: List<HighlightTarget>,
    val savedStyle: SpanStyle,
) {
    constructor(targets: List<HighlightTarget>, savedColor: Color) :
        this(targets, SpanStyle(color = savedColor))

    private class Token(val gender: String?, val lang: String)

    /** Normalized surface form -> its match metadata. Forms with gender win ties, to enable articles. */
    private val byForm: Map<String, Token> = buildMap {
        for (t in targets) for (form in t.forms) {
            if (form.isEmpty()) continue
            val existing = get(form)
            if (existing == null || (existing.gender == null && t.gender != null)) {
                put(form, Token(t.gender, t.lang))
            }
        }
    }

    /** True when this colorizer would never tint anything; lets callers skip tokenizing. */
    val isEmpty: Boolean get() = byForm.isEmpty()

    /** Style for a single [word], or null to leave it at the surrounding style. */
    fun styleFor(word: String): SpanStyle? {
        val key = normalizeWord(word)
        return if (key.isNotEmpty() && key in byForm) savedStyle else null
    }

    /**
     * Character ranges in [text] to tint: one per matched word, widened to cover a preceding article
     * when the matched word is a gendered noun. Ranges are relative to [text].
     */
    fun spansFor(text: String): List<HighlightSpan> {
        if (byForm.isEmpty()) return emptyList()
        val spans = ArrayList<HighlightSpan>()
        var i = 0
        var prevWord: String? = null
        var prevStart = -1
        var spaceOnlyGap = false // whether the run between prevWord and the current word was only spaces
        while (i < text.length) {
            val isWord = text[i].isLetterOrDigit()
            var j = i + 1
            while (j < text.length && text[j].isLetterOrDigit() == isWord) j++
            if (isWord) {
                val token = byForm[normalizeWord(text.substring(i, j))]
                if (token != null) {
                    var start = i
                    if (token.gender != null && prevWord != null && spaceOnlyGap) {
                        val articles = articlesFor(token.lang, token.gender)
                        if (articles != null && prevWord!!.lowercase() in articles) start = prevStart
                    }
                    spans += HighlightSpan(start, j)
                }
                prevWord = text.substring(i, j)
                prevStart = i
            } else {
                spaceOnlyGap = text.substring(i, j).all { it.isWhitespace() }
            }
            i = j
        }
        return spans
    }

    companion object {
        /** Shared word normalization; delegates to the highlight layer's [normalizeWord]. */
        fun normalize(word: String): String = normalizeWord(word)

        // Spanish definite + indefinite articles, grouped by grammatical gender. Number is intentionally
        // ignored (both "la fiesta" and "las fiestas" should highlight), since real text agrees in gender.
        private val ES_MASCULINE = setOf("el", "los", "un", "unos")
        private val ES_FEMININE = setOf("la", "las", "una", "unas")

        /**
         * Articles that may precede a noun with the stored [genderCode] (M/F/MF/MPL/FPL) in [lang], or
         * null when [lang] has no article table (article highlighting is Spanish-only for now).
         */
        private fun articlesFor(lang: String, genderCode: String): Set<String>? {
            if (lang != "es") return null
            return when {
                genderCode.equals("MF", ignoreCase = true) -> ES_MASCULINE + ES_FEMININE
                genderCode.startsWith("F", ignoreCase = true) -> ES_FEMININE
                genderCode.startsWith("M", ignoreCase = true) -> ES_MASCULINE
                else -> null
            }
        }
    }
}
