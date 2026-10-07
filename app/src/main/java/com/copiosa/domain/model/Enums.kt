package com.copiosa.domain.model

/** CEFR proficiency levels, ordered from beginner to advanced. */
enum class Cefr {
    A1, A2, B1, B2, C1, C2;

    /** The next level up, used to seed "occasional one level higher" vocabulary. */
    fun next(): Cefr = entries.getOrElse(ordinal + 1) { this }
}

/**
 * Which way an imported book is translated — decided once, at import, and never changed after.
 *
 * The reader always shows the *target*-language text as the primary column and the *native* one as a
 * subordinate gloss; [FORWARD] and [REVERSE] differ only in which side of a paragraph row holds
 * which language (see `com.copiosa.domain.book.targetText`).
 */
enum class BookDirection {
    /**
     * The EPUB is in some other language; the LLM produces graded target-language text into
     * `translatedText`. The historical (and default) behaviour.
     */
    FORWARD,

    /**
     * The EPUB is already in the target language, so `originalText` is what the learner reads. The
     * LLM, if asked at all, produces an ungraded native-language gloss into `translatedText` —
     * nothing is levelled, since the output is the learner's own language.
     */
    REVERSE,
}

/**
 * How eagerly a book's sections are translated — the one per-book knob for what an imported EPUB is
 * allowed to spend. Chosen in the reader's (or library row's) download-mode dialog, and at import.
 *
 * The order is the order the dialog lists them in, cheapest first; each mode does everything the one
 * above it does and more.
 */
enum class BookDownloadMode {
    /**
     * Nothing automatic: sections arrive only when the reader taps "continue translating". Where a
     * [BookDirection.REVERSE] book starts — it is readable as imported, so its native gloss is a
     * cost the learner has to ask for.
     */
    OFF,

    /**
     * Translate just ahead of the reading position (BookReadingViewModel.READ_AHEAD_SECTIONS), so
     * reading never stalls and an unread book costs nothing. The default for a forward import.
     */
    WHILE_READING,

    /**
     * Read-ahead, plus the background trickle: BookTranslateTask advances one section per run, runs
     * ~90 minutes apart, so a novel fills in over days without a cost spike.
     */
    BACKGROUND,

    /**
     * Translate the whole book now — a foreground worker walks it to the end in one sequential run
     * (tens of minutes on a novel). Stays on the book after the run so an interrupted download
     * resumes when the book is next opened.
     */
    RAPID;

    /** Whether the reader should translate past the section being read. */
    val readsAhead: Boolean get() = this != OFF
}

/** Which kind of session a conversation represents. */
enum class Mode {
    CONVERSATION,
    URL_TRANSLATION,
}

/** Supported LLM providers. The user supplies their own key for the selected one. */
enum class ProviderId {
    GEMINI,
    OPENROUTER;

    /** Human-readable provider name for chips, menus, and key labels. */
    fun displayLabel(): String = when (this) {
        GEMINI -> "Gemini"
        OPENROUTER -> "OpenRouter"
    }
}

/** App theme preference. SYSTEM follows the OS dark-mode setting. */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

/**
 * Visual persona skin for the theme system: each persona owns a chat layout, fonts, chrome,
 * texture, motion, and localized in-character copy. [UNSTYLED] preserves the app's stock look.
 */
enum class Persona {
    UNSTYLED,
    PROFESSOR,
    CHEF,
    POET,
    ARTIST,
    COMMUTER,
    EXPLORER,
}

/**
 * Country axis of the theme system: owns the color palette (light + dark), decorative motif,
 * tutor name, and localized labels. Independent of [com.copiosa.data.settings.AppSettings.defaultCountry],
 * which steers LLM dialect. [NONE] keeps the stock forest/cream palette.
 */
enum class ThemeCountry {
    NONE,
    SPAIN,
    PORTUGAL,
    MEXICO,
    GERMANY,
    BRAZIL,
}

/** Reading typeface used for chat replies, definitions, and other long-form reading text. */
enum class ReadingFont {
    /** Source Serif 4 — the app default. */
    SERIF,
    /** Literata — a warm slab-ish serif. */
    SLAB,
    /** Plus Jakarta Sans — the app's sans-serif chrome font. */
    SANS,
}

/** Reading text size; scales the reading font relative to its default size. */
enum class ReadingTextSize {
    SMALL,
    MEDIUM,
    LARGE,
    XLARGE,
}

/** Message author role, shared by storage, prompts, and the provider wire format. */
enum class Role {
    USER,
    ASSISTANT,
    SYSTEM,
}

/**
 * Spaced-repetition lifecycle state of a flashcard. NEW is our "never reviewed" sentinel (FSRS has
 * no such state); the other three mirror FSRS's own [io.github.openspacedrepetition.State] so a
 * card's lifecycle round-trips with Anki.
 */
enum class FlashcardState {
    NEW,
    LEARNING,
    REVIEW,
    RELEARNING,
}

/** Self-graded recall quality during review. */
enum class ReviewRating {
    AGAIN,
    HARD,
    GOOD,
    EASY,
}

/**
 * Lifecycle of a generated audio item. PENDING is queued (and, before the voice model is downloaded,
 * "waiting for the download"); GENERATING is actively rendering TTS; READY has a playable WAV; FAILED
 * can be retried.
 */
enum class AudioStatus {
    PENDING,
    GENERATING,
    READY,
    FAILED,
}

/**
 * Structural classification of a flashcard, set on import (from a cleaned Anki deck's `la:kind:`
 * tags) or when saving from chat. Only [VOCAB] and [PHRASE] are woven into LLM conversations and
 * highlighted in text; the others are study-only (drills, full sentences) and excluded from the
 * conversation vocab pool and from highlighting.
 */
enum class CardKind {
    VOCAB,
    PHRASE,
    SENTENCE,
    CONJUGATION,
    GRAMMAR,
    CLOZE;

    /** Whether this card is a single lexical item usable as conversation vocab / for highlighting. */
    val isLexicalVocab: Boolean get() = this == VOCAB || this == PHRASE
}

/**
 * Which language side a flashcard is quizzed from. [BOTH] alternates randomly each review so the word
 * is practiced in both directions over time (kept on a single FSRS schedule — no Anki-style
 * note→two-card split). [TARGET_FIRST] / [NATIVE_FIRST] pin the prompt to one starting side.
 */
enum class ReviewDirection {
    BOTH,
    TARGET_FIRST,
    NATIVE_FIRST,
}
