package com.copiosa.data.local

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Copies the database files aside before a migration that restructures data rather than adding to it.
 *
 * A Room migration runs in a transaction, so one that *fails* leaves the old database intact. What a
 * transaction cannot protect against is a migration that succeeds at doing the wrong thing — a merge
 * rule that picks the wrong side — and by the time that is noticed the old rows are gone. The copy is
 * the way back from that, taken once, before Room opens the file.
 *
 * The schema version is read straight from the file header instead of by opening the database: an
 * open-and-close on a WAL database can checkpoint it, and this must not change the thing it is about
 * to copy.
 */
object PreMigrationSnapshot {

    /**
     * If the database [name] exists at a schema older than [beforeVersion], copies it (and its WAL
     * sidecars) into `databases/pre-v<beforeVersion>/`. Does nothing once that folder exists, so a
     * later launch never overwrites the snapshot with already-migrated data. Never throws: a failed
     * copy is logged and the app starts regardless.
     */
    fun takeIfOlderThan(context: Context, name: String, beforeVersion: Int) {
        try {
            val db = context.getDatabasePath(name)
            if (!db.exists()) return
            val version = userVersion(db) ?: return
            // 0 is a file Room has not created its schema in yet: nothing to preserve.
            if (version == 0 || version >= beforeVersion) return
            val dir = File(db.parentFile, "pre-v$beforeVersion")
            if (dir.exists()) return
            if (!dir.mkdirs()) return
            for (suffix in listOf("", "-wal", "-shm")) {
                val source = File(db.path + suffix)
                if (source.exists()) source.copyTo(File(dir, source.name), overwrite = true)
            }
            Log.i(TAG, "Saved the v$version database to ${dir.name} before migrating")
        } catch (e: Exception) {
            Log.w(TAG, "Could not snapshot the database before migrating", e)
        }
    }

    /** SQLite's `user_version`: a big-endian 32-bit integer at byte 60 of the file header. */
    private fun userVersion(db: File): Int? =
        RandomAccessFile(db, "r").use { file ->
            if (file.length() < HEADER_BYTES) return null
            file.seek(USER_VERSION_OFFSET)
            file.readInt()
        }

    private const val USER_VERSION_OFFSET = 60L
    private const val HEADER_BYTES = 100L
    private const val TAG = "PreMigrationSnapshot"
}
