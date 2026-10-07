package com.copiosa.data.cardjson

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.cardDirection
import com.copiosa.data.local.noteKey
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewDirection
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Renders a deck as JSON meant to be *read and edited* — by the learner, or by an agent asked to
 * critique the notes and suggest new ones — and handed back through [CardJsonImporter]. The `.apkg`
 * export is the lossless one; this is the legible one, and the two are deliberately not the same
 * shape.
 *
 * It is an export of **notes**. A note is the word and everything true of it; the directions it is
 * studied in are its cards, and they appear inside it as a `cards` object keyed by direction, each
 * with nothing but its own schedule. That is the storage model exactly (see
 * [com.copiosa.data.local.NoteEntity]), and it is also what a reader wants: one entry to judge, with
 * "how is this going, each way" attached to it.
 *
 * Three more choices follow from being read by people. Sides are named for what they hold — `term`
 * is the target-language side, `translation` the native one. Anything that says nothing is left out
 * (nulls, false flags, an "unknown" part of speech), so a note is as short as its content. And the
 * output is **one note per line**: still a single valid JSON document, but one a reader can page,
 * grep, or diff note by note.
 *
 * Left out on purpose: the LLM-written explanation (several sentences a note, generated rather than
 * authored, and most of the file if included), images and audio, the cached inflections, the FSRS
 * internals and the Anki pass-through fields. Leaving them out loses nothing on the way back: the
 * import is a merge by [NoteJson.id], and a field the file doesn't mention is one it doesn't touch.
 */
object CardJsonExporter {

    /** Identifies the file to whatever reads it; bump [VERSION] if a field changes meaning. */
    const val FORMAT = "copiosa-cards"

    /**
     * 3: entries are notes and `id` is a note id; the directions are a `cards` object. In 1 and 2
     * an entry was a card (or a pair of them) and `id` was a card id — [CardJsonImporter] still
     * reads those, resolving each id to the note the card now belongs to.
     */
    const val VERSION = 3

    /**
     * The rules of the round trip, written into every file: whatever edits it next — a person or a
     * model — has only the file in front of it, not this source.
     */
    const val ABOUT =
        "Edit and re-import. One entry per note (a word, phrase or sentence); cards holds its " +
            "schedule in each direction it is studied: target-first shows the term and asks for " +
            "the translation, native-first the reverse. Entries are matched by id; an entry with " +
            "no id is added as a new note. Editable: term, translation, kind, pos, gender, cefr, " +
            "altForms, country, note, example, marked, and frozen inside a card, which sets that " +
            "one direction aside. A key left out is left unchanged (empty values are omitted on " +
            "export, so add the key to set one); null clears it. Adding a direction under cards " +
            "starts studying it. A new note is studied both ways unless it carries direction: " +
            "target-first or native-first. Everything else is read-only, and whatever is not in " +
            "this file (images, audio, review history) is kept. Removing an entry from the file " +
            "does not delete the note."

    /** One note as exported. Declaration order is output order. */
    @Serializable
    data class NoteJson(
        val id: Long,
        val term: String,
        val translation: String,
        val kind: CardKind,
        val pos: String? = null,
        val gender: String? = null,
        val cefr: String? = null,
        val altForms: String? = null,
        val country: String? = null,
        val note: String? = null,
        val example: String? = null,
        /** Rank in the bundled frequency list, when the word is in it. */
        val freqRank: Int? = null,
        val marked: Boolean? = null,
        /** One entry per direction studied, keyed [DIRECTION_TARGET_FIRST] / [DIRECTION_NATIVE_FIRST]. */
        val cards: Map<String, CardJson>,
        /** Which generator made the note ([FlashcardEntity.autoSource]); absent for hand-saved ones. */
        val source: String? = null,
        val created: String,
    )

    /** One direction of a note: its schedule, and whether it has been set aside. */
    @Serializable
    data class CardJson(
        val id: Long,
        val state: FlashcardState,
        val due: String,
        val intervalDays: Int? = null,
        val reviews: Int? = null,
        val lapses: Int? = null,
        val frozen: Boolean? = null,
    )

    private val json = Json { explicitNulls = false }

