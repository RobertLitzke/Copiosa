package com.copiosa.data.anki

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.ReviewLogEntity
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewRating
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A row destined for Anki's `notes` table. [flds] is already joined on the 0x1F field separator. */
data class OutNote(
    val id: Long,
    val guid: String,
    val mid: Long,
    val mod: Long,
    val tags: String,
    val flds: String,
    val sfld: String,
    val csum: Long,
)

/** A row destined for Anki's `cards` table (scheduling-relevant columns; the rest are constant). */
data class OutCard(
    val id: Long,
    val nid: Long,
    val did: Long,
    val ord: Int,
    val mod: Long,
    val type: Int,
    val queue: Int,
    val due: Long,
    val ivl: Long,
    val factor: Int,
    val reps: Int,
    val lapses: Int,
)

/** A row destined for Anki's `revlog` table. [id] is the review time (epoch ms); [time] its duration. */
data class OutRevlog(
    val id: Long,
    val cid: Long,
    val ease: Int,
    val ivl: Long,
    val lastIvl: Long,
    val factor: Int,
    val time: Int,
    val type: Int,
)

/**
 * A fully-built Anki collection ready to be written to `collection.anki21` and zipped into a `.apkg`.
 * The `col`-table JSON blobs ([modelsJson]/[decksJson]/[confJson]/[dconfJson]/[tagsJson]) plus the row
 * lists are everything Anki (and our own [AnkiImporter] on a re-import) needs.
 */
data class ExportCollection(
    val crt: Long, // collection creation, epoch SECONDS — review due dates are days relative to it
    val mod: Long, // epoch MILLIS
    val scm: Long, // schema modification time, epoch MILLIS
    val modelsJson: String,
    val decksJson: String,
    val confJson: String,
    val dconfJson: String,
    val tagsJson: String,
    val notes: List<OutNote>,
    val cards: List<OutCard>,
    val revlog: List<OutRevlog>,
    /** Distinct media filenames referenced by the exported notes; zip entries are their indices. */
    val mediaFiles: List<String>,
)

/**
 * Builds an Anki collection from Copiosa cards + their review history — the inverse of [AnkiImporter].
 *
 * Cards that came from Anki (carrying [FlashcardEntity.ankiNoteGuid] etc.) are reconstructed losslessly:
 * a note's forward/reverse cards collapse back into one note + two card rows, the original fields are
 * restored from [FlashcardEntity.ankiFieldsJson], and the structured `la:*` tags are regenerated so a
 * re-import rebuilds the same gender/region/kind/altForms. App-created cards get a synthesized note type
 * and a stable guid. Every transform the importer applies (SM-2 factor/interval, day-offset due dates,
 * revlog ease/type) is inverted, so scheduling and history round-trip. Pure (no I/O) and JVM-testable.
 */
class AnkiExporter {
    private val json = Json { ignoreUnknownKeys = true }

    /** One card plus its review history, as read from the database. */
    data class Input(val card: FlashcardEntity, val reviews: List<ReviewLogEntity>)

