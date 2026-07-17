package com.copiosa.data.anki

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal SQL sink so the schema/insert logic in [AnkiCollectionWriter] can target Android's SQLite in
 * production and a JDBC connection in tests, without either side duplicating the DDL or the bindings.
 * Bound arguments are limited to `Long`/`String` (the only types Android's `execSQL` accepts besides
 * `Double`/`ByteArray`/null), so callers pass scheduling integers as `Long`.
 */
interface SqlExec {
    fun exec(sql: String)
    fun insert(sql: String, args: List<Any?>)
}

/** Writes an [ExportCollection] into an Anki schema-11 `collection.anki2x` database via a [SqlExec]. */
object AnkiCollectionWriter {

    fun write(exec: SqlExec, coll: ExportCollection) {
        SCHEMA.forEach(exec::exec)
        exec.insert(
            "INSERT INTO col (id, crt, mod, scm, ver, dty, usn, ls, conf, models, decks, dconf, tags) " +
                "VALUES (1, ?, ?, ?, 11, 0, 0, 0, ?, ?, ?, ?, ?)",
            listOf(coll.crt, coll.mod, coll.scm, coll.confJson, coll.modelsJson, coll.decksJson, coll.dconfJson, coll.tagsJson),
        )
        for (n in coll.notes) {
            exec.insert(
                "INSERT INTO notes (id, guid, mid, mod, usn, tags, flds, sfld, csum, flags, data) " +
                    "VALUES (?, ?, ?, ?, -1, ?, ?, ?, ?, 0, '')",
                listOf(n.id, n.guid, n.mid, n.mod, n.tags, n.flds, n.sfld, n.csum),
            )
        }
        for (c in coll.cards) {
            exec.insert(
                "INSERT INTO cards (id, nid, did, ord, mod, usn, type, queue, due, ivl, factor, reps, " +
                    "lapses, left, odue, odid, flags, data) " +
                    "VALUES (?, ?, ?, ?, ?, -1, ?, ?, ?, ?, ?, ?, ?, 0, 0, 0, 0, '')",
                listOf(
                    c.id, c.nid, c.did, c.ord.toLong(), c.mod, c.type.toLong(), c.queue.toLong(), c.due,
                    c.ivl, c.factor.toLong(), c.reps.toLong(), c.lapses.toLong(),
                ),
            )
        }
        for (r in coll.revlog) {
            exec.insert(
                "INSERT INTO revlog (id, cid, usn, ease, ivl, lastIvl, factor, time, type) " +
                    "VALUES (?, ?, -1, ?, ?, ?, ?, ?, ?)",
                listOf(r.id, r.cid, r.ease.toLong(), r.ivl, r.lastIvl, r.factor.toLong(), r.time.toLong(), r.type.toLong()),
            )
        }
    }

    // Anki's schema-11 collection tables + the indexes Anki itself creates, so a real Anki install
    // reads the deck happily. Our own importer only needs the columns it SELECTs, but the rest keep
    // the file a valid Anki collection.
    private val SCHEMA = listOf(
        "CREATE TABLE col (id integer primary key, crt integer not null, mod integer not null, " +
            "scm integer not null, ver integer not null, dty integer not null, usn integer not null, " +
            "ls integer not null, conf text not null, models text not null, decks text not null, " +
            "dconf text not null, tags text not null)",
        "CREATE TABLE notes (id integer primary key, guid text not null, mid integer not null, " +
            "mod integer not null, usn integer not null, tags text not null, flds text not null, " +
            "sfld integer not null, csum integer not null, flags integer not null, data text not null)",
        "CREATE TABLE cards (id integer primary key, nid integer not null, did integer not null, " +
            "ord integer not null, mod integer not null, usn integer not null, type integer not null, " +
            "queue integer not null, due integer not null, ivl integer not null, factor integer not null, " +
            "reps integer not null, lapses integer not null, left integer not null, odue integer not null, " +
            "odid integer not null, flags integer not null, data text not null)",
        "CREATE TABLE revlog (id integer primary key, cid integer not null, usn integer not null, " +
            "ease integer not null, ivl integer not null, lastIvl integer not null, factor integer not null, " +
            "time integer not null, type integer not null)",
        "CREATE TABLE graves (usn integer not null, oid integer not null, type integer not null)",
        "CREATE INDEX ix_notes_usn on notes (usn)",
        "CREATE INDEX ix_cards_usn on cards (usn)",
        "CREATE INDEX ix_revlog_usn on revlog (usn)",
        "CREATE INDEX ix_cards_nid on cards (nid)",
        "CREATE INDEX ix_cards_sched on cards (did, queue, due)",
        "CREATE INDEX ix_revlog_cid on revlog (cid)",
        "CREATE INDEX ix_notes_csum on notes (csum)",
    )
}

/** Zips a written collection + its media into the `.apkg` layout Anki and [AnkiImportService] expect. */
object ApkgPackager {
    /**
     * Writes a `.apkg` to [out]: the collection DB as `collection.anki21`, each media file as a
     * numbered entry, and a `media` manifest mapping those numbers back to the original filenames.
     */
    fun pack(out: OutputStream, collectionFile: File, media: List<Pair<String, File>>) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("collection.anki21"))
            collectionFile.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()

            media.forEachIndexed { i, (_, file) ->
                zip.putNextEntry(ZipEntry(i.toString()))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }

            val manifest: JsonObject = buildJsonObject {
                media.forEachIndexed { i, (name, _) -> put(i.toString(), name) }
            }
            zip.putNextEntry(ZipEntry("media"))
            zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
