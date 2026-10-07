package com.copiosa.data

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs an export whose destination is [uri], a file the system picker has already created. When
 * [write] ends without having put anything in it — cancelled before its first byte, or failed on
 * the way there ("nothing to export") — the empty file is removed rather than left behind in the
 * learner's Downloads as if it were the export.
 *
 * Only an empty file is ever touched: one with bytes in it is either a finished export or something
 * that was already there, and neither is this function's to delete.
 */
internal suspend fun <T> ContentResolver.exportTo(uri: Uri, write: suspend () -> T): T =
    try {
        write()
    } catch (e: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) { discardIfEmpty(uri) }
        throw e
    }

/** Best effort: a provider that can't report a size, or can't delete, simply keeps its file. */
private fun ContentResolver.discardIfEmpty(uri: Uri) {
    runCatching {
        val size = query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        if (size == 0L) DocumentsContract.deleteDocument(this, uri)
    }
}
