package com.copiosa.domain.usage

/**
 * What a model call was *for* — the unit the usage meter reports in. Every
 * [com.copiosa.data.remote.LlmRequest] names one, with no default, so a new call site cannot be
 * added without saying which line of the bill it belongs on.
 *
 * The constant **names** are persisted (`llm_usage.feature`): append only, never rename. A name this
 * build doesn't know — a row written by a newer one — reads back as null from [fromName] and is
 * shown as "other" rather than dropped, so the total stays the total.
 */
enum class LlmFeature {
    /** Conversation replies, including a chat's opening turn and an "Explain" aside inside one. */
    CHAT,

    /** An imported text's translation: its chunks and its title. */
    TEXT,

    /** A book's translation: sections, the title, and the retries a section can cost. */
    BOOK,

    /** A routine's one consolidated content call. */
    ROUTINE,

    /** A course lesson's generated practice and its wrap-up diagnosis. */
    COURSE,

    /** The cloze bank's three passes: writing sentences, translating them, adjudicating reports. */
    CLOZE_BANK,

    /** Seeding the rapid-fire pools: spoken phrasings and scenario phrases. */
    QUIZ_POOL,

    /** Filling in a saved word's explanation, gender and level. */
    VOCAB,

    /** Drafting cards from a typed list. */
    BULK_ADD,

    /** An "Explain" asked from a reader's lookup card — a text, a book, a routine's reading step. */
    EXPLAIN;

    companion object {
        fun fromName(name: String): LlmFeature? = entries.firstOrNull { it.name == name }
    }
}
