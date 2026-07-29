package com.copiosa.data.backup

import android.content.Context
import android.net.Uri
import com.copiosa.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveInputStream
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Full app backup/restore as a single zip: the Room database, the media folders whose files the DB
 * references by name (playlist audio, Anki media, quiz clips), and the DataStore preference files
 * (settings + maintenance bookkeeping).
 *
 * Deliberately excluded: the TTS voice model and dictionary packs (both re-downloadable on demand)
 * and API keys (EncryptedSharedPreferences is bound to this device's keystore and would not decrypt
 * elsewhere anyway — re-enter the key after restoring to a new device).
 */
@Singleton
class BackupService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: AppDatabase,
) {

    /** Streams a complete backup zip to [uri] (a SAF "create document" target). */
    suspend fun exportTo(uri: Uri): Unit = withContext(Dispatchers.IO) {
        // Fold the write-ahead log into the main file so the single copied .db is complete.
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val out = context.contentResolver.openOutputStream(uri) ?: error("Cannot open backup target")
        ZipArchiveOutputStream(out.buffered()).use { zip ->
            addFile(zip, context.getDatabasePath(DB_NAME), DB_NAME)
            for (dir in BACKED_UP_DIRS) {
                val root = File(context.filesDir, dir)
                if (!root.isDirectory) continue
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    addFile(zip, f, "files/$dir/${f.relativeTo(root).invariantSeparatorsPath}")
                }
            }
        }
    }

    /**
     * Replaces the app's data with the backup at [uri]. Everything is staged to a temp dir first, so
     * a torn download or bad zip fails before anything live is touched. The caller MUST restart the
     * process immediately after this returns — Room and DataStore both hold now-stale state.
     */
    suspend fun restoreFrom(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val staging = File(context.cacheDir, "restore_staging")
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val input = context.contentResolver.openInputStream(uri) ?: error("Cannot open backup")
            var sawDb = false
            ZipArchiveInputStream(input.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val name = entry.name
                    if (!entry.isDirectory && isBackupEntry(name)) {
                        if (name == DB_NAME) sawDb = true
                        val target = File(staging, name)
                        // Zip-slip guard: every entry must land inside the staging dir.
                        require(target.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                            "Illegal entry path: $name"
                        }
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zip.copyTo(it) }
                    }
                    entry = zip.nextEntry
                }
            }
            require(sawDb) { "Not a Copiosa backup: no $DB_NAME in the archive" }

            // Point of no return: swap the live data for the staged copies.
            db.close()
            val dbFile = context.getDatabasePath(DB_NAME)
            File(dbFile.parentFile, "$DB_NAME-wal").delete()
            File(dbFile.parentFile, "$DB_NAME-shm").delete()
            File(staging, DB_NAME).copyTo(dbFile, overwrite = true)
            for (dir in BACKED_UP_DIRS) {
                val restored = File(staging, "files/$dir")
                if (!restored.isDirectory) continue // dir absent from the backup: leave live files be
                val live = File(context.filesDir, dir)
                live.deleteRecursively()
                restored.copyRecursively(live, overwrite = true)
            }
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun addFile(zip: ZipArchiveOutputStream, file: File, entryName: String) {
        if (!file.isFile) return
        zip.putArchiveEntry(ZipArchiveEntry(entryName))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeArchiveEntry()
    }

    private fun isBackupEntry(name: String): Boolean =
        name == DB_NAME || BACKED_UP_DIRS.any { name.startsWith("files/$it/") }

    private companion object {
        const val DB_NAME = "copiosa.db"

        /** files/ subdirectories included in a backup; "datastore" holds the DataStore prefs. */
        val BACKED_UP_DIRS = listOf("audio", "anki_media", "quiz_audio", "book_covers", "datastore")
    }
}
