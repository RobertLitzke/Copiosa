package com.copiosa.domain.routine

import com.copiosa.domain.listening.RebuildItem
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
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

/**
 * Reads [ActivityType] by name, resolving names retired since a plan was saved. Without this a plan
 * naming a merged-away type would fail to decode in full, not just lose the one activity.
 */
private object RetiringActivityTypeSerializer : KSerializer<ActivityType> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.copiosa.domain.routine.ActivityType", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: ActivityType) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): ActivityType {
        val raw = decoder.decodeString()
        return ActivityType.fromNameOrNull(raw)
            ?: throw SerializationException("Unknown ActivityType '$raw'")
    }
}

/** One activity in a [RoutinePlan]: its placement/difficulty plus the content the player renders. */
@Serializable
data class ActivityInstance(
    @Serializable(with = RetiringActivityTypeSerializer::class)
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

/**
 * The routine's prose read. [blocks] are rendered as separate paragraphs, except for
 * [TextFlavor.BEDTIME_STORY], whose sentences are joined into one settled block. [chapter] is set only
 * for [TextFlavor.SERIAL_CHAPTER].
 */
@Serializable
@SerialName("short_text")
data class ShortTextContent(
    val flavor: TextFlavor,
    val title: String,
    val blocks: List<String>,
    val chapter: Int? = null,
) : ActivityContent

/**
 * Superseded by [ShortTextContent]; retained only so plans saved before the merge still decode.
 *
 * [RoutinePlanCodec.decode] fails the *whole* plan on an unrecognised "kind" discriminator, not just
 * the one activity, and a run whose plan no longer decodes sits on a permanent "preparing" spinner with
 * no retry path — so removing this class would strand every in-flight run on upgrade.
 */
@Deprecated("Legacy plan decode only — new plans use ShortTextContent.")
@Serializable
@SerialName("bedtime_story")
data class BedtimeStoryContent(val title: String, val sentences: List<String>) : ActivityContent

/**
 * The frequent-word drill needs no generated text either: it is an ordinary review session narrowed
 * to cards for words ranked at or above [maxRank] in the bundled frequency list.
 */
@Serializable
@SerialName("frequent_word_drill")
data class FrequentWordDrillContent(val cardLimit: Int = 12, val maxRank: Int = 500) : ActivityContent

/** Recognition cards need no generated text — cards come from the SRS store; this just caps the count. */
@Serializable
@SerialName("recognition_cards")
data class RecognitionCardsContent(val cardLimit: Int = 10) : ActivityContent

@Serializable
@SerialName("multiple_choice_cloze")
data class MultipleChoiceClozeContent(val items: List<ClozeItem>) : ActivityContent

/**
 * A block drawn from the cloze bank. The item text is snapshotted into the plan like every other
 * activity's, so a stored run still plays if the bank changes underneath it; [BankedClozeItem.sentenceId]
 * is carried alongside so a graded answer can still move that sentence's own schedule. An id that no
 * longer resolves simply doesn't update the bank — the item is still answerable.
 */
@Serializable
@SerialName("cloze_bank")
data class ClozeBankContent(val items: List<BankedClozeItem>) : ActivityContent

/** One banked sentence in a routine plan: the playable item plus the row it came from. */
@Serializable
data class BankedClozeItem(val sentenceId: Long, val item: ClozeItem)

/**
 * A listen-and-rebuild block: banked sentences with their clips rendered at build time, so the step
 * plays without waiting on the voice model. Like [ClozeBankContent] the items are snapshotted; a clip
 * the cache has since evicted plays as silence and the item is still solvable by Show answer.
 */
@Serializable
@SerialName("listen_rebuild")
data class ListenRebuildContent(val items: List<RebuildItem>) : ActivityContent

/** A swipe-to-sort set built from the user's own cards: each item names the bin (by index) it belongs in. */
@Serializable
@SerialName("swipe_sort")
data class SwipeSortContent(val dimension: SortDimension, val items: List<SwipeItem>) : ActivityContent

@Serializable
@SerialName("cognate_hunt")
data class CognateHuntContent(val words: List<CognateWord>) : ActivityContent

/** Superseded by [ShortTextContent]; retained for legacy plan decode — see [BedtimeStoryContent]. */
@Deprecated("Legacy plan decode only — new plans use ShortTextContent.")
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
data class ConjugationDrillContent(
    val items: List<ConjugationDrillItem>,
    /**
     * Which language's paradigm the [items] coordinates are read against. Defaults to Spanish because
     * every plan stored before the drill became language-parametric was Spanish, and a stored plan has
     * to keep decoding.
     */
    val language: String = "es",
) : ActivityContent

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

/** What a [SwipeSortContent] sorts by; the player derives the bin labels from the language profile. */
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
