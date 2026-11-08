package com.copiosa.domain.listening

/** One sentence of a radio track: said in the language being learned, and optionally what it means. */
data class RadioLine(val target: String, val native: String?)

/**
 * A radio track's sentences as the text of its playlist row, and back.
 *
 * One sentence per line, with a tab between the sentence and its translation when it has one. Plain
 * enough that the row's text still reads as what it is anywhere the app shows it, and that is the
 * whole reason it isn't JSON.
 */
object RadioScript {
    /** `audio_items.kind` for a row whose text is a script; anything else is ordinary prose. */
    const val KIND = "RADIO"

    /** The kind every other playlist row has — an article, a chat reply, a book chapter. */
    const val KIND_ARTICLE = "ARTICLE"

    fun encode(lines: List<RadioLine>): String =
        lines.mapNotNull { line ->
            val target = flat(line.target)
            val native = line.native?.let(::flat).orEmpty()
            when {
                target.isEmpty() -> null
                native.isEmpty() -> target
                else -> "$target\t$native"
            }
        }.joinToString("\n")

    fun decode(text: String): List<RadioLine> =
        text.lineSequence().mapNotNull { raw ->
            val target = raw.substringBefore('\t').trim()
            val native = raw.substringAfter('\t', "").trim()
            if (target.isEmpty()) null else RadioLine(target, native.ifEmpty { null })
        }.toList()

    /** A sentence on one line: the two characters the format uses are not allowed inside one. */
    private fun flat(s: String): String = s.replace(SEPARATORS, " ").trim()

    private val SEPARATORS = Regex("[\\t\\r\\n]+")
}
