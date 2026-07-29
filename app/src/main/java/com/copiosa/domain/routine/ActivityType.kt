package com.copiosa.domain.routine

/**
 * Where an activity sits in a routine's arousal arc. A bedtime routine moves monotonically
 * [ENGAGE] -> [SETTLE] -> [DRIFT]; the planner draws each phase's activities from a band of arousal
 * weights so the curve is guaranteed while the picks vary night to night.
 */
enum class ArousalBand { ENGAGE, SETTLE, DRIFT }

/**
 * How activating an activity is — a hard tier (not the continuous [ActivityDefinition.arousalWeight],
 * which only orders activities within a band). A routine type declares the tier it draws from freely
 * ([RoutineType.maxArousal]) and how much it will admit from above it: the bedtime wind-down takes at
 * most one [HIGH] activity, and only in [ArousalBand.ENGAGE], where a jolt still has the rest of the
 * routine to decay. Ordinal order is meaningful: LOW < MEDIUM < HIGH.
 */
enum class ArousalLevel { LOW, MEDIUM, HIGH }

/**
 * One kind of practice unit in the activity library. Cross-language activities (e.g. [GUESS_THEN_FLIP],
 * [NARROW_READING]) apply to any target language; the Spanish-specific ones ([REFERENTIAL_PI],
 * [CONTRAST_CLOZE], [AFFECTIVE_PI]) are gated by [ActivityDefinition.supportedLanguages]. Persisted as
 * its [name] in attempt logs, so order/renames must stay backward-compatible.
 */
enum class ActivityType {
    /** Engage: reuse the flashcard tool, recall direction (L1->L2), with the fail-state stripped. */
    GUESS_THEN_FLIP,

    /** Settle: read a pre-conjugated, pro-dropped sentence and pick its referent (form -> meaning). */
    REFERENTIAL_PI,

    /** Settle: pick the correct form in context (ser/estar, por/para, preterite/imperfect, ...). */
    CONTRAST_CLOZE,

    /** Settle -> Drift: a short level-appropriate passage recycling recent vocab, tap-to-define. */
    NARROW_READING,

    /** Drift: "true for me" — pick which pre-conjugated statement fits you. No right answer, no score. */
    AFFECTIVE_PI,

    /** Drift closer: passive TTS micro-story from today's vocab; screen dims, taps disabled. */
    BEDTIME_STORY,

    /** Settle: gentle reverse of guess-then-flip — see the target word, tap to reveal its meaning. */
    RECOGNITION_CARDS,

    /** Settle: fill a blank from three meaning-distinct options (generic grammar-in-context). */
    MULTIPLE_CHOICE_CLOZE,

    /** Settle -> Drift: swipe saved nouns into bins (masculine/feminine) — tactile categorization. */
    SWIPE_SORT,

    /** Drift: tap the true cognates and flag the false friends in a small set. */
    COGNATE_HUNT,

    /** Drift: read a chapter of a story that continues night to night. */
    SERIAL_STORY,

    /** Engage: hear a short cue (a number/time) and tap the matching digits fast — sound -> meaning. */
    RAPID_FIRE_LISTENING,

    /** Engage: conjugate in your head, reveal, self-grade. The library's first [ArousalLevel.HIGH]. */
    CONJUGATION_DRILL,
}
