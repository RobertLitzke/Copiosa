package com.copiosa.data.anki

import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.ReviewLogEntity
import com.copiosa.domain.model.CardKind
import com.copiosa.domain.model.FlashcardState
import com.copiosa.domain.model.ReviewDirection
import com.copiosa.domain.model.ReviewRating
import com.copiosa.domain.vocab.BackNoteSplitter
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One card ready to persist: the card plus its review history (each log's `cardId` is filled on insert). */
data class ImportedCard(val card: FlashcardEntity, val reviews: List<ReviewLogEntity>)

data class ImportResult(
    val cards: List<ImportedCard>,
    /** Distinct media filenames referenced by the imported cards — the set to copy into the media store. */
    val mediaFiles: Set<String>,
)

/**
 * Maps an Anki collection ([RawApkg]) into Copiosa cards under the "simplified, reversible" model:
 * one Anki *card* becomes one [FlashcardEntity] carrying that card's own schedule + history. Two text
 * fields are projected to front/back for display; every original field, the note-type id, tags, deck,
 * and template ordinal are kept as passthrough so a later re-export reconstructs the note losslessly.
 *
 * Pure (no I/O) so it can be unit-tested against a fixture via a JDBC-backed [RawApkg].
 */
class AnkiImporter(
    private val targetLanguage: String,
    private val nativeLanguage: String,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private data class NoteType(val templateNames: List<String>)

    fun import(raw: RawApkg, now: Long): ImportResult {
        val models = parseModels(raw.modelsJson)
        val deckNames = parseDecks(raw.decksJson)
        val notesById = raw.notes().associateBy { it.id }
        val revlogByCard = raw.revlog().groupBy { it.cid }
        val crtMs = raw.crt * 1000L

        val media = LinkedHashSet<String>()
        val imported = raw.cards().mapNotNull { card ->
            val note = notesById[card.nid] ?: return@mapNotNull null
            val type = models[note.mid]
            val (projFront, projBack) = projectFrontBack(note, type, card.ord)

            // Structured fields from a cleaned deck's `la:*` tags (see tools/clean_spanish_apkg.py).
            val la = parseLaTags(note.tags)
            val frontIsTarget = when (la["tgt"]) {
                "1" -> isReverseTemplate(type, card.ord)
                "0" -> !isReverseTemplate(type, card.ord)
                else -> !isReverseTemplate(type, card.ord) // uncleaned: assume forward = target-front
            }
            // The target (Spanish) side may carry "; "-joined synonyms; the primary is the headword,
            // the rest become altForms. Splitting the target side keeps the card's review direction.
            val (primary, altForms) = splitPrimaryAlt(if (frontIsTarget) projFront else projBack)
            val front = if (frontIsTarget) primary else projFront
            // Keep the translation clean: peel a crammed usage note out of the gloss side only (a
            // reverse card's back is the target headword, which must never be split).
            val (back, usageNote) = if (frontIsTarget) BackNoteSplitter.split(projBack) else primary to null
            val cardKind = la["kind"]?.let { runCatching { CardKind.valueOf(it.uppercase()) }.getOrNull() }
                ?: CardKind.VOCAB

            val image = firstMatch(note.fields, IMG_FILE_RE)
            val audio = firstMatch(note.fields, SOUND_FILE_RE)
            image?.let(media::add)
            audio?.let(media::add)

            val sched = schedule(card, crtMs, now)
            val cardReviews = revlogByCard[card.id].orEmpty().sortedBy { it.id }

            val entity = FlashcardEntity(
                front = front,
                back = back,
                usageNote = usageNote,
                cardKind = cardKind,
                gender = la["gender"]?.uppercase(),
                // The retired `region` slug is no longer imported; a user-set country round-trips via
                // `la:country` instead (single-token only, like the other structured tags).
                country = la["country"],
                altForms = altForms,
                frontIsTarget = frontIsTarget,
                // Preserve the Anki card's direction: forward cards prompt from the target side,
                // reverse cards from the native side (i.e. their front, which is shown first).
                reviewDirection = if (frontIsTarget) ReviewDirection.TARGET_FIRST else ReviewDirection.NATIVE_FIRST,
                partOfSpeech = la["pos"],
                targetLanguage = targetLanguage,
                nativeLanguage = nativeLanguage,
                createdAt = now,
                dueAt = sched.dueAt,
                intervalDays = sched.intervalDays,
                easeFactor = easeFromFactor(card.factor),
                repetitions = card.reps,
                lapses = card.lapses,
                lastReviewedAt = cardReviews.lastOrNull()?.id,
                state = sched.state,
                stability = sched.stability,
                difficulty = sched.difficulty,
                ankiNoteGuid = note.guid,
                ankiOrd = card.ord,
                ankiNoteTypeId = note.mid,
                ankiFieldsJson = json.encodeToString(ListSerializer(String.serializer()), note.fields),
                // Anki's native "marked" tag becomes our per-card flag; stripped from the passthrough so
                // the [marked] column is the single source of truth on re-export.
                marked = note.tags.split(WHITESPACE_RE).any { it.equals("marked", ignoreCase = true) },
                ankiTags = note.tags.split(WHITESPACE_RE)
                    .filter { it.isNotEmpty() && !it.startsWith("la:") && !it.equals("marked", ignoreCase = true) }
                    .joinToString(" ").ifEmpty { null },
                ankiDeckName = deckNames[card.did],
                imageFile = image,
                audioFile = audio,
            )
            ImportedCard(entity, cardReviews.map { it.toReviewLog() })
        }
        return ImportResult(imported, media)
    }

    private data class Sched(
        val state: FlashcardState,
        val dueAt: Long,
        val intervalDays: Double,
        val stability: Double?,
        val difficulty: Double?,
    )

    /**
     * Converts Anki scheduling to ours. Review cards keep their absolute due date (days since `crt`) and
     * are seeded into FSRS from their interval (interval ≈ stability at 0.9 retention) so they continue
     * rather than reset. New/learning cards carry no memory state and are made due now.
     */
    private fun schedule(card: RawCard, crtMs: Long, now: Long): Sched {
        val interval = card.ivl.toDouble().coerceAtLeast(0.0)
        val stability = if (interval > 0.0) interval.coerceAtLeast(MIN_STABILITY) else null
        val difficulty = if (stability != null) SEED_DIFFICULTY else null
        return when (card.type) {
            TYPE_REVIEW -> Sched(FlashcardState.REVIEW, crtMs + card.due * DAY_MS, interval, stability, difficulty)
            TYPE_RELEARNING -> Sched(FlashcardState.RELEARNING, now, interval, stability, difficulty)
            TYPE_LEARNING -> Sched(FlashcardState.LEARNING, now, 0.0, null, null)
            else -> Sched(FlashcardState.NEW, now, 0.0, null, null)
        }
    }

    /** Picks the first two text-bearing fields (in field order) as front/back, swapped for reverse cards. */
    private fun projectFrontBack(note: RawNote, type: NoteType?, ord: Int): Pair<String, String> {
        val texts = note.fields.map { htmlToPlainText(it) }.filter { it.isNotEmpty() }
        val a = texts.getOrElse(0) { "" }
        val b = texts.getOrElse(1) { "" }
        return if (isReverseTemplate(type, ord)) b to a else a to b
    }

    private fun isReverseTemplate(type: NoteType?, ord: Int): Boolean {
        val name = type?.templateNames?.getOrNull(ord)?.lowercase()
        return when {
            name == null -> ord != 0
            name.contains("rever") || name.contains("back") -> true
            name.contains("forward") || name.contains("front") -> false
            else -> ord != 0
        }
    }

    /** Parses a cleaned deck's `la:<key>:<value>` tags (e.g. `la:kind:vocab`) into a key→value map. */
    private fun parseLaTags(tags: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (tok in tags.split(WHITESPACE_RE)) {
            if (!tok.startsWith("la:")) continue
            val parts = tok.split(":", limit = 3)
            if (parts.size == 3) out.putIfAbsent(parts[1], parts[2])
        }
        return out
    }

    /** Splits a "primary; alt1; alt2" target term into (primary, "alt1; alt2"-or-null). */
    private fun splitPrimaryAlt(target: String): Pair<String, String?> {
        val parts = target.split("; ").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size <= 1) return target.trim() to null
        return parts.first() to parts.drop(1).joinToString("; ")
    }

    private fun RawRevlog.toReviewLog(): ReviewLogEntity = ReviewLogEntity(
        cardId = 0L, // set when the owning card is persisted
        reviewedAt = id,
        rating = ratingFromEase(ease),
        lastIntervalDays = ivlToDays(lastIvl),
        intervalDays = ivlToDays(ivl),
        easeFactor = easeFromFactor(factor),
        state = stateFromRevlogType(type),
        reviewDurationMs = time,
    )

    private fun parseModels(jsonStr: String): Map<Long, NoteType> =
        json.parseToJsonElement(jsonStr).jsonObject.mapNotNull { (mid, el) ->
            val tmpls = el.jsonObject["tmpls"]?.jsonArray
                ?.map { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull ?: "" }
                .orEmpty()
            mid.toLongOrNull()?.let { it to NoteType(tmpls) }
        }.toMap()

    private fun parseDecks(jsonStr: String): Map<Long, String> =
        json.parseToJsonElement(jsonStr).jsonObject.mapNotNull { (did, el) ->
            val name = el.jsonObject["name"]?.jsonPrimitive?.contentOrNull
            val id = did.toLongOrNull()
            if (id != null && name != null) id to name else null
        }.toMap()

    private fun firstMatch(fields: List<String>, re: Regex): String? =
        fields.firstNotNullOfOrNull { re.find(it)?.groupValues?.getOrNull(1) }

    private fun easeFromFactor(factor: Int): Double = if (factor > 0) factor / 1000.0 else 2.5

    /** Anki stores intervals as positive days, or as negative seconds for sub-day steps. */
    private fun ivlToDays(v: Long): Double = if (v < 0) -v / 86_400.0 else v.toDouble()

    private fun ratingFromEase(ease: Int): ReviewRating = when (ease) {
        1 -> ReviewRating.AGAIN
        2 -> ReviewRating.HARD
        4 -> ReviewRating.EASY
        else -> ReviewRating.GOOD
    }

    private fun stateFromRevlogType(type: Int): FlashcardState = when (type) {
        0 -> FlashcardState.LEARNING
        2 -> FlashcardState.RELEARNING
        else -> FlashcardState.REVIEW
    }

    companion object {
        private const val DAY_MS = 86_400_000L
        private const val MIN_STABILITY = 0.5
        private const val SEED_DIFFICULTY = 5.0
        private const val TYPE_NEW = 0
        private const val TYPE_LEARNING = 1
        private const val TYPE_REVIEW = 2
        private const val TYPE_RELEARNING = 3
        private val SOUND_FILE_RE = Regex("""\[sound:([^]]+)]""")
        private val IMG_FILE_RE = Regex("""<img[^>]*src="([^"]+)"""", RegexOption.IGNORE_CASE)
        private val SOUND_STRIP_RE = Regex("""\[sound:[^]]*]""")
        // Tags that imply a line/word boundary (so dropping them must leave a space).
        private val BLOCK_TAG_RE =
            Regex("""</?(?:br|div|p|li|tr|h[1-6]|blockquote)(?:\s[^>]*)?/?>""", RegexOption.IGNORE_CASE)
        // Any remaining (inline) tag — removed outright, *not* replaced with a space, so styling spans
        // inside a word like `t<span>a</span>par` don't get split into "t a par".
        private val TAG_STRIP_RE = Regex("<[^>]*>")
        private val WHITESPACE_RE = Regex("""\s+""")

        /**
         * Renders an Anki HTML field as display text. Block/line tags (`<br>`, `</div>`, …) collapse to
         * a space so words on separate lines stay separated; inline tags drop out with no space so they
         * don't fracture a word; the common HTML entities are decoded; whitespace runs are collapsed.
         * Public for direct unit testing of the projection.
         */
        fun htmlToPlainText(field: String): String =
            field
                .replace(SOUND_STRIP_RE, " ")
                .replace(BLOCK_TAG_RE, " ")
                .replace(TAG_STRIP_RE, "")
                .let(::unescapeHtml)
                .replace(WHITESPACE_RE, " ")
                .trim()

        /** Decodes the handful of HTML entities Anki fields commonly carry. `&amp;` is decoded last. */
        private fun unescapeHtml(s: String): String =
            s.replace("&nbsp;", " ")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
    }
}
