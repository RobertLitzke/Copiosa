package com.copiosa.data.epub

import android.content.Context
import java.io.File

/**
 * Where an imported book's images live: one cover per book in a flat directory, and a subdirectory
 * of body images per book. Filenames only (never paths), keyed by book id, so backups restore
 * cleanly — the AnkiMedia model.
 *
 * Body images are namespaced by a directory rather than by a prefix in the filename because the
 * name is also what the stored `![](2.jpg)` markdown says. Putting the book id in there would mean
 * rewriting every image row's text after the insert assigns one, and would break the moment a row
 * were ever copied to another book.
 */
object BookMedia {
    const val DIR_NAME = "book_covers"
    const val IMAGES_DIR_NAME = "book_images"

    fun dir(context: Context): File = File(context.filesDir, DIR_NAME)

    fun coverFileName(bookId: Long): String = "cover_$bookId.jpg"

    fun file(context: Context, name: String): File = File(dir(context), name)

    /** This book's body images. One directory per book, so deleting the book deletes them all. */
    fun imagesDir(context: Context, bookId: Long): File =
        File(File(context.filesDir, IMAGES_DIR_NAME), bookId.toString())

    /** The file an `![](name)` block refers to. Null-safe against a missing image is the caller's. */
    fun imageFile(context: Context, bookId: Long, name: String): File =
        File(imagesDir(context, bookId), name)
}
