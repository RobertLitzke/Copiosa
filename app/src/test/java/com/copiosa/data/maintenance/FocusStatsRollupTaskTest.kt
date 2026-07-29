package com.copiosa.data.maintenance

import com.copiosa.data.local.FocusSessionEntity
import com.copiosa.data.maintenance.tasks.FocusStatsRollupTask
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.stats.FakeFocusStatsDao
import com.copiosa.domain.stats.FocusActivityType
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusStatsRollupTaskTest {

    private val now = 1_700_000_000_000L
    private val today: Long =
        Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()

    private fun session(
        lang: String = "es",
        day: Long,
        type: FocusActivityType = FocusActivityType.READING,
        durationMs: Long = 60_000,
        rolledUp: Boolean = false,
    ) = FocusSessionEntity(
        langTag = lang,
        activityType = type,
        startedAt = 0,
        durationMs = durationMs,
        epochDay = day,
        rolledUp = rolledUp,
    )

    private fun ctx(tag: String = "es") = TaskContext(tag, AppSettings(), now)

    @Test
    fun `completed days roll up, today stays raw`() = runTest {
        val dao = FakeFocusStatsDao()
        dao.insert(session(day = today - 1, durationMs = 60_000))
        dao.insert(session(day = today - 1, durationMs = 30_000))
        dao.insert(session(day = today, durationMs = 45_000))

        FocusStatsRollupTask(dao).run(ctx())

        val rolled = dao.rollups.values.single()
        assertEquals(90_000L, rolled.totalDurationMs)
        assertEquals(2, rolled.sessionCount)
        assertEquals(today - 1, rolled.epochDay)
        // Today's session is untouched and still live.
        val todayRow = dao.sessions.single { it.epochDay == today }
        assertTrue(!todayRow.rolledUp)
    }

    @Test
    fun `a second run does not double-count`() = runTest {
        val dao = FakeFocusStatsDao()
        dao.insert(session(day = today - 1, durationMs = 60_000))
        val task = FocusStatsRollupTask(dao)

        task.run(ctx())
        task.run(ctx())

        assertEquals(60_000L, dao.rollups.values.single().totalDurationMs)
        assertEquals(1, dao.rollups.values.single().sessionCount)
    }

    @Test
    fun `later sessions for an already-rolled day add to its rollup`() = runTest {
        val dao = FakeFocusStatsDao()
        dao.insert(session(day = today - 1, durationMs = 60_000))
        val task = FocusStatsRollupTask(dao)
        task.run(ctx())

        // A session for that day that landed after the first pass (e.g. flushed late).
        dao.insert(session(day = today - 1, durationMs = 30_000))
        task.run(ctx())

        assertEquals(90_000L, dao.rollups.values.single().totalDurationMs)
        assertEquals(2, dao.rollups.values.single().sessionCount)
    }

    @Test
    fun `days and activities roll up separately`() = runTest {
        val dao = FakeFocusStatsDao()
        dao.insert(session(day = today - 2, type = FocusActivityType.READING))
        dao.insert(session(day = today - 1, type = FocusActivityType.READING))
        dao.insert(session(day = today - 1, type = FocusActivityType.QUIZ))

        FocusStatsRollupTask(dao).run(ctx())

        assertEquals(3, dao.rollups.size)
    }

    @Test
    fun `only the context profile is touched`() = runTest {
        val dao = FakeFocusStatsDao()
        dao.insert(session(lang = "es", day = today - 1))
        dao.insert(session(lang = "fr", day = today - 1))

        FocusStatsRollupTask(dao).run(ctx("es"))

        assertEquals("es", dao.rollups.values.single().langTag)
        assertTrue(!dao.sessions.single { it.langTag == "fr" }.rolledUp)
    }

    @Test
    fun `absorbed sessions past retention are pruned, recent ones kept`() = runTest {
        val dao = FakeFocusStatsDao()
        val oldDay = today - FocusStatsRollupTask.RAW_RETENTION_DAYS - 1
        dao.insert(session(day = oldDay))
        dao.insert(session(day = today - 1))

        FocusStatsRollupTask(dao).run(ctx())

        // Both were absorbed, but only the recent raw row is retained.
        assertEquals(2, dao.rollups.size)
        assertEquals(today - 1, dao.sessions.single().epochDay)
    }
}
