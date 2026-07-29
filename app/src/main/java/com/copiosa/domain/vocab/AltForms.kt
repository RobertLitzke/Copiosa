package com.copiosa.domain.vocab

/**
 * The storage shape of [com.copiosa.data.local.FlashcardEntity.altForms]: synonyms and gendered
 * variants of the headword, `"; "`-joined. That separator is what Anki import splits on and what
 * export re-joins with, so the editor normalizes to it rather than keeping whatever the user typed.
 */
object AltForms {

    /** Canonicalizes typed alternate forms to the stored `"; "`-joined shape; blank becomes null. */
    fun normalize(raw: String?): String? = raw
        ?.split(';')
        ?.map(String::trim)
        ?.filter(String::isNotEmpty)
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString("; ")
}
