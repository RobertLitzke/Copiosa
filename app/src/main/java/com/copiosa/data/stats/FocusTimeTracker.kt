package com.copiosa.data.stats

import com.copiosa.data.local.FocusSessionEntity
import com.copiosa.data.local.FocusStatsDao
import com.copiosa.domain.stats.FocusActivityType
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Accumulates focused-study time into `focus_sessions`.
 *
 * Two independent channels, because playlist listening continues in the background and can
 * legitimately overlap a foreground activity: [Channel.FOREGROUND] is driven by screen lifecycle
 * (one open session at a time), [Channel.PLAYBACK] by the audio controller's play/pause
 * transitions. The accepted consequence is that listening-while-reading records both, so summed
 * minutes can exceed wall-clock — "minutes" means focused-activity minutes, not elapsed time.
 *
 * Persistence is heartbeat-based: a session row is inserted on the first flush and its duration
 * grown every [flushIntervalMs] after, so process death loses at most one interval. On [stop],
 * sessions shorter than [MIN_SESSION_MS] that never reached a flush are discarded. A flush that
 * crosses local midnight finalizes the row at the boundary and opens a fresh one, so a row never
 * spans days (and the rollup task never races an open yesterday-session). Any session is
 * force-stopped at [MAX_SESSION_MS] as an idle guard for left-open screens or a stuck player.
 */
@Singleton
class FocusTimeTracker @Inject constructor(
    private val dao: FocusStatsDao,
) {
    // Seams for tests; Hilt always uses the primary constructor defaults.
    internal var now: () -> Long = System::currentTimeMillis
    internal var zone: () -> ZoneId = ZoneId::systemDefault
    internal var flushIntervalMs: Long = FLUSH_INTERVAL_MS
    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    enum class Channel { FOREGROUND, PLAYBACK }

    private class OpenSession(
        val type: FocusActivityType,
        val langTag: String,
        var startedAt: Long,
        val owner: Any?,
        var rowId: Long? = null,
    )

    private val mutex = Mutex()
    private val open = mutableMapOf<Channel, OpenSession>()
    private var heartbeat: Job? = null

    /**
     * Opens a session on [channel], closing any it replaces. [owner] scopes a later [stop]: during
     * a navigation transition the outgoing screen's dispose can run after the incoming screen's
     * start, and the owner check keeps that stale stop from killing the new session.
     */
    fun start(channel: Channel, type: FocusActivityType, langTag: String, owner: Any? = null) {
        scope.launch {
            mutex.withLock {
                closeLocked(channel)
                open[channel] = OpenSession(type, langTag, now(), owner)
                if (heartbeat?.isActive != true) {
                    heartbeat = scope.launch { heartbeatLoop() }
                }
            }
        }
    }

    /** Closes [channel]'s session. A non-null [owner] only closes the session it opened. */
    fun stop(channel: Channel, owner: Any? = null) {
        scope.launch {
            mutex.withLock {
                val session = open[channel] ?: return@withLock
                if (owner != null && session.owner !== owner) return@withLock
                closeLocked(channel)
                if (open.isEmpty()) {
                    heartbeat?.cancel()
                    heartbeat = null
                }
            }
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(flushIntervalMs)
            mutex.withLock {
                if (open.isEmpty()) return
                for (channel in open.keys.toList()) flushLocked(channel)
            }
        }
    }

    /** Persists [channel]'s elapsed time, splitting at midnight and enforcing the idle cap. */
    private suspend fun flushLocked(channel: Channel) {
        val session = open[channel] ?: return
        val nowMs = now()
        val dayEndMs = dayEndMillis(session.startedAt)
        if (nowMs >= dayEndMs) {
            // Finalize at the boundary; the remainder continues as a fresh row in the new day.
            persistLocked(session, dayEndMs - session.startedAt)
            open[channel] = OpenSession(session.type, session.langTag, dayEndMs, session.owner)
            flushLocked(channel)
            return
        }
        val elapsed = nowMs - session.startedAt
        if (elapsed >= MAX_SESSION_MS) {
            persistLocked(session, MAX_SESSION_MS)
            open.remove(channel)
            return
        }
        persistLocked(session, elapsed)
    }

    private suspend fun closeLocked(channel: Channel) {
        val session = open.remove(channel) ?: return
        val nowMs = now().coerceAtMost(dayEndMillis(session.startedAt))
        val elapsed = (nowMs - session.startedAt).coerceIn(0, MAX_SESSION_MS)
        if (elapsed < MIN_SESSION_MS && session.rowId == null) return
        persistLocked(session, elapsed)
    }

    private suspend fun persistLocked(session: OpenSession, durationMs: Long) {
        val id = session.rowId
        if (id == null) {
            session.rowId = dao.insert(
                FocusSessionEntity(
                    langTag = session.langTag,
                    activityType = session.type,
                    startedAt = session.startedAt,
                    durationMs = durationMs,
                    epochDay = epochDay(session.startedAt),
                ),
            )
        } else {
            dao.setDuration(id, durationMs)
        }
    }

    private fun epochDay(atMs: Long): Long =
        Instant.ofEpochMilli(atMs).atZone(zone()).toLocalDate().toEpochDay()

    /** First instant of the day after the one containing [atMs], in the device zone. */
    private fun dayEndMillis(atMs: Long): Long =
        Instant.ofEpochMilli(atMs).atZone(zone()).toLocalDate()
            .plusDays(1).atStartOfDay(zone()).toInstant().toEpochMilli()

    companion object {
        const val MIN_SESSION_MS = 5_000L
        const val FLUSH_INTERVAL_MS = 30_000L
        const val MAX_SESSION_MS = 4 * 60 * 60 * 1_000L
    }
}
