package com.copiosa.data.translate

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A dictionary headword: its definitions and (when the lookup was an inflected form) its lemma. */
data class DictionaryEntry(
    val lemma: String,
    val pos: String?,
    val definitions: List<String>,
    /** Non-null when the selected token was a conjugation/inflection, e.g. "plural of casa". */
    val inflectionOf: String?,
)

sealed interface DictionaryResult {
    data class Found(val entries: List<DictionaryEntry>) : DictionaryResult
    /** The pack is present but has no entry for the word. */
    data object NotFound : DictionaryResult
    /** The language pack hasn't been downloaded yet (or the download failed). */
    data object Unavailable : DictionaryResult
}

/**
 * On-device monolingual dictionary backed by a prebuilt SQLite pack (generated from Wiktionary; see
 * tools/build_dictionary.py). The pack is downloaded once per language from [DICTIONARY_BASE_URL]
 * and then queried entirely offline. Definitions are in the target language; inflected forms
 * (conjugations, plurals…) resolve to their lemma's entry.
 *
 * Schema (see tools/build_dictionary.py):
 *   lemmas(id, key, lemma, pos)   — one row per headword+pos
 *   senses(lemma_id, seq, gloss)  — one row per sense, in order
 *   forms(key, lemma_id, tags)    — inflected form -> lemma
 */
