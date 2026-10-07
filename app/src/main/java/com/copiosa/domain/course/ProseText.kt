package com.copiosa.domain.course

/**
 * Turns authored lesson prose into the paragraphs a reader should actually see.
 *
 * Authored prose is written inside a Kotlin literal, so nearly every line break in it is a wrapping
 * artefact and means nothing — which is why the naive treatment is to join a paragraph's lines with
 * spaces. That is right until it meets the one place the catalog uses a line break on purpose: an
 * ending list, written one item to a line.
 *
 * ```
 * **yo → -é** · *hablar → hablé* (I spoke)
 * **tú → -aste** · *hablar → hablaste* (you spoke)
 * ```
 *
 * Joined with spaces those run together into a single unreadable line, in the beat whose entire job
 * is to show two endings side by side.
 *
 * The rule that separates the two cases is a **run**: two or more consecutive lines each opening
 * with bold. One line opening with bold is ordinary wrapped prose that happens to break before an
 * emphasised word ("…otherwise: / **hablaste** has no written accent…") and must stay joined; a run
 * of them is a list, and each item becomes its own paragraph. Authoring the items with blank lines
 * between them works too, and then this never fires.
 */
object ProseText {

    private val PARAGRAPH_BREAK = Regex("\n\\s*\n")

    fun paragraphs(text: String): List<String> = buildList {
        for (block in text.split(PARAGRAPH_BREAK)) {
            val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val bold = lines.map { it.startsWith("**") }
            val item = lines.indices.map { i ->
                bold[i] && (bold.getOrElse(i - 1) { false } || bold.getOrElse(i + 1) { false })
            }

            val current = StringBuilder()
            var previousWasItem = false
            for (i in lines.indices) {
                // A list item opens a paragraph, and closes the one after it too, so the prose
                // following a list does not get glued onto its last item.
                if (item[i] || previousWasItem) {
                    current.toString().trim().takeIf { it.isNotEmpty() }?.let { add(it) }
                    current.clear()
                }
                if (current.isNotEmpty()) current.append(' ')
                current.append(lines[i])
                previousWasItem = item[i]
            }
            current.toString().trim().takeIf { it.isNotEmpty() }?.let { add(it) }
        }
    }

    /** The same paragraphs, rejoined for a renderer that treats a blank line as a break. */
    fun forMarkdown(text: String): String = paragraphs(text).joinToString("\n\n")
}
