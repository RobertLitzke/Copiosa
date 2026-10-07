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
import java.io.IOException
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton

/** One meaning of a headword, in the language being learned. */
data class Sense(
    val gloss: String,
    /** Register/dialect/domain markers — "colloquial", "Argentina", "nautical" — or null. */
    val labels: String?,
    /** A short example sentence from the dictionary, or null (most senses have none). */
    val example: String?,
)

/** A dictionary headword: its definitions and (when the lookup was an inflected form) its lemma. */
data class DictionaryEntry(
    val lemma: String,
    val pos: String?,
    /** A noun's gender code (M/F/MF/MPL/FPL), as stored on a flashcard; null for anything else. */
    val gender: String? = null,
    val definitions: List<Sense>,
    /**
     * English meanings of this same headword and part of speech, from the English-edition source.
     * Sense numbering isn't comparable between the two editions, so these are the meanings of the
     * *headword* rather than a translation of each [definitions] entry one for one. Empty for a
     * headword the English edition doesn't carry, and always empty on a schema-2 pack.
     */
    val englishDefinitions: List<String>,
    /** Non-null when the selected token was a conjugation/inflection, e.g. "plural of casa". */
    val inflectionOf: String?,
)

/**
 * What an attempt to fetch a language pack did.
 *
 * A plain Boolean was enough while the only question was "can I look words up now", but it collapses
 * every way this can go wrong into one silent "no" — and the card's only move then is to re-draw the
 * download button, which reads as a button that does nothing. The distinctions that matter to a
 * reader are whether waiting will help ([Offline], [Corrupt]) and whether it won't ([Unavailable],
 * [NotConfigured]).
 */
sealed interface DictionaryDownload {
    /** A usable pack is on disk. */
    data object Ready : DictionaryDownload

    /** The request never completed — no route, DNS, TLS, timeout. Says nothing about the pack. */
    data object Offline : DictionaryDownload

    /**
     * The server answered, but not with a pack. [code] is its status: a 404 means this language has
     * no pack published at the tag this build asks for, which retrying will never change.
     */
    data class Unavailable(val code: Int) : DictionaryDownload

    /** It arrived but could not be unpacked or opened — a truncated transfer, or the wrong schema. */
    data object Corrupt : DictionaryDownload

