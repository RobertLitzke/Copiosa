package com.copiosa.domain.book

/**
 * How a chapter becomes one or more audio tracks.
 *
 * A chapter is the natural unit — it is what the author divided the book into, and it is what the
 * table of contents already names — so one track per chapter is the default. The only thing that
 * overrides it is length: speech runs at roughly 150 words a minute, so an unbroken chapter of a
 * long novel can be well over an hour, and a track is the smallest thing playback can seek between.
 *
 * Sizing is in characters rather than words to match [com.copiosa.data.epub.BookSectioner], whose
 * `SECTION_CHARS = 4000` is the unit everything else about a book is measured in — so a chunk is
 * four or five whole sections. A section is never split: it is the translation unit, so splitting
 * one would put half a model call's output in each of two tracks.
 */

/** One track's worth of a chapter: which sections it covers, and where it sits in the chapter. */
data class AudioChunk(
    val chapterIndex: Int,
    val partIndex: Int,
    val partCount: Int,
    val firstSection: Int,
    val lastSection: Int,
) {
    val sections: IntRange get() = firstSection..lastSection
}

/** ~3,000 words, ~20 minutes of speech. */
const val CHUNK_CHARS = 18_000

/**
 * Splits [chapter]'s section span into runs of at most [capChars], never splitting a section.
 *
 * [sizes] maps sectionIndex to its character count; a section missing from it counts as 0 (the
 * frontier has not produced its text yet), which keeps an under-translated chapter from fragmenting
 * into empty tracks. A single section over the cap gets its own chunk rather than being dropped —
 * the cap is a target, and an over-long track beats a missing one.
 */
fun chunkChapter(
    chapter: BookChapter,
    sizes: Map<Int, Int>,
    capChars: Int = CHUNK_CHARS,
): List<AudioChunk> {
    val bounds = ArrayList<Pair<Int, Int>>()
    var first = chapter.firstSection
    var running = 0

    for (section in chapter.firstSection..chapter.lastSection) {
        val size = sizes[section] ?: 0
        // Close the current run *before* adding a section that would overflow it, so the cap is an
        // upper bound rather than something each chunk overshoots by up to a whole section.
        if (running > 0 && running + size > capChars) {
            bounds += first to section - 1
            first = section
            running = 0
        }
        running += size
    }
    bounds += first to chapter.lastSection

    return bounds.mapIndexed { index, (from, to) ->
        AudioChunk(
            chapterIndex = chapter.index,
            partIndex = index,
            partCount = bounds.size,
            firstSection = from,
            lastSection = to,
        )
    }
}

/**
 * A track's spoken title: the chapter's own, with a part suffix only when the chapter was split.
 * [fallback] is what the reader's table of contents shows for an untitled chapter ("Chapter 4").
 */
fun trackTitle(chunk: AudioChunk, chapterTitle: String?, fallback: String): String {
    val base = chapterTitle?.takeIf { it.isNotBlank() } ?: fallback
    return if (chunk.partCount > 1) "$base (${chunk.partIndex + 1}/${chunk.partCount})" else base
}
