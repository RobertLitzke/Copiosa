package com.copiosa.data.anki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import androidx.room.withTransaction
import com.copiosa.data.local.AppDatabase
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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

    private suspend fun persist(result: ImportResult): Pair<Int, Int> {
        var added = 0
        var updated = 0
        db.withTransaction {
            for (imported in result.cards) {
                val guid = imported.card.ankiNoteGuid
                val ord = imported.card.ankiOrd
                val existing = if (guid != null && ord != null) flashcardDao.findByAnkiKey(guid, ord) else null
                val cardId = if (existing == null) {
                    added++
                    flashcardDao.insert(imported.card)
                } else {
                    updated++
                    // Anki is authoritative for schedule + content; preserve Copiosa-only enrichment
                    // and this row's identity so the card updates in place rather than duplicating.
                    flashcardDao.update(
                        imported.card.copy(
                            id = existing.id,
                            createdAt = existing.createdAt,
                            sourceConversationId = existing.sourceConversationId,
                            explanation = existing.explanation,
                            // Keep a user-typed usage note; otherwise adopt the freshly-split one.
                            usageNote = existing.usageNote ?: imported.card.usageNote,
                            partOfSpeech = existing.partOfSpeech,
                            cefrLevel = existing.cefrLevel,
                            // The deck's tags win when present; otherwise keep any LLM-enriched value.
                            gender = imported.card.gender ?: existing.gender,
                            // The deck's `la:country` wins; otherwise keep the user's existing country.
                            country = imported.card.country ?: existing.country,
                            // Retired: never re-imported, but preserve any legacy value on the existing row.
                            region = existing.region,
                        ),
                    )
                    reviewLogDao.deleteForCard(existing.id)
                    existing.id
                }
                imported.reviews.forEach { reviewLogDao.insert(it.copy(cardId = cardId)) }
            }
        }
        return added to updated
    }
}
