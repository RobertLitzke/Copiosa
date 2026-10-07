package com.copiosa.domain.routine

/**
 * An arousal tier an activity can fill. The *sequence* of bands is the routine's arc and belongs to the
 * routine type ([RoutineType.bandOrder]), not to this enum: the bedtime wind-down plays them
 * [ENGAGE] -> [SETTLE] -> [DRIFT], the daytime activation plays the mirror image. The planner draws
 * each phase's activities from a band of arousal weights so the curve is guaranteed while the picks
 * vary run to run.
 *
 * Declaration order is therefore *not* the arc, and must stay stable regardless: the band is serialized
 * by name into [ActivityInstance.band].
 */
enum class ArousalBand { ENGAGE, SETTLE, DRIFT }

/**
 * Which flavour of [ActivityType.SHORT_TEXT] a slot renders — the one prose activity reshapes itself
 * rather than the library carrying three near-identical types. Serialized by name into the plan JSON.
 */
enum class TextFlavor {
    /** A short, soothing standalone story: the bedtime routine's closer, and chapter zero of a thread. */
    BEDTIME_STORY,

    /** One chapter of the story that continues night to night, from the persisted running summary. */
    SERIAL_CHAPTER,

    /** A brief second-person dispatch framing a daytime session and priming the vocab it will drill. */
    DAYTIME_MOTIVATION,
}

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
 * [NARROW_READING]) apply to any target language; the ones needing grammar data ([REFERENTIAL_PI],
 * [CONTRAST_CLOZE], [AFFECTIVE_PI], …) are gated by [ActivityDefinition.requires] against the target
 * language's `LanguageProfile`. Persisted as its [name] in attempt logs, so order/renames must stay
 * backward-compatible.
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

    /** Drift: a short generated prose read whose shape follows the routine — see [TextFlavor]. */
    SHORT_TEXT,

    /** Settle: gentle reverse of guess-then-flip — see the target word, tap to reveal its meaning. */
    RECOGNITION_CARDS,

    /** Settle: fill a blank from three meaning-distinct options (generic grammar-in-context). */
    MULTIPLE_CHOICE_CLOZE,

    /** Settle -> Drift: swipe saved nouns into bins (masculine/feminine) — tactile categorization. */
    SWIPE_SORT,

    /** Drift: tap the true cognates and flag the false friends in a small set. */
    COGNATE_HUNT,

    /** Engage: hear a short cue (a number/time) and tap the matching digits fast — sound -> meaning. */
    RAPID_FIRE_LISTENING,

    /** Engage: conjugate in your head, reveal, self-grade. The library's first [ArousalLevel.HIGH]. */
    CONJUGATION_DRILL,

    /** Engage: real review, narrowed to the words the frequency list says are worth knowing first. */
    FREQUENT_WORD_DRILL,

    /**
     * Settle: fill the blank from the learner's own banked sentences — material they have already
     * read, with a word from their own deck missing. Unlike [MULTIPLE_CHOICE_CLOZE] it needs no
     * generation at all, which makes it the library's cheapest settle-band activity.
     */
    CLOZE_BANK,

    /**
     * Settle: hear a banked sentence spoken, then tap its shuffled words back into order. The
     * library's first activity that trains listening itself rather than playing audio alongside a
     * task — the sentence is never shown until it has been rebuilt. No LLM; needs the voice model.
     */
    LISTEN_REBUILD,
    ;

    companion object {
        /**
         * Type names that no longer exist, mapped to what replaced them. The name is persisted in saved
         * plans and attempt logs, so retiring one without an entry here fails the *whole* plan on decode
         * (see [RoutinePlanCodec]) and strands any run left unfinished across the upgrade.
         */
        private val RETIRED: Map<String, ActivityType> = mapOf(
            "BEDTIME_STORY" to SHORT_TEXT,
            "SERIAL_STORY" to SHORT_TEXT,
        )

        /** [valueOf], but resolving retired names instead of throwing; null for genuinely unknown ones. */
        fun fromNameOrNull(name: String): ActivityType? =
            entries.firstOrNull { it.name == name } ?: RETIRED[name]
    }
}
