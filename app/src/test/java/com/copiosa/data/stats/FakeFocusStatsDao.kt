package com.copiosa.data.stats

import com.copiosa.data.local.ActivityTotal
import com.copiosa.data.local.DayTotal
import com.copiosa.data.local.FocusDailyStatEntity
import com.copiosa.data.local.FocusSessionEntity
import com.copiosa.data.local.FocusStatsDao
import com.copiosa.domain.stats.FocusActivityType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** In-memory [FocusStatsDao] mirroring the real queries' union semantics. */
class FakeFocusStatsDao : FocusStatsDao {
    val sessions = mutableListOf<FocusSessionEntity>()
    val rollups = mutableMapOf<Triple<String, Long, FocusActivityType>, FocusDailyStatEntity>()
    private var nextId = 1L

    override suspend fun insert(session: FocusSessionEntity): Long {
        val id = nextId++
        sessions += session.copy(id = id)
        return id
    }

    override suspend fun setDuration(id: Long, durationMs: Long) {
        val i = sessions.indexOfFirst { it.id == id }
        if (i >= 0) sessions[i] = sessions[i].copy(durationMs = durationMs)
    }

    override suspend fun delete(id: Long) {
        sessions.removeAll { it.id == id }
    }

    override fun observeDayTotals(lang: String, fromDay: Long): Flow<List<DayTotal>> =
        flow { emit(dayTotals(fromDay) { it == lang }) }

    override fun observeActiveDays(lang: String, fromDay: Long): Flow<List<Long>> =
        flow { emit(dayTotals(fromDay) { it == lang }.map { it.epochDay }.sortedDescending()) }

    override fun observeDayTotalsScoped(lang: String?): Flow<List<DayTotal>> =
        flow { emit(dayTotals(Long.MIN_VALUE) { lang == null || it == lang }) }

    override fun observeActivityTotals(lang: String?): Flow<List<ActivityTotal>> = flow {
        val perType = mutableMapOf<FocusActivityType, Long>()
        rollups.values.filter { lang == null || it.langTag == lang }
            .forEach { perType.merge(it.activityType, it.totalDurationMs, Long::plus) }
        sessions.filter { (lang == null || it.langTag == lang) && !it.rolledUp }
            .forEach { perType.merge(it.activityType, it.durationMs, Long::plus) }
        emit(perType.entries.map { ActivityTotal(it.key, it.value) })
    }

    override suspend fun clearSessions(lang: String?) {
        sessions.removeAll { lang == null || it.langTag == lang }
    }

    override suspend fun clearDailyStats(lang: String?) {
        rollups.keys.filter { lang == null || it.first == lang }.forEach { rollups.remove(it) }
    }

    private fun dayTotals(fromDay: Long, langMatches: (String) -> Boolean): List<DayTotal> {
        val perDay = mutableMapOf<Long, Long>()
        rollups.values.filter { langMatches(it.langTag) && it.epochDay >= fromDay }
            .forEach { perDay.merge(it.epochDay, it.totalDurationMs, Long::plus) }
        sessions.filter { langMatches(it.langTag) && !it.rolledUp && it.epochDay >= fromDay }
            .forEach { perDay.merge(it.epochDay, it.durationMs, Long::plus) }
        return perDay.entries.sortedBy { it.key }.map { DayTotal(it.key, it.value) }
    }

    override suspend fun unrolledBefore(lang: String, beforeDay: Long): List<FocusSessionEntity> =
        sessions.filter { it.langTag == lang && !it.rolledUp && it.epochDay < beforeDay }

    override suspend fun rollupRow(lang: String, day: Long, type: FocusActivityType): FocusDailyStatEntity? =
        rollups[Triple(lang, day, type)]

    override suspend fun upsertRollup(row: FocusDailyStatEntity) {
        rollups[Triple(row.langTag, row.epochDay, row.activityType)] = row
    }

    override suspend fun markRolledUp(ids: List<Long>) {
        sessions.replaceAll { if (it.id in ids) it.copy(rolledUp = true) else it }
    }

    override suspend fun pruneRolledBefore(beforeDay: Long) {
        sessions.removeAll { it.rolledUp && it.epochDay < beforeDay }
    }
}
