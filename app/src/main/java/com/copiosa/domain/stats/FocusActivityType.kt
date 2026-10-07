package com.copiosa.domain.stats

/**
 * A kind of focused study activity whose time is tracked. Stored by [Enum.name], so adding a new
 * surface later is an enum value plus a tracker hook — no schema change.
 */
enum class FocusActivityType {
    /** Reading a conversation or an imported text. */
    READING,

    /** Reading an imported book. */
    READING_BOOK,

    /** Listening to the audio playlist (counts while backgrounded). */
    LISTENING,

    /** Flashcard review sessions. */
    REVIEW,

    /** Rapid-fire listening quiz sessions. */
    QUIZ,

    /** The routine player (bedtime wind-down activities). */
    ROUTINE,

    /** Standalone conjugation drill sessions. */
    CONJUGATION,

    /** Standalone cloze-bank sessions (fill the blank from your own sentences). */
    CLOZE,

    /** Standalone listen-and-rebuild sessions (hear a sentence, tap its words into order). */
    LISTEN_REBUILD,
}
