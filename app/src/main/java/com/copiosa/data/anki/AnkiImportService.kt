package com.copiosa.data.anki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.copiosa.data.local.AppDatabase
import com.copiosa.data.local.ClozeDao
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.FlashcardEntity
import com.copiosa.data.local.cardDirection
import com.copiosa.data.local.toCard
import com.copiosa.data.local.toNote
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates importing an Anki `.apkg`: copies the picked file out of the content URI, unpacks the
 * collection + media, runs the pure [AnkiImporter], copies referenced media into the app store, and
 * applies a **state transfer** to the database — the incoming deck is authoritative, so a card already
 * imported before (matched on `ankiNoteGuid` + `ankiOrd`) is overwritten in place and its history
 * replaced, while cards created in Copiosa are left untouched.
 */
@Singleton
class AnkiImportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
    private val flashcardDao: FlashcardDao,
    private val clozeDao: ClozeDao,
    private val reviewLogDao: ReviewLogDao,
    private val settingsRepo: SettingsRepository,
) {
    data class Summary(val newCards: Int, val updatedCards: Int, val mediaFiles: Int)

    /** Thrown for the newer zstd/protobuf `.anki21b` format we don't read yet, or non-apkg files. */
    class UnsupportedFormatException(message: String) : Exception(message)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun import(uri: Uri): Summary = withContext(Dispatchers.IO) {
        val work = File(context.cacheDir, "anki_import_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val apkg = File(work, "import.apkg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                apkg.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("Could not open the selected file.")

            ZipFile(apkg).use { zip ->
                val collName = when {
                    zip.getEntry("collection.anki21") != null -> "collection.anki21"
                    zip.getEntry("collection.anki2") != null -> "collection.anki2"
                    zip.getEntry("collection.anki21b") != null -> throw UnsupportedFormatException(
                        "This deck uses Anki's newer format. In Anki, export it again with " +
                            "\"Support older Anki versions\" enabled, then try once more.",
                    )
                    else -> throw UnsupportedFormatException("This file isn't a recognizable Anki .apkg deck.")
                }
                val collFile = File(work, collName)
                zip.getInputStream(zip.getEntry(collName)).use { i -> collFile.outputStream().use { i.copyTo(it) } }

                val mediaMap = readMediaMap(zip)
                val settings = settingsRepo.settings.first()
                val result = SQLiteDatabase.openDatabase(collFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
                    .use { sqlite ->
                        AnkiImporter(settings.targetLanguage, settings.nativeLanguage)
                            .import(AndroidRawApkg(sqlite, mediaMap), System.currentTimeMillis())
                    }

                // Parsing and the media copy are plain blocking work; this is where a cancel made
                // during the parse is noticed, before a large deck's media starts landing on disk.
                ensureActive()
                val mediaCopied = copyMedia(zip, mediaMap, result.mediaFiles)
                val (added, updated) = persist(result)
                Summary(newCards = added, updatedCards = updated, mediaFiles = mediaCopied)
            }
        } finally {
            work.deleteRecursively()
        }
    }

    private fun readMediaMap(zip: ZipFile): Map<String, String> =
        zip.getEntry("media")?.let { e ->
            json.parseToJsonElement(zip.getInputStream(e).readBytes().decodeToString())
                .jsonObject.mapValues { it.value.jsonPrimitive.content }
        }.orEmpty()

    private fun copyMedia(zip: ZipFile, mediaMap: Map<String, String>, wanted: Set<String>): Int {
        if (wanted.isEmpty()) return 0
        val numberByName = mediaMap.entries.associate { (number, name) -> name to number }
        val dir = AnkiMedia.dir(context).apply { mkdirs() }
        var copied = 0
        for (name in wanted) {
            val entry = numberByName[name]?.let { zip.getEntry(it) } ?: continue
            val out = File(dir, AnkiMedia.safeName(name))
            zip.getInputStream(entry).use { i -> out.outputStream().use { i.copyTo(it) } }
            copied++
        }
        return copied
    }

    /**
     * Stores the import note by note. Anki's card rows arrive one per direction, each carrying its
     * own projection of the note's text; here the text is the note's, so one card speaks for it and
     * the rest contribute only their schedules and histories.
     */
    private suspend fun persist(result: ImportResult): Pair<Int, Int> {
        var added = 0
        var updated = 0
        db.withTransaction {
            // A card with no guid can't be matched to anything, so it stands as a note of its own.
            val byNote = result.cards.withIndex().groupBy({ (i, c) -> c.card.ankiNoteGuid ?: "#$i" }, { it.value })
            for (group in byNote.values) {
                // A card the learner already converted into the cloze bank must not come back:
                // re-import matches on (guid, ord), so without this check the next .apkg import
                // would recreate the flashcard the conversion deleted and silently undo it.
                val incoming = group.filterNot { imported ->
                    val guid = imported.card.ankiNoteGuid
                    val ord = imported.card.ankiOrd
                    guid != null && ord != null && clozeDao.existsForAnkiKey(guid, ord)
                }
                if (incoming.isEmpty()) continue

                val content = noteContent(incoming.map { it.card })
                val existingNoteId = content.ankiNoteGuid?.let { flashcardDao.noteIdForAnkiGuid(it, content.targetLanguage) }
                val siblings = existingNoteId?.let { flashcardDao.cardsOfNote(it) }.orEmpty()
                val noteId = if (existingNoteId == null) {
                    flashcardDao.insertNote(content.toNote(id = 0))
                } else {
                    // Anki is authoritative for content; preserve Copiosa-only enrichment and the
                    // note's identity so it updates in place rather than duplicating.
                    // A note always has a card; `content` stands in for one that somehow has none.
                    val existing = siblings.firstOrNull() ?: content
                    flashcardDao.updateContent(
                        content.copy(
                            noteId = existingNoteId,
                            createdAt = existing.createdAt,
                            sourceConversationId = existing.sourceConversationId,
                            sourceBookId = existing.sourceBookId,
                            sourceRoutineRunId = existing.sourceRoutineRunId,
                            explanation = existing.explanation,
                            // Keep a user-typed usage note; otherwise adopt the freshly-split one.
                            usageNote = existing.usageNote ?: content.usageNote,
                            partOfSpeech = existing.partOfSpeech,
                            cefrLevel = existing.cefrLevel,
                            // The deck's tags win when present; otherwise keep any LLM-enriched value.
                            gender = content.gender ?: existing.gender,
                            // The deck's `la:country` wins; otherwise keep the user's existing country.
                            country = content.country ?: existing.country,
                            // Retired: never re-imported, but preserve any legacy value on the note.
                            region = existing.region,
                            // `inflectedForms` is left null on purpose: the cache was computed from
                            // the old text, and null is what makes the backfill redo it.
                        ),
                    )
                    existingNoteId
                }

                for (imported in incoming) {
                    val card = imported.card
                    // The same Anki card as before, by its template ordinal — or, failing that, the
                    // card already asking in this direction, which is one Copiosa made itself (a note
                    // has one card per direction, so a second would not be storable anyway).
                    val existing = siblings.firstOrNull { it.ankiOrd == card.ankiOrd }
                        ?: siblings.firstOrNull { it.cardDirection == card.cardDirection }
                    val cardId = if (existing == null) {
                        added++
                        flashcardDao.insertCardsFor(noteId, card)
                    } else {
                        updated++
                        // Anki is authoritative for the schedule; the frozen flag is Copiosa's own.
                        flashcardDao.updateCard(
                            card.toCard(noteId = noteId, direction = existing.cardDirection, id = existing.id)
                                .copy(suspended = existing.suspended),
                        )
                        reviewLogDao.deleteForCard(existing.id)
                        existing.id
                    }
                    imported.reviews.forEach { reviewLogDao.insert(it.copy(cardId = cardId)) }
                }
            }
        }
        return added to updated
    }

    /**
     * The one row that speaks for a note's content: its term-first card where it has one, since that
     * is the projection whose translation had its usage note peeled off. A side that card left blank
     * — a template that showed only a picture there — is taken from another card of the note.
     */
    private fun noteContent(cards: List<FlashcardEntity>): FlashcardEntity {
        val lead = cards.firstOrNull { it.frontIsTarget } ?: cards.first()
        val others = cards.filter { it !== lead }
        fun term(c: FlashcardEntity) = if (c.frontIsTarget) c.front else c.back
        fun translation(c: FlashcardEntity) = if (c.frontIsTarget) c.back else c.front
        val term = term(lead).ifBlank { others.map(::term).firstOrNull { it.isNotBlank() } ?: term(lead) }
        val translation = translation(lead)
            .ifBlank { others.map(::translation).firstOrNull { it.isNotBlank() } ?: translation(lead) }
        return lead.copy(
            front = if (lead.frontIsTarget) term else translation,
            back = if (lead.frontIsTarget) translation else term,
            // Anki's "marked" is a tag on the note, so every card of it agrees; `any` is for safety.
            marked = cards.any { it.marked },
            imageFile = lead.imageFile ?: others.firstNotNullOfOrNull { it.imageFile },
            audioFile = lead.audioFile ?: others.firstNotNullOfOrNull { it.audioFile },
        )
    }
}
