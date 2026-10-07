package com.copiosa.domain.vocab

/**
 * Where a word is being saved from: a conversation (a chat, or an imported text — the
 * conversation's mode says which), a book, or one generated routine run. At most one is set; none
 * means the word came from nowhere a reader could list — typed in, seeded, or brought in from Anki.
 *
 * Stored on the note as three nullable columns, and the key a reader uses to ask for "the words I
 * saved from this".
 */
data class WordSource(
    val conversationId: Long? = null,
    val bookId: Long? = null,
    val routineRunId: Long? = null,
) {
    companion object {
        val NONE = WordSource()

        fun conversation(id: Long?) = WordSource(conversationId = id)
        fun book(id: Long) = WordSource(bookId = id)
        fun routineRun(id: Long) = WordSource(routineRunId = id)
    }
}