    fun export(cards: List<Input>, now: Long): ExportCollection {
        val crtMs = now - (now % DAY_MS) // align to a day boundary so review due-day offsets are exact
        val crt = crtMs / 1000L
        val modSec = crt // a stable per-object mod stamp in seconds; nothing reads it back

        // Decks: "Default" is reserved id 1; every other deck name gets a generated id.
        val deckNames = LinkedHashSet<String>().apply {
            add("Default")
            cards.forEach { add(it.card.ankiDeckName ?: DEFAULT_DECK_NAME) }
        }
        val deckIds = LinkedHashMap<String, Long>()
        var deckSeq = crtMs
        for (name in deckNames) deckIds[name] = if (name == "Default") 1L else ++deckSeq

        // Group cards into notes: imported cards share their guid (forward+reverse → one note); an
        // app-created card is its own note keyed on a stable synthetic guid.
        val groups = LinkedHashMap<String, MutableList<FlashcardEntity>>()
        for (input in cards) {
            val key = input.card.ankiNoteGuid ?: "la-${input.card.id}"
            groups.getOrPut(key) { ArrayList() }.add(input.card)
        }

        // First pass: each note type's field count (so flds is padded to match the model) and the set
        // of template ordinals it uses (so the model declares a template for every card).
        val fieldsByCardId = HashMap<Long, List<String>>()
        val fieldCountByMid = HashMap<Long, Int>()
        val ordsByMid = HashMap<Long, MutableSet<Int>>()
        for (input in cards) {
            val card = input.card
            val mid = card.ankiNoteTypeId ?: LA_BASIC_MID
            val f = noteFields(card)
            fieldsByCardId[card.id] = f
            fieldCountByMid[mid] = maxOf(fieldCountByMid[mid] ?: MIN_FIELDS, f.size, MIN_FIELDS)
            ordsByMid.getOrPut(mid) { sortedSetOf(0) }.add(card.ankiOrd ?: 0)
        }

        val outNotes = ArrayList<OutNote>()
        val outCards = ArrayList<OutCard>()
        val media = LinkedHashSet<String>()
        val exportCardId = HashMap<Long, Long>()
        var noteId = now
        var cardId = now
        var newPos = 1L

        for ((guid, group) in groups) {
            val rep = group.first()
            val mid = rep.ankiNoteTypeId ?: LA_BASIC_MID
            val fields = padFields(fieldsByCardId.getValue(rep.id), fieldCountByMid.getValue(mid))
            val nId = noteId++
            outNotes.add(
                OutNote(
                    id = nId,
                    guid = guid,
                    mid = mid,
                    mod = modSec,
                    tags = formatTags(buildTags(rep, group)),
                    flds = fields.joinToString(FIELD_SEP),
                    sfld = stripHtmlMedia(fields.first()),
                    csum = checksum(fields.first()),
                ),
            )
            for (card in group) {
                val cId = cardId++
                exportCardId[card.id] = cId
                card.imageFile?.let(media::add)
                card.audioFile?.let(media::add)

                val type = when (card.state) {
                    FlashcardState.NEW -> 0
                    FlashcardState.LEARNING -> 1
                    FlashcardState.REVIEW -> 2
                    FlashcardState.RELEARNING -> 3
                }
                val queue = when {
                    card.suspended -> -1
                    card.state == FlashcardState.NEW -> 0
                    card.state == FlashcardState.REVIEW -> 2
                    else -> 1 // learning / relearning
                }
                val due = when (card.state) {
                    FlashcardState.NEW -> newPos++ // new cards order by a position, not a date
                    FlashcardState.REVIEW -> ((card.dueAt - crtMs).toDouble() / DAY_MS).roundToLong()
                    else -> card.dueAt / 1000L // learning/relearning due is an epoch-seconds timestamp
                }
                outCards.add(
                    OutCard(
                        id = cId,
                        nid = nId,
                        did = deckIds.getValue(card.ankiDeckName ?: DEFAULT_DECK_NAME),
                        ord = card.ankiOrd ?: 0,
                        mod = modSec,
                        type = type,
                        queue = queue,
                        due = due,
                        ivl = daysToIvl(card.intervalDays),
                        factor = (card.easeFactor * 1000).roundToInt(),
                        reps = card.repetitions,
                        lapses = card.lapses,
                    ),
                )
            }
        }

        // Revlog: flatten every review, ordered by time. Two reviews can share a millisecond across
        // cards; bump colliding ids by 1 so the primary key stays unique while order is preserved.
        val outRevlog = ArrayList<OutRevlog>()
        var lastRevId = 0L
        val allReviews = cards.flatMap { input ->
            val cid = exportCardId[input.card.id] ?: return@flatMap emptyList()
            input.reviews.map { cid to it }
        }.sortedBy { it.second.reviewedAt }
        for ((cid, r) in allReviews) {
            val rid = if (r.reviewedAt <= lastRevId) lastRevId + 1 else r.reviewedAt
            lastRevId = rid
            outRevlog.add(
                OutRevlog(
                    id = rid,
                    cid = cid,
                    ease = easeFromRating(r.rating),
                    ivl = daysToIvl(r.intervalDays),
                    lastIvl = daysToIvl(r.lastIntervalDays),
                    factor = (r.easeFactor * 1000).roundToInt(),
                    time = r.reviewDurationMs ?: 0,
                    type = revlogType(r.state),
                ),
            )
        }

        val mids = cards.map { it.card.ankiNoteTypeId ?: LA_BASIC_MID }.distinct()
        return ExportCollection(
            crt = crt,
            mod = now,
            scm = now,
            modelsJson = buildModels(mids, fieldCountByMid, ordsByMid, modSec),
            decksJson = buildDecks(deckIds, modSec),
            confJson = buildConf(curModel = mids.firstOrNull() ?: LA_BASIC_MID, nextPos = newPos),
            dconfJson = DEFAULT_DCONF,
            tagsJson = "{}",
            notes = outNotes,
            cards = outCards,
            revlog = outRevlog,
            mediaFiles = media.toList(),
        )
    }

