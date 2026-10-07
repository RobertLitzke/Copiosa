package com.copiosa.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.copiosa.domain.model.ProviderId
import kotlinx.coroutines.flow.Flow

/** `llm_usage` rows summed over some period, grouped the way the meter breaks usage down. */
data class LlmUsageGroup(
    val provider: ProviderId,
    val model: String,
    val feature: String,
    val costReported: Boolean,
    val calls: Int,
    val promptTokens: Long,
    val cachedTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
)

@Dao
interface LlmUsageDao {

    @Query(
        "UPDATE llm_usage SET calls = calls + :calls, promptTokens = promptTokens + :promptTokens, " +
            "cachedTokens = cachedTokens + :cachedTokens, outputTokens = outputTokens + :outputTokens, " +
            "costUsd = costUsd + :costUsd " +
            "WHERE day = :day AND provider = :provider AND model = :model AND feature = :feature " +
            "AND bookId = :bookId AND costReported = :costReported",
    )
    suspend fun addTo(
        day: Long,
        provider: ProviderId,
        model: String,
        feature: String,
        bookId: Long,
        costReported: Boolean,
        calls: Int,
        promptTokens: Long,
        cachedTokens: Long,
        outputTokens: Long,
        costUsd: Double,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: LlmUsageEntity): Long

    /**
     * Folds [row] into the day's running total for its key, creating it on the day's first call.
     * One transaction, so two calls finishing together cannot both find no row and lose one.
     */
    @Transaction
    suspend fun add(row: LlmUsageEntity) {
        val updated = addTo(
            day = row.day,
            provider = row.provider,
            model = row.model,
            feature = row.feature,
            bookId = row.bookId,
            costReported = row.costReported,
            calls = row.calls,
            promptTokens = row.promptTokens,
            cachedTokens = row.cachedTokens,
            outputTokens = row.outputTokens,
            costUsd = row.costUsd,
        )
        if (updated == 0) insert(row)
    }

    /** Usage on days in `[fromDay, untilDay)`, across every book and none. */
    @Query(
        "SELECT provider, model, feature, costReported, SUM(calls) AS calls, " +
            "SUM(promptTokens) AS promptTokens, SUM(cachedTokens) AS cachedTokens, " +
            "SUM(outputTokens) AS outputTokens, SUM(costUsd) AS costUsd " +
            "FROM llm_usage WHERE day >= :fromDay AND day < :untilDay " +
            "GROUP BY provider, model, feature, costReported",
    )
    fun observeBetween(fromDay: Long, untilDay: Long): Flow<List<LlmUsageGroup>>

    /** Everything ever spent on one book — all time, since a novel is translated over months. */
    @Query(
        "SELECT provider, model, feature, costReported, SUM(calls) AS calls, " +
            "SUM(promptTokens) AS promptTokens, SUM(cachedTokens) AS cachedTokens, " +
            "SUM(outputTokens) AS outputTokens, SUM(costUsd) AS costUsd " +
            "FROM llm_usage WHERE bookId = :bookId " +
            "GROUP BY provider, model, feature, costReported",
    )
    suspend fun forBook(bookId: Long): List<LlmUsageGroup>

    /** The first day [bookId] has anything on record, or null if it has nothing. */
    @Query("SELECT MIN(day) FROM llm_usage WHERE bookId = :bookId")
    suspend fun firstDayForBook(bookId: Long): Long?

    /**
     * The first day anything at all was recorded — when the meter started counting. Earlier calls
     * were never written down, so a period reaching back past this is only partly measured.
     */
    @Query("SELECT MIN(day) FROM llm_usage")
    fun observeFirstDay(): Flow<Long?>
}