    /** This build has no download URL configured. */
    data object NotConfigured : DictionaryDownload
}

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
 * Schema 4 (see tools/build_dictionary.py):
 *   lemmas(id, key, lemma, pos, gender)            — one row per headword+pos; gender on nouns
 *   senses(lemma_id, seq, gloss, labels, example)  — one row per sense, in order
 *   en_senses(lemma_id, seq, gloss)                — English meanings of the same headword
 *   forms(key, lemma_id, tags)                     — inflected form -> lemma
 *
 * A pack built before [REQUIRED_SCHEMA] is treated as absent, so the card offers the download
 * button rather than silently showing an entry with no English side. See [database].
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

    /**
     * True when a *usable* pack is on disk. A file left by an older app version carries an older
     * schema and no English side, so it reads as not-downloaded and the card offers the upgrade
     * through the same download button a first-time reader sees.
     */
    suspend fun isDownloaded(lang: String): Boolean =
        withContext(Dispatchers.IO) { database(lang) != null }

    /**
     * Roughly how large the download is, in MB, or null for a language whose pack hasn't been
     * measured. Hardcoded rather than fetched: the button has to say something before any request is
     * made, and "~36 MB" is worth a stale constant when the alternative is a HEAD round-trip on a
     * screen the learner is reading. Update when a pack is rebuilt.
     */
    fun approxSizeMb(lang: String): Int? = PACK_SIZE_MB[lang.substringBefore('-').lowercase()]

    /** Downloads the language pack if absent or outdated, reporting what happened. */
    suspend fun ensureDownloaded(lang: String): DictionaryDownload = withContext(Dispatchers.IO) {
        if (!isConfigured()) return@withContext DictionaryDownload.NotConfigured
        if (database(lang) != null) return@withContext DictionaryDownload.Ready
        downloadLock.withLock {
            if (database(lang) != null) return@withLock DictionaryDownload.Ready
            // An outdated pack is still sitting there; drop it (and its open handle) before refetching.
            discard(lang)
            val outcome = downloadAndUnpack(lang, dbFile(lang))
            if (outcome != DictionaryDownload.Ready) return@withLock outcome
            // Unpacked, but a pack this build cannot read is no better than none: it would report
            // success here and then answer every lookup with Unavailable, which is the same silent
            // dead end by a longer route.
            if (database(lang) != null) DictionaryDownload.Ready else DictionaryDownload.Corrupt
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
     * Each word maps to the `lemmas.pos` values it may expand through, or null for "unknown, allow
     * any". The filter matters because a key can be a *homograph*: Spanish "son" is both a noun (the
     * music genre) and the third-person plural of "ser", so an unfiltered expansion of the noun hops
     * through the `forms` table into "ser" and drags back soy/eres/es/… Rows with a NULL pos are
     * always allowed — the pack, not the card, is the uncertain party there.
     *
     * The `forms` table is indexed on `key` but not `lemma_id`, so the final gather is a table scan;
     * we keep it to one scan per chunk (not one per word) by resolving all lemma ids first. This runs
     * only when the saved-card set changes, off the main thread.
     */
    suspend fun inflectedFormsFor(words: Map<String, Set<String>?>, lang: String): Map<String, Set<String>> =
        withContext(Dispatchers.IO) {
            val db = database(lang) ?: return@withContext emptyMap()
            // Original word -> normalized key; distinct keys each seeded with themselves.
            val wordKeys = words.keys.associateWith { normalize(it) }.filterValues { it.isNotEmpty() }
            if (wordKeys.isEmpty()) return@withContext emptyMap()
            val keyForms = HashMap<String, MutableSet<String>>()
            for (key in wordKeys.values.toSet()) keyForms[key] = mutableSetOf(key)
            // Two cards can share a key with different parts of speech: union their allowances, and
            // let a single unrestricted word make the key unrestricted.
            val keyPos = HashMap<String, Set<String>?>()
            val unrestricted = HashSet<String>()
            for ((word, key) in wordKeys) {
                val pos = words[word]
                if (pos == null) {
                    unrestricted += key
                    keyPos[key] = null
                } else if (key !in unrestricted) {
                    keyPos[key] = keyPos[key].orEmpty() + pos
                }
            }

            // Resolve each key to its lemma id(s): a key can be a headword and/or an inflected form.
            val lemmaIdToKeys = HashMap<Long, MutableSet<String>>()
            for (key in keyForms.keys) {
                val pos = keyPos[key]?.takeIf { it.isNotEmpty() }
                val posArgs = pos?.toTypedArray() ?: emptyArray()
                // With no filter the queries stay exactly the plain indexed lookups they were.
                val posClause = { alias: String ->
                    pos?.let { " AND ($alias.pos IS NULL OR $alias.pos IN (${it.joinToString(",") { "?" }}))" }
                        .orEmpty()
                }
                db.rawQuery("SELECT id FROM lemmas WHERE key = ?" + posClause("lemmas"), arrayOf(key) + posArgs)
                    .use { c ->
                        while (c.moveToNext()) lemmaIdToKeys.getOrPut(c.getLong(0)) { mutableSetOf() }.add(key)
                    }
                val formSql = if (pos == null) {
                    "SELECT lemma_id FROM forms WHERE key = ?"
                } else {
                    "SELECT f.lemma_id FROM forms f JOIN lemmas l ON l.id = f.lemma_id WHERE f.key = ?" +
                        posClause("l")
                }
                db.rawQuery(formSql, arrayOf(key) + posArgs).use { c ->
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

    /**
     * The gender code for [word] as a noun, or null when the pack has none or the word isn't clearly
     * one — what a card is saved with so it gets its article (and a place in the gender swipe-sort)
     * without waiting on the enrichment call.
     *
     * [partOfSpeech] is the card's own. A card that says "noun" takes the noun row's gender even when
     * the spelling is also a verb form (*casa*, *cuenta*); one that doesn't say takes it only when
     * every row for the key is a noun, because a wrong article chip is worse than a missing one.
     */
    suspend fun nounGender(word: String, lang: String, partOfSpeech: String?): String? =
        withContext(Dispatchers.IO) {
            val db = database(lang) ?: return@withContext null
            val key = normalize(word)
            if (key.isBlank()) return@withContext null
            val rows = mutableListOf<Pair<String?, String?>>()
            db.rawQuery("SELECT pos, gender FROM lemmas WHERE key = ?", arrayOf(key)).use { c ->
                while (c.moveToNext()) rows += c.stringOrNull(0) to c.stringOrNull(1)
            }
            val gender = rows.firstOrNull { it.first == NOUN_POS }?.second ?: return@withContext null
            when (partOfSpeech?.trim()?.lowercase()?.ifBlank { null }) {
                NOUN_POS -> gender
                null, UNKNOWN_POS -> gender.takeIf { rows.all { it.first == NOUN_POS } }
                else -> null
            }
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
        db.rawQuery("SELECT id, lemma, pos, gender FROM lemmas WHERE key = ?", arrayOf(key)).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (!seen.add(id)) continue
                entries += DictionaryEntry(
                    lemma = c.getString(1),
                    pos = c.stringOrNull(2),
                    gender = c.stringOrNull(3),
                    definitions = sensesOf(db, id),
                    englishDefinitions = englishSensesOf(db, id),
                    inflectionOf = null,
                )
            }
        }
        // Inflected-form matches → resolve to the lemma's entry and note the inflection. The pack
        // carries several rows per form: a bare "form-of" beside the one that actually names the
        // inflection, in no fixed order. Keep the informative one, or "maceran" reports itself as a
        // "form-of of macerar" instead of the third-person plural present it is.
        val tagsByLemma = LinkedHashMap<Long, String?>()
        db.rawQuery("SELECT lemma_id, tags FROM forms WHERE key = ?", arrayOf(key)).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val tags = c.stringOrNull(1)
                if (id !in tagsByLemma || (tagsByLemma[id].isGenericTag() && !tags.isGenericTag())) {
                    tagsByLemma[id] = tags
                }
            }
        }
        for ((id, tags) in tagsByLemma) {
            if (!seen.add(id)) continue
            val lemma = lemmaById(db, id) ?: continue
            val defs = sensesOf(db, id)
            if (defs.isNotEmpty()) {
                entries += DictionaryEntry(
                    lemma = lemma.lemma,
                    pos = lemma.pos,
                    gender = lemma.gender,
                    definitions = defs,
                    englishDefinitions = englishSensesOf(db, id),
                    inflectionOf = inflectionNote(tags, lemma.lemma),
                )
            }
        }
        return entries
    }

    /** Target-language senses for a lemma id, in sense order. */
    private fun sensesOf(db: SQLiteDatabase, lemmaId: Long): List<Sense> {
        val out = mutableListOf<Sense>()
        db.rawQuery(
            "SELECT gloss, labels, example FROM senses WHERE lemma_id = ? ORDER BY seq",
            arrayOf(lemmaId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                val gloss = c.stringOrNull(0) ?: continue
                out += Sense(gloss, c.stringOrNull(1), c.stringOrNull(2))
            }
        }
        return out
    }

    /** English meanings for a lemma id, in sense order. */
    private fun englishSensesOf(db: SQLiteDatabase, lemmaId: Long): List<String> {
        val out = mutableListOf<String>()
        db.rawQuery("SELECT gloss FROM en_senses WHERE lemma_id = ? ORDER BY seq", arrayOf(lemmaId.toString())).use { c ->
            while (c.moveToNext()) c.stringOrNull(0)?.let { out += it }
        }
        return out
    }

    private class LemmaRow(val lemma: String, val pos: String?, val gender: String?)

    /** The display lemma, part of speech and gender for a lemma id, or null if absent. */
    private fun lemmaById(db: SQLiteDatabase, lemmaId: Long): LemmaRow? =
        db.rawQuery("SELECT lemma, pos, gender FROM lemmas WHERE id = ?", arrayOf(lemmaId.toString())).use { c ->
            if (c.moveToNext()) LemmaRow(c.getString(0), c.stringOrNull(1), c.stringOrNull(2)) else null
        }

    /**
     * The open pack for [lang], opening it on first use, or null when there isn't a usable one.
     *
     * Synchronized because it both reads and writes [openDbs], and is reached from several readers
     * at once — a lookup, the highlight rebuild, and the card's own is-it-downloaded check all land
     * here on [Dispatchers.IO]. Contended only on the first call per language; every later one
     * returns the cached handle.
     */
    private fun database(lang: String): SQLiteDatabase? = synchronized(openDbs) {
        openDbs[lang]?.let { return it }
        val f = dbFile(lang)
        if (!f.exists()) return null
        try {
            val db = SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READONLY)
            // Left in place rather than deleted: the reader may be offline, and a stale pack that
            // can be re-fetched later is better than one thrown away at the moment it's read.
            if (schemaOf(db) < REQUIRED_SCHEMA) {
                db.close()
                null
            } else {
                db.also { openDbs[lang] = it }
            }
        } catch (e: Exception) {
            f.delete() // corrupt/partial download; force a fresh fetch next time
            null
        }
    }

    /** The pack's schema version, or 0 when it predates the meta row or can't be read. */
    private fun schemaOf(db: SQLiteDatabase): Int = try {
        db.rawQuery("SELECT value FROM meta WHERE key = 'schema'", null).use { c ->
            if (c.moveToNext()) c.getString(0)?.toIntOrNull() ?: 0 else 0
        }
    } catch (e: Exception) {
        0
    }

    /** Closes and deletes the pack for [lang], so the next [ensureDownloaded] fetches a fresh one. */
    private fun discard(lang: String) {
        synchronized(openDbs) { openDbs.remove(lang) }?.close()
        dbFile(lang).delete()
    }

    private fun downloadAndUnpack(lang: String, target: File): DictionaryDownload {
        val request = Request.Builder().url(DICTIONARY_BASE_URL + remoteName(lang)).build()
        val call = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            // OkHttp funnels every transport failure through IOException, and none of them say
            // anything about the pack itself — they are all "the same request may work later".
            return DictionaryDownload.Offline
        }
        return call.use { resp ->
            val body = resp.body
            if (!resp.isSuccessful || body == null) return DictionaryDownload.Unavailable(resp.code)
            val tmp = File(target.path + ".tmp")
            try {
                GZIPInputStream(body.byteStream()).use { input ->
                    tmp.outputStream().use { out -> input.copyTo(out) }
                }
                if (tmp.renameTo(target)) DictionaryDownload.Ready else DictionaryDownload.Corrupt
            } catch (e: Exception) {
                // A transfer cut short arrives as a gzip error rather than an HTTP one. Drop the
                // partial file here instead of leaving it for [database] to open and delete: a
                // half-unpacked pack of this size is not worth carrying around until then.
                tmp.delete()
                DictionaryDownload.Corrupt
            }
        }
    }

    private fun dbFile(lang: String): File = File(context.filesDir, "dict_$lang.sqlite")

    private fun remoteName(lang: String): String = "dict_$lang.sqlite.gz"

    private companion object {
        /**
         * Lowest pack schema this build can read. Schema 3 added the English glosses and the
         * per-sense labels and examples; schema 4 added `lemmas.gender`. Anything older is missing
         * a table or column [queryEntries] reads.
         */
        const val REQUIRED_SCHEMA = 4

        /** The pack's part of speech for a noun — also the card editor's value for one. */
        const val NOUN_POS = "noun"

        /** What an app-built card's part of speech says before anything has filled it in. */
        const val UNKNOWN_POS = "unknown"

        /** Compressed download size of each published pack, from `dist/dict_<lang>.sqlite.gz`. */
        val PACK_SIZE_MB = mapOf("es" to 120, "pt" to 55)

        const val URL_PLACEHOLDER = "<owner>"
        // Tagged per schema, not per rebuild: a shipped build asks for the packs it can read, so an
        // older one keeps resolving dict-v1 instead of fetching a schema it has no tables for.
        // The repo's current name. `LangAid` resolved here only through GitHub's rename redirect,
        // which is not a guarantee: it lapses the moment anything else claims that name.
        const val DICTIONARY_BASE_URL = "https://github.com/RobertLitzke/Copiosa/releases/download/dict-v3/"

        fun normalize(word: String): String =
            word.trim()
                .trim('¿', '?', '¡', '!', '.', ',', ';', ':', '"', '\'', '«', '»', '(', ')', '—', '–')
                .lowercase()

        /** The pack's placeholder tag, which says nothing the note can use. */
        const val GENERIC_FORM_TAG = "form-of"

        fun String?.isGenericTag(): Boolean = isNullOrBlank() || this == GENERIC_FORM_TAG

        fun inflectionNote(tags: String?, lemma: String): String =
            if (tags.isGenericTag()) "form of $lemma" else "$tags of $lemma"
    }
}

private fun Cursor.stringOrNull(index: Int): String? =
    if (isNull(index)) null else getString(index)