    /** The original Anki fields if the card was imported, else a `[front(+altForms), back]` projection. */
    private fun noteFields(card: FlashcardEntity): List<String> {
        val base = card.ankiFieldsJson?.let { raw ->
            runCatching { json.decodeFromString(ListSerializer(String.serializer()), raw) }
                .getOrNull()?.takeIf { it.isNotEmpty() }
        } ?: run {
            val front = if (card.altForms.isNullOrBlank()) card.front else "${card.front}; ${card.altForms}"
            listOf(front, card.back)
        }
        return withMediaRefs(base, card)
    }

    /**
     * Ensures the card's image/audio filenames are referenced in a field (as `<img>` / `[sound:]`), so a
     * re-import re-links them — needed for app-created cards and for a searched image attached to any
     * card, whose [FlashcardEntity.imageFile] isn't yet in the note's fields. No-op when already present.
     */
    private fun withMediaRefs(fields: List<String>, card: FlashcardEntity): List<String> {
        var out = fields
        card.imageFile?.takeIf { name -> out.none { it.contains(name) } }
            ?.let { out = appendToLast(out, "<img src=\"$it\">") }
        card.audioFile?.takeIf { name -> out.none { it.contains(name) } }
            ?.let { out = appendToLast(out, "[sound:$it]") }
        return out
    }

    private fun appendToLast(fields: List<String>, suffix: String): List<String> {
        if (fields.isEmpty()) return listOf(suffix)
        return fields.toMutableList().also { it[it.lastIndex] = (it[it.lastIndex] + " " + suffix).trim() }
    }

    private fun padFields(fields: List<String>, count: Int): List<String> =
        if (fields.size >= count) fields else fields + List(count - fields.size) { "" }

    /**
     * The note's Anki tags: the preserved passthrough tags plus regenerated `la:*` tags so a re-import
     * rebuilds the structured columns. Tag tokens can't contain whitespace, so a multi-word region/pos
     * (rare) is skipped rather than corrupting the tag string.
     */
    private fun buildTags(rep: FlashcardEntity, group: List<FlashcardEntity>): List<String> {
        val out = ArrayList<String>()
        rep.ankiTags?.split(WHITESPACE)?.filterTo(out) { it.isNotEmpty() }
        // Restore Anki's native "marked" tag (a note is marked if any of its cards is).
        if (group.any { it.marked }) out.add("marked")
        out.add("la:kind:${rep.cardKind.name.lowercase()}")
        rep.gender?.let { if (it.isNotBlank() && it.noWhitespace()) out.add("la:gender:${it.lowercase()}") }
        rep.region?.let { if (it.isNotBlank() && it.noWhitespace()) out.add("la:region:$it") }
        rep.partOfSpeech?.let { if (it.isNotBlank() && it.noWhitespace()) out.add("la:pos:$it") }
        out.add("la:tgt:${targetFieldIndex(group)}")
        return out.distinct()
    }

