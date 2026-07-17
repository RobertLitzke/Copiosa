package com.copiosa.domain.routine

import com.copiosa.domain.prompt.RoutineContentParser

/** A proficiency-bucket subdivision of a categorized activity (a contrast pair, a rapid-fire cue type). */
data class BucketCategory(val slug: String, val label: String)

/** Language names (in English) interpolated into the synthesis system prompt's activity rules. */
data class PromptContext(val targetName: String, val nativeName: String)

/** Slot-independent inputs to the per-slot request lines of the consolidated generation request. */
data class RequestContext(val durationMinutes: Int, val storyContext: String?)

/**
 * Data-layer services the offline-built activities draw on. Implemented by the synthesis manager so
 * descriptors stay pure domain code while still reaching the flashcard store and the quiz pool.
 */
interface RoutineBuildServices {
    /** The learner's own gendered nouns as swipe items (M -> bin 0, F -> bin 1). */
    suspend fun swipeSortItems(lang: String): List<SwipeItem>

    /** A single-topic rapid-fire block from the pre-rendered quiz pool; null when it can't field one. */
    suspend fun buildRapidFire(lang: String, stats: Map<String, ActivityStats>): RapidFireContent?
}

/** Everything a descriptor may need to assemble content for one slot. */
class ActivityBuildContext(
    val lang: String,
    /** The serial story's next chapter number (1-based). */
    val nextChapter: Int,
    val stats: Map<String, ActivityStats>,
    val validator: RoutineContentValidator,
    val services: RoutineBuildServices,
)

/**
 * One activity type's complete integration: planner metadata ([definition]), its prompt fragments for
 * the consolidated LLM request, its content assembly, and its status-screen buckets. Adding an
 * activity = one descriptor (+ its content class and renderer) and a registration line in
 * [ActivityRegistry] — the planner, prompt builder, synthesizer, and status screen all iterate the
 * registry instead of switching over [ActivityType].
 */
interface ActivityDescriptor {
    val definition: ActivityDefinition

    val type: ActivityType get() = definition.type

    /** This activity's rule block in the system prompt (no leading "- "); null = no generated content. */
    fun systemRule(ctx: PromptContext): String? = null

    /** This slot's "- id N: ..." request line (no trailing newline); null = no generated content. */
    fun requestLine(id: Int, slot: ActivitySlot, ctx: RequestContext): String? = null

    /** Assembles validated content for one slot; null drops the slot from the plan. */
    suspend fun buildContent(
        slot: ActivitySlot,
        dto: RoutineContentParser.ActivityDto?,
        ctx: ActivityBuildContext,
    ): ActivityContent?

    /** Per-category proficiency buckets, when this type subdivides; empty = one bucket per type. */
    val statusCategories: List<BucketCategory> get() = emptyList()
}

/** Grammar drills keep at least this many validated items or the slot is dropped. */
internal const val MIN_ACTIVITY_ITEMS = 2

/** Flashcard-count cap for the card-based steps, nudged up at higher difficulty. */
internal fun cardLimitFor(difficulty: Int): Int = if (difficulty >= 2) 20 else 12

/** The difficulty rider appended to a request line as adaptation raises a bucket's level. */
internal fun difficultyNote(level: Int): String = when {
    level <= 0 -> ""
    level == 1 -> " (slightly harder: less common vocab)"
    else -> " (harder: longer sentences, subtler distinctions, rarer vocab, fewer cognates)"
}
