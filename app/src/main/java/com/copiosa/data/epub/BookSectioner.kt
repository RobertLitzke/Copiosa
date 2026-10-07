package com.copiosa.data.epub

/** One block with its final storage coordinates, ready to become a `book_paragraphs` row. */
data class SectionedBlock(
    val ord: Int,
    val chapterIndex: Int,
    val sectionIndex: Int,
    val kind: BlockKind,
    val text: String,
)

/**
 * Assigns translation sections (~2 pages of source text) across a book's blocks. Greedy
 * accumulation up to [SECTION_CHARS] with two structural rules: a section never spans a chapter
 * boundary (each chapter starts a fresh section, so the part-N continuity context always reads in
 * order), and a heading always joins the section that *follows* it — a section never ends on a
 * dangling chapter/scene title.
 *
 * Lists, tables and footnote bodies are ordinary body text here: they are prose the reader reads
 * and the model translates, and giving notes a section of their own would cost an extra LLM call
 * per chapter and leave every chapter's table-of-contents progress stuck at partial until it ran.
 * Images are the exception — see [BlockKind.IMAGE] handling below.
 */
object BookSectioner {

    /** ~2 pages of source text per LLM call (spec: 3500-4500 chars). */
    const val SECTION_CHARS = 4000

    fun assign(chapters: List<EpubChapter>): List<SectionedBlock> {
        val out = mutableListOf<SectionedBlock>()
        var ord = 0
        var section = -1

        chapters.forEachIndexed { chapterIndex, chapter ->
            var sectionChars = 0
            var pendingHeadings = mutableListOf<EpubBlock>()
            section += 1 // A chapter always begins a new section.

            for (block in chapter.blocks) {
                if (block.kind == BlockKind.HEADING) {
                    // Held back until the next body paragraph decides which section it opens.
                    pendingHeadings.add(block)
                    continue
                }
                // An image is filtered out of the translation request, so it costs the call
                // nothing and must not be the block that tips a section over the budget. It keeps
                // its ord position — unlike a heading it is never deferred, because the reader's
                // list index is its ord and a plate has to stay where the author put it.
                if (block.kind == BlockKind.IMAGE) {
                    for (heading in pendingHeadings) {
                        out.add(SectionedBlock(ord++, chapterIndex, section, heading.kind, heading.text))
                        sectionChars += heading.text.length + 2
                    }
                    pendingHeadings = mutableListOf()
                    out.add(SectionedBlock(ord++, chapterIndex, section, block.kind, block.text))
                    continue
                }
                val pendingChars = pendingHeadings.sumOf { it.text.length + 2 }
                if (sectionChars > 0 && sectionChars + pendingChars + block.text.length > SECTION_CHARS) {
                    section += 1
                    sectionChars = 0
                }
                for (heading in pendingHeadings) {
                    out.add(SectionedBlock(ord++, chapterIndex, section, heading.kind, heading.text))
                    sectionChars += heading.text.length + 2
                }
                pendingHeadings = mutableListOf()
                out.add(SectionedBlock(ord++, chapterIndex, section, block.kind, block.text))
                sectionChars += block.text.length + 2
            }
            // A chapter of nothing but headings still keeps them.
            for (heading in pendingHeadings) {
                out.add(SectionedBlock(ord++, chapterIndex, section, heading.kind, heading.text))
            }
        }
        return out
    }
}