    /**
     * The whole document. [cards] is every card of the deck, in any order — rows sharing a note are
     * gathered here. [ranks] is frequency rank by card id, empty for a language with no list; [zone]
     * decides which calendar day a timestamp falls on.
     */
    fun encode(
        cards: List<FlashcardEntity>,
        ranks: Map<Long, Int>,
        targetLanguage: String,
        nativeLanguage: String,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        // Insertion-ordered, so notes come out in the order their first card was listed.
        val notes = cards.groupBy { it.noteKey }.values

        val header = buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("exported", dateOf(now, zone))
            put("targetLanguage", targetLanguage)
            put("nativeLanguage", nativeLanguage)
            put("count", notes.size)
            put("about", ABOUT)
        }.toString()
        return buildString {
            // The header object is reopened so "notes" lands inside it, last, one note to a line.
            append(header.removeSuffix("}"))
            append(",\"notes\":[\n")
            notes.forEachIndexed { index, noteCards ->
                if (index > 0) append(",\n")
                // The rank is the word's; whichever card the frequency list was matched through has it.
                val rank = noteCards.firstNotNullOfOrNull { ranks[it.id] }
                append(json.encodeToString(NoteJson.serializer(), toJson(noteCards, rank, zone)))
            }
            append("\n]}\n")
        }
    }

    /** One note from its cards, which all carry the same content. */
    internal fun toJson(cards: List<FlashcardEntity>, rank: Int?, zone: ZoneId): NoteJson {
        val note = cards.first()
        return NoteJson(
            id = note.noteKey,
            term = note.targetSide,
            translation = note.nativeSide,
            kind = note.cardKind,
            pos = note.partOfSpeech.clean()?.takeIf { it != "unknown" },
            gender = note.gender.clean(),
            cefr = note.cefrLevel.clean(),
            altForms = note.altForms.clean(),
            country = note.country.clean()?.replace('_', ' '),
            note = note.usageNote.clean(),
            example = note.contextSentence.clean(),
            freqRank = rank,
            marked = true.takeIf { note.marked },
            // Term-first leads, so a note's two schedules always read in the same order.
            cards = cards.sortedBy { it.cardDirection != ReviewDirection.TARGET_FIRST }
                .associate { directionName(it.cardDirection) to cardJson(it, zone) },
            source = note.autoSource.clean(),
            created = dateOf(note.createdAt, zone),
        )
    }

    private fun cardJson(card: FlashcardEntity, zone: ZoneId) = CardJson(
        id = card.id,
        state = card.state,
        due = dateOf(card.dueAt, zone),
        intervalDays = card.intervalDays.roundToInt().takeIf { it > 0 },
        reviews = card.repetitions.takeIf { it > 0 },
        lapses = card.lapses.takeIf { it > 0 },
        frozen = true.takeIf { card.suspended },
    )

    const val DIRECTION_BOTH = "both"
    const val DIRECTION_TARGET_FIRST = "target-first"
    const val DIRECTION_NATIVE_FIRST = "native-first"

    /** A [ReviewDirection] as the file spells it. */
    fun directionName(direction: ReviewDirection): String = when (direction) {
        ReviewDirection.BOTH -> DIRECTION_BOTH
        ReviewDirection.TARGET_FIRST -> DIRECTION_TARGET_FIRST
        ReviewDirection.NATIVE_FIRST -> DIRECTION_NATIVE_FIRST
    }

    /** The reverse of [directionName]; null for anything else, which the import then ignores. */
    fun directionOf(name: String): ReviewDirection? = when (name.trim().lowercase()) {
        DIRECTION_BOTH -> ReviewDirection.BOTH
        DIRECTION_TARGET_FIRST -> ReviewDirection.TARGET_FIRST
        DIRECTION_NATIVE_FIRST -> ReviewDirection.NATIVE_FIRST
        else -> null
    }

    private fun String?.clean(): String? = this?.trim()?.ifEmpty { null }

    internal fun dateOf(epochMs: Long, zone: ZoneId): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()
}

/** The target-language side, wherever the row keeps it — one not yet stored may face either way. */
internal val FlashcardEntity.targetSide: String get() = if (frontIsTarget) front else back

/** The native-language side, wherever the row keeps it. */
internal val FlashcardEntity.nativeSide: String get() = if (frontIsTarget) back else front
