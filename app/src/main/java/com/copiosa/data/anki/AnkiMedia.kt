package com.copiosa.data.anki

import android.content.Context
import java.io.File

/** Where imported Anki media lives, and how a note's media filename maps to an on-disk file. */
object AnkiMedia {
    fun dir(context: Context): File = File(context.filesDir, "anki_media")

    fun file(context: Context, name: String): File = File(dir(context), safeName(name))

    /** Anki media names allow spaces/parens (fine on Android) but never path separators. */
    fun safeName(name: String): String = name.replace('/', '_').replace('\\', '_')
}