    /** Which projected field (0/1) holds the target language, recovered from the lowest-ord card. */
    private fun targetFieldIndex(group: List<FlashcardEntity>): Int {
        val rep = group.minByOrNull { it.ankiOrd ?: 0 } ?: return 0
        val isReverse = (rep.ankiOrd ?: 0) != 0
        return if (isReverse) {
            if (rep.frontIsTarget) 1 else 0
        } else {
            if (rep.frontIsTarget) 0 else 1
        }
    }

    private fun formatTags(tokens: List<String>): String =
        if (tokens.isEmpty()) "" else " ${tokens.joinToString(" ")} "

    /** Anki interval: whole days when ≥1, a negative-seconds count for sub-day, 0 for none. */
    private fun daysToIvl(days: Double): Long = when {
        days <= 0.0 -> 0L
        days < 1.0 -> -(days * 86_400).roundToLong()
        else -> days.roundToLong()
    }

    private fun easeFromRating(rating: ReviewRating): Int = when (rating) {
        ReviewRating.AGAIN -> 1
        ReviewRating.HARD -> 2
        ReviewRating.GOOD -> 3
        ReviewRating.EASY -> 4
    }

    private fun revlogType(state: FlashcardState): Int = when (state) {
        FlashcardState.LEARNING -> 0
        FlashcardState.RELEARNING -> 2
        else -> 1 // REVIEW (and the NEW sentinel, which shouldn't appear in history)
    }

    // ---- col JSON blobs --------------------------------------------------------

