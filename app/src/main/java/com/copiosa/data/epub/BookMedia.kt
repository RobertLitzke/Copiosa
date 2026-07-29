package com.copiosa.data.epub

import android.content.Context
import java.io.File

/**
 * Where imported book covers live. Filenames only (never paths), keyed by book id, so backups
 * restore cleanly — the AnkiMedia model.
 */
object BookMedia {
    const val DIR_NAME = "book_covers"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun coverFileName(bookId: Long): String = "cover_$bookId.jpg"

    fun file(context: Context, name: String): File = File(dir(context), name)
}
