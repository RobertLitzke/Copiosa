package com.copiosa.data.anki

/** A row from Anki's `notes` table; [fields] is `flds` already split on the 0x1f separator. */
data class RawNote(
    val id: Long,
    val guid: String,
    val mid: Long,
    val tags: String,
    val fields: List<String>,
)

/** The scheduling-relevant columns of a row in Anki's `cards` table. */
data class RawCard(
    val id: Long,
    val nid: Long,
    val did: Long,
    val ord: Int,
    val type: Int,
    val queue: Int,
    val due: Long,
    val ivl: Long,
    val factor: Int,
    val reps: Int,
    val lapses: Int,
)

/** A review-history row from Anki's `revlog` table. */
data class RawRevlog(
    val id: Long,
    val cid: Long,
    val ease: Int,
    val ivl: Long,
    val lastIvl: Long,
    val factor: Int,
    val time: Int,
    val type: Int,
)

/**
 * Read access to an unpacked Anki collection. Implementations are platform-specific (Android SQLite in
 * production, a JDBC reader in tests) so the mapping in [AnkiImporter] stays pure and JVM-testable.
 */
interface RawApkg {
    /** `col.crt` — collection creation time in epoch SECONDS; review-card due dates are days relative to it. */
    val crt: Long

    /** `col.models` — raw JSON object keyed by note-type id. */
    val modelsJson: String

    /** `col.decks` — raw JSON object keyed by deck id. */
    val decksJson: String

    fun notes(): List<RawNote>
    fun cards(): List<RawCard>
    fun revlog(): List<RawRevlog>

    /** Anki `media` manifest: numbered zip entry name -> original filename. */
    fun mediaMap(): Map<String, String>
}
