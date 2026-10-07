package com.copiosa.data.cardjson

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.cardDirection
import com.copiosa.data.local.noteKey
import com.copiosa.domain.highlight.normalizeWord
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.vocab.AltForms
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * One entry's worth of edits from an imported file: only the keys the file actually carries.
 *
 * The whole import rests on telling "absent" from "null". [CardJsonExporter] omits everything empty,
 * so a missing key cannot mean "clear this" — it means the file has nothing to say, and the note
 * keeps what it has. That is also what protects everything the format never carries (image, audio,
 * explanation, schedule, the Anki pass-through): no key, no change. An explicit `null` or empty
 * string is the way to clear a field.
 */
class NotePatch internal constructor(private val obj: JsonObject) {

    /** What the entry calls itself: a note id, or in a file older than version 3, a card id. */
    val id: Long? get() = (obj["id"] as? JsonPrimitive)?.longOrNull

    internal val hasId: Boolean get() = "id" in obj && obj["id"] !is JsonNull

    /** The export's `created` date, used only to check that [id] still means the same note. */
    internal val created: LocalDate?
        get() = text("created")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    val term: String? get() = text("term")
    val translation: String? get() = text("translation")

    /**
     * For an entry that describes a new note: which way it should be studied, or null to take the
     * kind's default. Ignored on an existing note, whose directions are its `cards`.
     */
    val direction: ReviewDirection? get() = text("direction")?.let(CardJsonExporter::directionOf)