    private fun buildModels(
        mids: List<Long>,
        fieldCount: Map<Long, Int>,
        ords: Map<Long, Set<Int>>,
        mod: Long,
    ): String {
        val obj = buildJsonObject {
            for (mid in mids) {
                val fc = fieldCount[mid] ?: MIN_FIELDS
                val maxOrd = (ords[mid] ?: setOf(0)).max()
                putJsonObject(mid.toString()) {
                    put("id", mid)
                    put("name", if (mid == LA_BASIC_MID) "Copiosa Basic" else "Imported $mid")
                    put("type", 0)
                    put("mod", mod)
                    put("usn", -1)
                    put("sortf", 0)
                    put("did", 1)
                    put("latexPre", LATEX_PRE)
                    put("latexPost", "\\end{document}")
                    put("css", DEFAULT_CSS)
                    putJsonArray("flds") {
                        for (i in 0 until fc) addJsonObject {
                            put("name", "Field ${i + 1}")
                            put("ord", i)
                            put("sticky", false)
                            put("rtl", false)
                            put("font", "Arial")
                            put("size", 20)
                            putJsonArray("media") {}
                        }
                    }
                    putJsonArray("tmpls") {
                        for (t in 0..maxOrd) addJsonObject {
                            // ord 0 = forward (Field 1 → Field 2); ord 1 = reverse (Field 2 → Field 1).
                            val frontField = if (t == 1) 2 else 1
                            val backField = if (t == 1) 1 else 2
                            put("name", templateName(t))
                            put("ord", t)
                            put("qfmt", "{{Field $frontField}}")
                            put("afmt", "{{FrontSide}}\n\n<hr id=answer>\n\n{{Field $backField}}")
                            put("did", JsonNull)
                            put("bqfmt", "")
                            put("bafmt", "")
                            put("bfont", "")
                            put("bsize", 0)
                        }
                    }
                    putJsonArray("req") {
                        for (t in 0..maxOrd) addJsonArray {
                            add(t)
                            add("any")
                            addJsonArray { add(if (t == 1) 1 else 0) }
                        }
                    }
                    putJsonArray("tags") {}
                    putJsonArray("vers") {}
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun templateName(ord: Int): String = when (ord) {
        0 -> "Forward"
        1 -> "Reverse"
        else -> "Card ${ord + 1}"
    }

    private fun buildDecks(deckIds: Map<String, Long>, mod: Long): String {
        val obj = buildJsonObject {
            for ((name, id) in deckIds) {
                putJsonObject(id.toString()) {
                    put("id", id)
                    put("name", name)
                    put("mod", mod)
                    put("usn", -1)
                    putJsonArray("lrnToday") { add(0); add(0) }
                    putJsonArray("revToday") { add(0); add(0) }
                    putJsonArray("newToday") { add(0); add(0) }
                    putJsonArray("timeToday") { add(0); add(0) }
                    put("collapsed", false)
                    put("browserCollapsed", false)
                    put("desc", "")
                    put("dyn", 0)
                    put("conf", 1)
                    put("extendNew", 10)
                    put("extendRev", 50)
                }
            }
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun buildConf(curModel: Long, nextPos: Long): String {
        val obj = buildJsonObject {
            put("nextPos", nextPos)
            put("estTimes", true)
            putJsonArray("activeDecks") { add(1) }
            put("sortType", "noteFld")
            put("timeLim", 0)
            put("sortBackwards", false)
            put("addToCur", true)
            put("curDeck", 1)
            put("newBury", true)
            put("newSpread", 0)
            put("dueCounts", true)
            put("curModel", curModel.toString())
            put("collapseTime", 1200)
            put("schedVer", 2)
        }
        return json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun stripHtmlMedia(field: String): String {
        var s = SOUND_RE.replace(field, "")
        s = IMG_RE.replace(s) { " ${it.groupValues[1]} " }
        s = TAG_RE.replace(s, "")
        return unescape(s.replace("&nbsp;", " ")).trim()
    }

    /** Anki note checksum: first 8 hex digits of sha1(stripHTMLMedia(field0)) as an integer. */
    private fun checksum(field0: String): Long {
        val digest = MessageDigest.getInstance("SHA-1").digest(stripHtmlMedia(field0).toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 8).toLong(16)
    }

    private fun unescape(s: String): String =
        s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&amp;", "&")

    private fun String.noWhitespace(): Boolean = none { it.isWhitespace() }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val FIELD_SEP = "\u001F"
        private const val MIN_FIELDS = 2
        private const val DEFAULT_DECK_NAME = "Copiosa"
        /** Synthetic note-type id shared by every app-created (non-imported) card. */
        private const val LA_BASIC_MID = 1_700_000_000_001L
        private val WHITESPACE = Regex("""\s+""")
        private val SOUND_RE = Regex("""\[sound:[^]]*]""")
        private val IMG_RE = Regex("""<img[^>]*src="?([^">]+)"?[^>]*>""", RegexOption.IGNORE_CASE)
        private val TAG_RE = Regex("<[^>]*>")
        private const val DEFAULT_CSS =
            ".card {\n font-family: arial;\n font-size: 20px;\n text-align: center;\n" +
                " color: black;\n background-color: white;\n}\n"
        private const val LATEX_PRE =
            "\\documentclass[12pt]{article}\n\\special{papersize=3in,5in}\n" +
                "\\usepackage[utf8]{inputenc}\n\\usepackage{amssymb,amsmath}\n" +
                "\\pagestyle{empty}\n\\setlength{\\parindent}{0in}\n\\begin{document}\n"
        private const val DEFAULT_DCONF =
            """{"1":{"id":1,"name":"Default","mod":0,"usn":0,"maxTaken":60,"autoplay":true,"timer":0,""" +
                """"replayq":true,"new":{"bury":false,"delays":[1.0,10.0],"initialFactor":2500,""" +
                """"ints":[1,4,0],"order":1,"perDay":20,"separate":true},"rev":{"bury":false,"ease4":1.3,""" +
                """"ivlFct":1.0,"maxIvl":36500,"perDay":200,"hardFactor":1.2},"lapse":{"delays":[10.0],""" +
                """"leechAction":1,"leechFails":8,"minInt":1,"mult":0.0},"dyn":false}}"""
    }
}