@Singleton
class LocalDictionary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val http: OkHttpClient,
) {
    private val downloadLock = Mutex()
    private val openDbs = mutableMapOf<String, SQLiteDatabase>()

    /** False while [DICTIONARY_BASE_URL] is the unset placeholder, so the UI hides definitions. */
    fun isConfigured(): Boolean = !DICTIONARY_BASE_URL.contains(URL_PLACEHOLDER)

    fun isDownloaded(lang: String): Boolean = dbFile(lang).exists()

    /** Downloads the language pack if absent. Returns true if a usable pack is present afterwards. */
    suspend fun ensureDownloaded(lang: String): Boolean = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext false
        if (dbFile(lang).exists()) return@withContext true
        downloadLock.withLock {
            val target = dbFile(lang)
            if (target.exists()) true else downloadAndUnpack(lang, target)
        }
    }

    suspend fun lookup(word: String, lang: String): DictionaryResult = withContext(Dispatchers.IO) {
        val db = database(lang) ?: return@withContext DictionaryResult.Unavailable
        val key = normalize(word)
        if (key.isBlank()) return@withContext DictionaryResult.NotFound
        val entries = queryEntries(db, key)
        if (entries.isEmpty()) DictionaryResult.NotFound else DictionaryResult.Found(entries)
    }

    /**
     * For each word in [words], its lemma plus every inflected form the pack records — the reverse of
     * [lookup]'s form→lemma resolution, so a saved headword like "vivir" can be matched against the
     * "vivió"/"vivimos" that show up in text. The result is keyed by the *original* word the caller
     * passed (so the caller needn't reproduce [normalize]); each maps to a set of normalized forms that
     * always contains at least the word's own normalized form. Returns an empty map when the pack for
     * [lang] isn't downloaded, letting callers fall back to plain exact matching.
     *
     * The `forms` table is indexed on `key` but not `lemma_id`, so the final gather is a table scan;
     * we keep it to one scan per chunk (not one per word) by resolving all lemma ids first. This runs
     * only when the saved-card set changes, off the main thread.
     */
    suspend fun inflectedFormsFor(words: Collection<String>, lang: String): Map<String, Set<String>> =
        withContext(Dispatchers.IO) {
            val db = database(lang) ?: return@withContext emptyMap()
            // Original word -> normalized key; distinct keys each seeded with themselves.
            val wordKeys = words.associateWith { normalize(it) }.filterValues { it.isNotEmpty() }
            if (wordKeys.isEmpty()) return@withContext emptyMap()
            val keyForms = HashMap<String, MutableSet<String>>()
            for (key in wordKeys.values.toSet()) keyForms[key] = mutableSetOf(key)

            // Resolve each key to its lemma id(s): a key can be a headword and/or an inflected form.
            val lemmaIdToKeys = HashMap<Long, MutableSet<String>>()
            for (key in keyForms.keys) {
                db.rawQuery("SELECT id FROM lemmas WHERE key = ?", arrayOf(key)).use { c ->
                    while (c.moveToNext()) lemmaIdToKeys.getOrPut(c.getLong(0)) { mutableSetOf() }.add(key)
                }
                db.rawQuery("SELECT lemma_id FROM forms WHERE key = ?", arrayOf(key)).use { c ->
                    while (c.moveToNext()) lemmaIdToKeys.getOrPut(c.getLong(0)) { mutableSetOf() }.add(key)
                }
            }
            if (lemmaIdToKeys.isNotEmpty()) {
                // The lemma's own headword, then every inflected form of those lemmas.
                forEachIn(db, "SELECT id, key FROM lemmas WHERE id", lemmaIdToKeys.keys) { c ->
                    lemmaIdToKeys[c.getLong(0)]?.forEach { keyForms[it]?.add(c.getString(1)) }
                }
                forEachIn(db, "SELECT lemma_id, key FROM forms WHERE lemma_id", lemmaIdToKeys.keys) { c ->
                    lemmaIdToKeys[c.getLong(0)]?.forEach { keyForms[it]?.add(c.getString(1)) }
                }
            }
            wordKeys.mapValues { (_, key) -> keyForms[key]?.toSet() ?: setOf(key) }
        }

    /** Runs [body] over the rows of "[sqlPrefix] IN (…)" for [ids], chunked under SQLite's param limit. */
    private inline fun forEachIn(db: SQLiteDatabase, sqlPrefix: String, ids: Collection<Long>, body: (Cursor) -> Unit) {
        ids.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            db.rawQuery("$sqlPrefix IN ($placeholders)", chunk.map(Long::toString).toTypedArray()).use { c ->
                while (c.moveToNext()) body(c)
            }
        }
    }

    private fun queryEntries(db: SQLiteDatabase, key: String): List<DictionaryEntry> {
        val entries = mutableListOf<DictionaryEntry>()
        val seen = mutableSetOf<Long>()
        // Direct headword matches (one lemma row per part of speech).
        db.rawQuery("SELECT id, lemma, pos FROM lemmas WHERE key = ?", arrayOf(key)).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue
                entries += DictionaryEntry(c.getString(1), c.stringOrNull(2), sensesOf(db, id), null)
            }
        }
        // Inflected-form matches → resolve to the lemma's entry and note the inflection.
        db.rawQuery("SELECT lemma_id, tags FROM forms WHERE key = ?", arrayOf(key)).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue
                val lemma = lemmaById(db, id) ?: continue
                val defs = sensesOf(db, id)
                if (defs.isNotEmpty()) {
                    entries += DictionaryEntry(lemma.first, lemma.second, defs, inflectionNote(c.stringOrNull(1), lemma.first))
                }
            }
        }
        return entries
    }

    /** Glosses for a lemma id, in sense order. */
    private fun sensesOf(db: SQLiteDatabase, lemmaId: Long): List<String> {
        val out = mutableListOf<String>()
        db.rawQuery("SELECT gloss FROM senses WHERE lemma_id = ? ORDER BY seq", arrayOf(lemmaId.toString())).use { c ->
            while (c.moveToNext()) c.stringOrNull(0)?.let { out += it }
        }
        return out
    }

    /** (display lemma, part of speech) for a lemma id, or null if absent. */
    private fun lemmaById(db: SQLiteDatabase, lemmaId: Long): Pair<String, String?>? =
        db.rawQuery("SELECT lemma, pos FROM lemmas WHERE id = ?", arrayOf(lemmaId.toString())).use { c ->
            if (c.moveToNext()) c.getString(0) to c.stringOrNull(1) else null
        }

    private fun database(lang: String): SQLiteDatabase? {
        openDbs[lang]?.let { return it }
        val f = dbFile(lang)
        if (!f.exists()) return null
        return try {
            SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY).also { openDbs[lang] = it }
        } catch (e: Exception) {
            f.delete() // corrupt/partial download; force a fresh fetch next time
            null
        }
    }

    private fun downloadAndUnpack(lang: String, target: File): Boolean = try {
        val request = Request.Builder().url(DICTIONARY_BASE_URL + remoteName(lang)).build()
        http.newCall(request).execute().use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) {
                false
            } else {
                val tmp = File(target.path + ".tmp")
                GZIPInputStream(body.byteStream()).use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                tmp.renameTo(target)
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun dbFile(lang: String): File = File(context.filesDir, "dict_$lang.sqlite")

    private fun remoteName(lang: String): String = "dict_$lang.sqlite.gz"

    private companion object {
        const val URL_PLACEHOLDER = "<owner>"
        const val DICTIONARY_BASE_URL = "https://github.com/RobertLitzke/LangAid/releases/download/dict-v1/"

        fun normalize(word: String): String =
            word.trim()
                .trim('¿', '?', '¡', '!', '.', ',', ';', ':', '"', '\'', '«', '»', '(', ')', '—', '–')
                .lowercase()

        fun inflectionNote(tags: String?, lemma: String): String =
            if (tags.isNullOrBlank()) "form of $lemma" else "$tags of $lemma"
    }
}

private fun Cursor.stringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