    /**
     * What the entry says about each direction under `cards`: the directions it names at all, and
     * for each, whether it sets the frozen flag. A direction the note has no card for is a request
     * to start studying it.
     */
    val cardEdits: Map<ReviewDirection, Boolean?>
        get() = (obj["cards"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
            val direction = CardJsonExporter.directionOf(name)?.takeIf { it != ReviewDirection.BOTH }
                ?: return@mapNotNull null
            direction to ((value as? JsonObject)?.get("frozen") as? JsonPrimitive)?.booleanOrNull
        }.toMap()

    /**
     * [card] with this entry's content edits applied; returns [card] itself, unchanged, when there
     * are none. Content is the note's, so any card of the note will do as [card] and every card of
     * it sees the result; schedules and frozen flags are not content and are untouched here.
     *
     * A value is compared after the same tidying the export applied, so a file that was exported and
     * re-imported untouched changes nothing — not even a stored trailing space.
     */
    fun applyTo(card: FlashcardEntity): FlashcardEntity {
        var out = card
        // Both sides are required, so a blank or null one is ignored rather than emptying the note.
        term?.let { out = out.withTarget(mergeSide(out.targetSide, it)) }
        translation?.let { out = out.withNative(mergeSide(out.nativeSide, it)) }

        text("kind")?.let { name -> CardKind.entries.firstOrNull { it.name.equals(name, ignoreCase = true) } }
            ?.let { out = out.copy(cardKind = it) }
        if ("pos" in obj) {
            out = out.copy(partOfSpeech = merge(out.partOfSpeech, text("pos")?.lowercase(), ignoreCase = true))
        }
        // An unrecognised code is ignored rather than stored: the editor could not show it.
        if ("gender" in obj) {
            val gender = text("gender")?.uppercase()
            if (gender == null || gender in GENDERS) out = out.copy(gender = merge(out.gender, gender, ignoreCase = true))
        }
        if ("cefr" in obj) {
            val cefr = text("cefr")?.uppercase()
            if (cefr == null || cefr in CEFR) out = out.copy(cefrLevel = merge(out.cefrLevel, cefr, ignoreCase = true))
        }
        if ("altForms" in obj) out = out.copy(altForms = merge(out.altForms, AltForms.normalize(text("altForms"))))
        if ("country" in obj) {
            // Stored whitespace-free for the Anki tag round-trip; the export shows it with spaces.
            out = out.copy(country = merge(out.country, text("country")?.replace(WHITESPACE, "_")))
        }
        if ("note" in obj) out = out.copy(usageNote = merge(out.usageNote, text("note")))
        if ("example" in obj) out = out.copy(contextSentence = merge(out.contextSentence, text("example")))
        flag("marked")?.let { out = out.copy(marked = it) }
        return if (out == card) card else out
    }

    /**
     * The directions of [cards] this entry changes: one to freeze or unfreeze (mapped to the wanted
     * frozen state), or one the note has no card for yet (mapped to the state it should start in).
     */
    fun directionChanges(cards: List<FlashcardEntity>): Map<ReviewDirection, Boolean> =
        cardEdits.mapNotNull { (direction, frozen) ->
            val card = cards.firstOrNull { it.cardDirection == direction }
            when {
                card == null -> direction to (frozen ?: false)
                frozen != null && frozen != card.suspended -> direction to frozen
                else -> null
            }
        }.toMap()

    /** Whether importing this entry would change anything about the note [cards] belong to. */
    fun changes(cards: List<FlashcardEntity>): Boolean =
        applyTo(cards.first()) !== cards.first() || directionChanges(cards).isNotEmpty()

    /** The trimmed string under [key], or null when it is absent, null, blank or not a string. */
    private fun text(key: String): String? =
        (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.ifEmpty { null }

    private fun flag(key: String): Boolean? = (obj[key] as? JsonPrimitive)?.booleanOrNull

    /** [incoming], unless it is only the tidied form of [current] — then [current], byte for byte. */
    private fun merge(current: String?, incoming: String?, ignoreCase: Boolean = false): String? =
        if (incoming.equals(current?.trim()?.ifEmpty { null }, ignoreCase)) current else incoming

    private fun mergeSide(current: String, incoming: String): String =
        if (incoming == current.trim()) current else incoming

    private fun FlashcardEntity.withTarget(text: String) = if (frontIsTarget) copy(front = text) else copy(back = text)

    private fun FlashcardEntity.withNative(text: String) = if (frontIsTarget) copy(back = text) else copy(front = text)

    private companion object {
        val GENDERS = setOf("M", "F", "MF", "N", "MPL", "FPL")
        val CEFR = setOf("A1", "A2", "B1", "B2", "C1", "C2")
        val WHITESPACE = Regex("\\s+")
    }
}

/** An entry that matches a note and would change it. */
data class PlannedUpdate(
    val patch: NotePatch,
    /** The note, by its [FlashcardEntity.noteId] — read from the deck, whatever kind of id the file gave. */
    val noteId: Long,
)

/**
 * What importing a file would do, worked out before anything is written so the learner can be shown
 * real counts and agree to them.
 */
data class CardJsonImportPlan(
    /** Entries that match a note and would change its content or its directions. */
    val updates: List<PlannedUpdate>,
    /** Entries with no id: new notes. */
    val additions: List<NotePatch>,
    /** Entries that match a note and say nothing new. */
    val unchanged: Int,
    /** Entries whose id matches no note in this deck. */
    val unmatched: Int,
    /** New notes whose word the deck already teaches. */
    val duplicates: Int,
    /** Entries that aren't a note: not an object, or a new one missing its word or translation. */
    val invalid: Int,
) {
    val isEmpty: Boolean get() = updates.isEmpty() && additions.isEmpty()
}

/**
 * Reads a file [CardJsonExporter] wrote — or one in its shape that something else wrote — and plans a
 * merge into the deck. Pure: it decides, [CardJsonImportService] writes.
 *
 * The merge is deliberately one-way and additive. A note is matched by id and edited in place; an
 * entry with no id is a new note; a note the file leaves out is left alone, never deleted — a file
 * trimmed down to the forty notes worth fixing must not cost the other three thousand.
 */
object CardJsonImporter {

    /** The file can't be imported at all; [message] is shown to the learner as-is. */
    class InvalidFileException(message: String) : Exception(message)

    /**
     * Plans the import of [text] into [existing], every card of the active profile's deck.
     *
     * An id is only trusted when the entry's `created` date agrees with the note's (to within a day,
     * which absorbs a timezone change). Ids are row numbers: a file from another install, or from
     * before a restore, would otherwise match unrelated notes and quietly overwrite them.
     *
     * A file older than version 3 was a list of cards, and its ids are card ids. Each is resolved to
     * the note that card now belongs to, so an edit made in such a file still lands — on the note,
     * which is where a card's wording has lived since the split.
     */
    fun plan(
        text: String,
        existing: List<FlashcardEntity>,
        targetLanguage: String,
        zone: ZoneId = ZoneId.systemDefault(),
    ): CardJsonImportPlan {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull()
            ?: throw InvalidFileException("This file isn't valid JSON.")
        val (entries, idsAreCards) = entriesOf(root, targetLanguage)

        val byNote = existing.groupBy { it.noteKey }
        val noteOfCard = existing.associate { it.id to it.noteKey }
        // Grown as additions are accepted, so a word listed twice in the file is added once.
        val owned = existing.filter { it.cardKind.isLexicalVocab }.mapTo(HashSet()) { normalizeWord(it.targetSide) }

        val updates = ArrayList<PlannedUpdate>()
        val additions = ArrayList<NotePatch>()
        var unchanged = 0
        var unmatched = 0
        var duplicates = 0
        var invalid = 0
        for (entry in entries) {
            val patch = (entry as? JsonObject)?.let(::NotePatch)
            when {
                patch == null -> invalid++
                patch.hasId -> {
                    val noteId = patch.id?.let { if (idsAreCards) noteOfCard[it] else it }
                    val cards = noteId?.let(byNote::get)
                    when {
                        cards == null || !sameNote(cards.first(), patch, zone) -> unmatched++
                        patch.changes(cards) -> updates += PlannedUpdate(patch, noteId)
                        else -> unchanged++
                    }
                }
                patch.term == null || patch.translation == null -> invalid++
                !owned.add(normalizeWord(patch.term!!)) -> duplicates++
                else -> additions += patch
            }
        }
        return CardJsonImportPlan(updates, additions, unchanged, unmatched, duplicates, invalid)
    }

    /**
     * A file's entries, and whether their ids are card ids (a file from before version 3) rather
     * than note ids. The entries are the `notes` array of an exported document — `cards`, in an
     * older one — or a bare top-level array, the shape a list of suggested new notes most naturally
     * arrives in.
     */
    private fun entriesOf(root: JsonElement, targetLanguage: String): Pair<JsonArray, Boolean> = when (root) {
        is JsonArray -> root to false
        is JsonObject -> {
            val fileLanguage = (root["targetLanguage"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (fileLanguage != null && !fileLanguage.equals(targetLanguage, ignoreCase = true)) {
                throw InvalidFileException(
                    "This file holds \"$fileLanguage\" notes, but the active profile is \"$targetLanguage\". " +
                        "Switch profiles and import it again.",
                )
            }
            val version = (root["version"] as? JsonPrimitive)?.intOrNull
            val entries = root["notes"] as? JsonArray ?: root["cards"] as? JsonArray
                ?: throw InvalidFileException("This file has no \"notes\" list.")
            entries to (version != null && version < FIRST_NOTE_VERSION)
        }
        else -> throw InvalidFileException("This file has no \"notes\" list.")
    }

    private fun sameNote(card: FlashcardEntity, patch: NotePatch, zone: ZoneId): Boolean {
        val claimed = patch.created ?: return true
        val actual = LocalDate.parse(CardJsonExporter.dateOf(card.createdAt, zone))
        return abs(ChronoUnit.DAYS.between(claimed, actual)) <= 1
    }

    /** The first format version in which an entry is a note and its id a note id. */
    private const val FIRST_NOTE_VERSION = 3
}
