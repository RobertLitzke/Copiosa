package com.copiosa.domain.text

/**
 * Sentence segmentation for target-language text, shared by the TTS renderer (which needs speakable
 * segments to pace its own pauses) and the cloze miner (which needs one sentence at a time out of a
 * book paragraph).
 *
 * Deliberately biased toward over-splitting: for speech a spurious split only adds a short gap,
 * whereas a missed split reproduces the run-on pacing this exists to fix; for mining, an over-split
 * fragment simply fails the length gate. A period is not treated as a boundary unless followed by
 * whitespace/end (so decimals like "3.5" stay intact) nor after a few common pt/es abbreviations
 * ("Sr.", "Dr.", "etc.").
 *
 * Pure (string in, strings out) so it is JVM-testable — which it was not while it lived private on
 * the Android-dependent TTS engine.
 *
 * [ABBREVIATIONS] is es/pt only. That is fine for both callers: the TTS engine speaks the target
 * language, and the miner only ever reads target-language text.
 */
object Sentences {

    /**
     * Splits [text] into trimmed, non-blank segments at sentence-final punctuation and line breaks.
     * Returns a single-element list when [text] holds no interior sentence boundary, and an empty
     * list when it is blank.
     */
    fun split(text: String): List<String> {
        val segments = ArrayList<String>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            var start = 0
            var i = 0
            while (i < line.length) {
                if (line[i] == '.' || line[i] in TERMINATORS) {
                    // Extend across a run of terminators like "?!" or "..." so we split once, after it.
                    var end = i
                    while (end + 1 < line.length && (line[end + 1] == '.' || line[end + 1] in TERMINATORS)) end++
                    val next = line.getOrNull(end + 1)
                    val atBoundary = next == null || next.isWhitespace()
                    val abbrev = line[i] == '.' && end == i && endsWithAbbrev(line, start, i)
                    if (atBoundary && !abbrev) {
                        segments += line.substring(start, end + 1).trim()
                        var k = end + 1
                        while (k < line.length && line[k].isWhitespace()) k++
                        start = k
                        i = k
                        continue
                    }
                    i = end + 1
                } else {
                    i++
                }
            }
            if (start < line.length) segments += line.substring(start).trim()
        }
        val cleaned = segments.filter { it.isNotEmpty() }
        return if (cleaned.size <= 1) listOfNotNull(text.trim().ifBlank { null }) else cleaned
    }

    /** True when the token ending at the period [dotIndex] (back to the last space or [start]) is a known abbreviation. */
    private fun endsWithAbbrev(line: String, start: Int, dotIndex: Int): Boolean {
        var b = dotIndex - 1
        while (b >= start && !line[b].isWhitespace()) b--
        return line.substring(b + 1, dotIndex + 1).lowercase() in ABBREVIATIONS
    }

    /** Sentence-final punctuation other than '.', which needs the decimal/abbreviation guards. */
    private const val TERMINATORS = "!?…。"

    /** Lowercased pt/es abbreviations (with trailing dot) whose period must not end a sentence. */
    private val ABBREVIATIONS = setOf(
        "sr.", "sra.", "srs.", "dr.", "dra.", "prof.", "profa.",
        "etc.", "ex.", "p.ex.", "sto.", "sta.", "av.", "pág.", "núm.", "no.", "vs.",
    )
}
