package com.copiosa.data.anki

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.copiosa.data.local.FlashcardDao
import com.copiosa.data.local.ReviewLogDao
import com.copiosa.data.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates exporting the vocabulary to an Anki `.apkg`: reads every card + its review history,
 * runs the pure [AnkiExporter], writes the result to a `collection.anki21` SQLite file, copies the
 * referenced media, and zips it all to the user-chosen destination URI.
 *
 * This is the reverse of [AnkiImportService] and preserves what import preserved: each card's schedule
 * (due date, interval, ease, lapses), its full graded history (the `revlog`, including review times and
 * durations), and the structured `la:*` metadata — so the deck round-trips through Anki without losing
 * progress. Imported cards reconstruct their original note losslessly; app-created cards are written
 * under a synthesized note type with a stable guid (`la-<id>`).
 */
@Singleton
class AnkiExportService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val flashcardDao: FlashcardDao,
    private val reviewLogDao: ReviewLogDao,
    private val settingsRepo: SettingsRepository,
) {
    data class Summary(val cards: Int, val notes: Int, val reviews: Int, val mediaFiles: Int)

    /** Thrown when there are no cards to export, so the UI can explain rather than write an empty deck. */
    class NothingToExportException : Exception("You don't have any saved words to export yet.")

    suspend fun export(uri: Uri, now: Long = System.currentTimeMillis()): Summary = withContext(Dispatchers.IO) {
        // The export is the active language profile's deck; reviews self-scope via the card list.
        val cards = flashcardDao.getAll(settingsRepo.activeProfileTag.first())
        if (cards.isEmpty()) throw NothingToExportException()

        val reviewsByCard = reviewLogDao.all().groupBy { it.cardId }
        val inputs = cards.map { AnkiExporter.Input(it, reviewsByCard[it.id].orEmpty()) }
        val collection = AnkiExporter().export(inputs, now)

        val work = File(context.cacheDir, "anki_export_$now").apply { mkdirs() }
        try {
            val collFile = File(work, "collection.anki21")
            SQLiteDatabase.openOrCreateDatabase(collFile, null).use { db ->
                db.beginTransaction()
                try {
                    AnkiCollectionWriter.write(AndroidSqlExec(db), collection)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }

            // Only zip media files that still exist in the store; a missing one just means the card's
            // field still references it (Anki shows a broken-media marker) — better than aborting.
            val mediaPairs = collection.mediaFiles.mapNotNull { name ->
                AnkiMedia.file(context, name).takeIf { it.exists() }?.let { name to it }
            }

            context.contentResolver.openOutputStream(uri)?.use { os ->
                ApkgPackager.pack(os, collFile, mediaPairs)
            } ?: throw IllegalStateException("Could not open the destination file.")

            Summary(
                cards = collection.cards.size,
                notes = collection.notes.size,
                reviews = collection.revlog.size,
                mediaFiles = mediaPairs.size,
            )
        } finally {
            work.deleteRecursively()
        }
    }

    /** Routes the shared schema/insert logic through Android's SQLite. */
    private class AndroidSqlExec(private val db: SQLiteDatabase) : SqlExec {
        override fun exec(sql: String) = db.execSQL(sql)
        override fun insert(sql: String, args: List<Any?>) = db.execSQL(sql, args.toTypedArray())
    }
}
