package com.copiosa.data.anki

import android.database.sqlite.SQLiteDatabase

/**
 * Production [RawApkg] that reads an unpacked Anki collection through Android's SQLite. The mapping
 * logic in [AnkiImporter] is exercised on the JVM via the JDBC-backed reader in tests; this class is
 * the thin device-side equivalent of that I/O.
 */
class AndroidRawApkg(
    private val db: SQLiteDatabase,
    private val media: Map<String, String>,
) : RawApkg {

    override val crt: Long
    override val modelsJson: String
    override val decksJson: String

    init {
        db.rawQuery("SELECT crt, models, decks FROM col LIMIT 1", null).use { c ->
            c.moveToFirst()
            crt = c.getLong(0)
            modelsJson = c.getString(1)
            decksJson = c.getString(2)
        }
    }

    override fun notes(): List<RawNote> =
        db.rawQuery("SELECT id, guid, mid, tags, flds FROM notes", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        RawNote(
                            id = c.getLong(0),
                            guid = c.getString(1),
                            mid = c.getLong(2),
                            tags = c.getString(3),
                            // Anki joins note fields with the ASCII Unit Separator (0x1F).
                            fields = c.getString(4).split(0x1F.toChar()),
                        ),
                    )
                }
            }
        }

    override fun cards(): List<RawCard> =
        db.rawQuery("SELECT id, nid, did, ord, type, queue, due, ivl, factor, reps, lapses FROM cards", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        RawCard(
                            id = c.getLong(0), nid = c.getLong(1), did = c.getLong(2), ord = c.getInt(3),
                            type = c.getInt(4), queue = c.getInt(5), due = c.getLong(6), ivl = c.getLong(7),
                            factor = c.getInt(8), reps = c.getInt(9), lapses = c.getInt(10),
                        ),
                    )
                }
            }
        }

    override fun revlog(): List<RawRevlog> =
        db.rawQuery("SELECT id, cid, ease, ivl, lastIvl, factor, time, type FROM revlog", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        RawRevlog(
                            id = c.getLong(0), cid = c.getLong(1), ease = c.getInt(2), ivl = c.getLong(3),
                            lastIvl = c.getLong(4), factor = c.getInt(5), time = c.getInt(6), type = c.getInt(7),
                        ),
                    )
                }
            }
        }

    override fun mediaMap(): Map<String, String> = media
}
