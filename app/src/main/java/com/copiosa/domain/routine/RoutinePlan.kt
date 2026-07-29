package com.copiosa.domain.routine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A fully-synthesized routine: the ordered activity sequence with its generated content, ready for the
 * player to step through. Serialized into [com.copiosa.data.local.RoutineRunEntity.planJson] so a
 * run survives process death and the bedtime story can be continued night-to-night. Use
 * [RoutinePlanCodec] to (de)serialize.
 */
@Serializable
data class RoutinePlan(
    val routineType: RoutineType,
    val durationMinutes: Int,
    val targetLanguage: String,
    val topic: String? = null,
    val activities: List<ActivityInstance>,
)

/** One activity in a [RoutinePlan]: its placement/difficulty plus the content the player renders. */
@Serializable
data class ActivityInstance(
    val type: ActivityType,
    val band: ArousalBand,
    val difficultyLevel: Int = 0,
    val category: String? = null,
    val content: ActivityContent,
)

/** Generated payload for an activity. Closed hierarchy so the player can render each kind exhaustively. */
@Serializable
sealed interface ActivityContent

/** Guess-then-flip needs no generated text — cards come from the SRS store; this just caps the count. */
@Serializable
@SerialName("guess_then_flip")
data class GuessThenFlipContent(val cardLimit: Int = 12) : ActivityContent

@Serializable
@SerialName("referential_pi")
data class ReferentialPiContent(val items: List<PiItem>) : ActivityContent

@Serializable
@SerialName("contrast_cloze")
data class ContrastClozeContent(val category: String, val items: List<ClozeItem>) : ActivityContent

@Serializable
@SerialName("narrow_reading")
data class NarrowReadingContent(val title: String, val paragraphs: List<String>) : ActivityContent

@Serializable
@SerialName("affective_pi")
data class AffectivePiContent(val prompt: String, val statements: List<String>) : ActivityContent

@Serializable
@SerialName("bedtime_story")
data class BedtimeStoryContent(val title: String, val sentences: List<String>) : ActivityContent

/** Recognition cards need no generated text — cards come from the SRS store; this just caps the count. */
@Serializable
@SerialName("recognition_cards")
data class RecognitionCardsContent(val cardLimit: Int = 10) : ActivityContent

@Serializable
@SerialName("multiple_choice_cloze")
data class MultipleChoiceClozeContent(val items: List<ClozeItem>) : ActivityContent

/** A swipe-to-sort set built from the user's own cards: each item names the bin (by index) it belongs in. */
@Serializable
@SerialName("swipe_sort")
data class SwipeSortContent(val dimension: SortDimension, val items: List<SwipeItem>) : ActivityContent

@Serializable
@SerialName("cognate_hunt")
data class CognateHuntContent(val words: List<CognateWord>) : ActivityContent

/** One chapter of a story that continues night to night (see the story thread in the data layer). */
@Serializable
@SerialName("serial_story")
data class SerialStoryContent(val chapter: Int, val title: String, val paragraphs: List<String>) : ActivityContent

/**
 * A rapid-fire listening block drawn from the pre-rendered quiz pool. Single-[topic] per block (a
 * CueType slug like "rf_numbers") so attempts land in one proficiency bucket; options were generated
 * fresh at synthesis time. An item's [audioPath] can go stale if the pool is rebuilt mid-run — the
 * player skips items whose file is gone.
 */
@Serializable
@SerialName("rapid_fire_listening")
data class RapidFireContent(
    val topic: String,
    val items: List<RapidFireItem>,
    /** Auto listening speed (1.0 = normal) this block was built at, from the topic's mastery. */
    val playbackSpeed: Float = 1.0f,
) : ActivityContent

/** One pre-rendered listening question: play [audioPath], then reveal [options] (digit strings). */
@Serializable
data class RapidFireItem(
    val cueId: Long,
    val audioPath: String,
    val display: String,
    val options: List<String>,
    val answerIndex: Int,
)

/**
 * A conjugation-drill block, built offline from the bundled verb lexicon rather than by the LLM.
 *
 * Only the coordinates are stored: the forms, their irregularity classes, the colour-coded segments
 * and the naive counterparts are all re-derived at render time by
 * [com.copiosa.domain.conjugation.ConjugationEngine], so a saved plan stays tiny and can never carry
 * a stale conjugation.
 */
@Serializable
@SerialName("conjugation_drill")
data class ConjugationDrillContent(val items: List<ConjugationDrillItem>) : ActivityContent

/**
 * One drill card, mirroring the fields of
 * [com.copiosa.domain.conjugation.DrillItem]'s persistence key. [subject] is an infinitive, or an
 * irregularity-class slug for a generalize card; [sourceCell] is set only for transform cards.
 */
@Serializable
data class ConjugationDrillItem(
    val direction: String,
    val subject: String,
    val targetCell: String,
    val sourceCell: String? = null,
    /** The verb actually shown for a generalize card, whose [subject] names a class instead. */
    val shownInfinitive: String? = null,
)

/** What a [SwipeSortContent] sorts by; the player derives the bin labels from this. */
@Serializable
enum class SortDimension { GENDER }

/** A swipe-to-sort item: a [word] and the [binIndex] it belongs in (0/1 for gender = masculine/feminine). */
@Serializable
data class SwipeItem(val word: String, val binIndex: Int)

/** A cognate-hunt word: a target [form], whether it's a [falseFriend], and its real meaning [gloss]. */
@Serializable
data class CognateWord(val form: String, val falseFriend: Boolean = false, val gloss: String? = null)

/**
 * A referential Processing-Instruction item: a fully-conjugated, pro-dropped [sentence] whose verb
 * form alone reveals the referent. [options] are the candidate referents (e.g. yo/tú/ella or
 * ayer/hoy/mañana); [answerIndex] points at the correct one.
 */
@Serializable
data class PiItem(
    val sentence: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String? = null,
)

/** A contrast cloze item: a [sentence] with a "___" blank, two [options], and the correct [answerIndex]. */
@Serializable
data class ClozeItem(
    val sentence: String,
    val options: List<String>,
    val answerIndex: Int,
    val explanation: String? = null,
) {
    companion object {
        const val BLANK = "___"
    }
}

/** (De)serializes a [RoutinePlan] to/from the JSON stored in `planJson`. */
object RoutinePlanCodec {
    // "kind" discriminates ActivityContent subtypes; ignoreUnknownKeys future-proofs older/newer plans.
    private val json = Json {
        classDiscriminator = "kind"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(plan: RoutinePlan): String = json.encodeToString(RoutinePlan.serializer(), plan)

    fun decode(stored: String?): RoutinePlan? {
        if (stored.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(RoutinePlan.serializer(), stored) }.getOrNull()
    }
}
