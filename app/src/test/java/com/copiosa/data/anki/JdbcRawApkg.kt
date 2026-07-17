package com.copiosa.data.anki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.util.zip.ZipFile

/**
 * Test-only [RawApkg] backed by the sqlite-jdbc driver. Unpacks the apkg's collection database to a
 * temp file and reads it with plain JDBC — lets the pure [AnkiImporter] logic run against a real
 * fixture on the JVM without an Android device. The production reader will use Android's SQLite.
 */
class JdbcRawApkg(apkg: File) : RawApkg, AutoCloseable {

    private val tmpDir = File(System.getProperty("java.io.tmpdir"), "apkg_test_${System.nanoTime()}").apply { mkdirs() }
    private val media: Map<String, String>
    private val conn: Connection

    override val crt: Long
    override val modelsJson: String
    override val decksJson: String

    init {
        ZipFile(apkg).use { zip ->
            media = zip.getEntry("media")?.let { e ->
                Json.parseToJsonElement(zip.getInputStream(e).readBytes().decodeToString())
                    .jsonObject.mapValues { it.value.jsonPrimitive.content }
            }.orEmpty()

            val collName = listOf("collection.anki21", "collection.anki2")
                .firstOrNull { zip.getEntry(it) != null }
                ?: error("no collection.anki2/.anki21 entry in $apkg")
            val collFile = File(tmpDir, collName)
            zip.getInputStream(zip.getEntry(collName)).use { input ->
                collFile.outputStream().use { input.copyTo(it) }
            }
            conn = DriverManager.getConnection("jdbc:sqlite:${collFile.absolutePath}")
        }
        conn.createStatement().executeQuery("SELECT crt, models, decks FROM col LIMIT 1").use { rs ->
            rs.next()
            crt = rs.getLong("crt")
            modelsJson = rs.getString("models")
            decksJson = rs.getString("decks")
        }
    }

    override fun notes(): List<RawNote> =
        query("SELECT id, guid, mid, tags, flds FROM notes") { rs ->
            RawNote(
                id = rs.getLong("id"),
                guid = rs.getString("guid"),
                mid = rs.getLong("mid"),
                tags = rs.getString("tags"),
                // Anki joins note fields with the ASCII Unit Separator (0x1F).
                fields = rs.getString("flds").split(0x1F.toChar()),
            )
        }

    override fun cards(): List<RawCard> =
        query("SELECT id, nid, did, ord, type, queue, due, ivl, factor, reps, lapses FROM cards") { rs ->
            RawCard(
                id = rs.getLong("id"), nid = rs.getLong("nid"), did = rs.getLong("did"),
                ord = rs.getInt("ord"), type = rs.getInt("type"), queue = rs.getInt("queue"),
                due = rs.getLong("due"), ivl = rs.getLong("ivl"), factor = rs.getInt("factor"),
                reps = rs.getInt("reps"), lapses = rs.getInt("lapses"),
            )
        }

    override fun revlog(): List<RawRevlog> =
        query("SELECT id, cid, ease, ivl, lastIvl, factor, time, type FROM revlog") { rs ->
            RawRevlog(
                id = rs.getLong("id"), cid = rs.getLong("cid"), ease = rs.getInt("ease"),
                ivl = rs.getLong("ivl"), lastIvl = rs.getLong("lastIvl"), factor = rs.getInt("factor"),
                time = rs.getInt("time"), type = rs.getInt("type"),
            )
        }

    override fun mediaMap(): Map<String, String> = media

    private fun <T> query(sql: String, map: (ResultSet) -> T): List<T> {
        val out = ArrayList<T>()
        conn.createStatement().executeQuery(sql).use { rs -> while (rs.next()) out.add(map(rs)) }
        return out
    }

    override fun close() {
        conn.close()
        tmpDir.deleteRecursively()
    }
}
