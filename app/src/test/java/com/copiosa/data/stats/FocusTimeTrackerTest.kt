package com.copiosa.data.stats

import com.copiosa.data.stats.FocusTimeTracker.Channel
import com.copiosa.domain.stats.FocusActivityType
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FocusTimeTrackerTest {

    private val utc: ZoneId = ZoneId.of("UTC")

    /** 2024-01-01 00:00 UTC — a clean day boundary to measure from. */
    private val dayStartMs: Long =
        LocalDate.of(2024, 1, 1).atStartOfDay(utc).toInstant().toEpochMilli()
    private val day: Long = LocalDate.of(2024, 1, 1).toEpochDay()

    private class Harness(scope: TestScope, startAt: Long) {
        val dao = FakeFocusStatsDao()
        var nowMs = startAt
        val tracker = FocusTimeTracker(dao).apply {
            now = { nowMs }
            zone = { ZoneId.of("UTC") }
            this.scope = scope
        }

        /** Moves the wall clock and the virtual dispatcher forward together. */
        fun TestScope.advance(ms: Long) {
            nowMs += ms
            testScheduler.advanceTimeBy(ms)
            testScheduler.runCurrent()
        }
    }

    private fun harness(scope: TestScope, startAt: Long) = Harness(scope, startAt)

    @Test
    fun `sub-threshold session is discarded`() = runTest {
        val h = harness(this, dayStartMs)
        with(h) {
            tracker.start(Channel.FOREGROUND, FocusActivityType.READING, "es")
            runCurrent()
            advance(2_000)
            tracker.stop(Channel.FOREGROUND)
            advance(0)
            assertTrue(dao.sessions.isEmpty())
        }
    }

    @Test
    fun `heartbeat inserts then grows one row, stop finalizes it`() = runTest {
        val h = harness(this, dayStartMs)
        with(h) {
            tracker.start(Channel.FOREGROUND, FocusActivityType.READING, "es")
            runCurrent()
            advance(30_000)
            assertEquals(1, dao.sessions.size)
            assertEquals(30_000L, dao.sessions[0].durationMs)
            assertEquals(day, dao.sessions[0].epochDay)

            advance(30_000)
            assertEquals(1, dao.sessions.size)
            assertEquals(60_000L, dao.sessions[0].durationMs)

            advance(10_000)
            tracker.stop(Channel.FOREGROUND)
            advance(0)
            assertEquals(70_000L, dao.sessions[0].durationMs)
        }
    }

    @Test
    fun `short session that already flushed is still finalized`() = runTest {
        val h = harness(this, dayStartMs)
        with(h) {
            tracker.flushIntervalMs = 1_000
            tracker.start(Channel.FOREGROUND, FocusActivityType.REVIEW, "es")
            runCurrent()
            advance(1_000)
            assertEquals(1, dao.sessions.size)
            advance(1_000)
            tracker.stop(Channel.FOREGROUND)
            advance(0)
            assertEquals(2_000L, dao.sessions[0].durationMs)
        }
    }

    @Test
    fun `channels track independently`() = runTest {
        val h = harness(this, dayStartMs)
        with(h) {
            tracker.start(Channel.FOREGROUND, FocusActivityType.READING, "es")
            tracker.start(Channel.PLAYBACK, FocusActivityType.LISTENING, "fr")
            runCurrent()
            advance(10_000)
            tracker.stop(Channel.FOREGROUND)
            advance(0) // settle the stop before moving the clock further
            advance(20_000)
            tracker.stop(Channel.PLAYBACK)
            advance(0)

            val reading = dao.sessions.single { it.activityType == FocusActivityType.READING }
            val listening = dao.sessions.single { it.activityType == FocusActivityType.LISTENING }
            assertEquals(10_000L, reading.durationMs)
            assertEquals("es", reading.langTag)
            assertEquals(30_000L, listening.durationMs)
            assertEquals("fr", listening.langTag)
        }
    }

    @Test
    fun `restarting a channel closes the previous session`() = runTest {
        val h = harness(this, dayStartMs)
        with(h) {
            tracker.start(Channel.FOREGROUND, FocusActivityType.READING, "es")
            runCurrent()
            advance(10_000)
            tracker.start(Channel.FOREGROUND, FocusActivityType.QUIZ, "es")
            advance(0)
            advance(10_000)
            tracker.stop(Channel.FOREGROUND)
            advance(0)

            assertEquals(
                listOf(FocusActivityType.READING to 10_000L, FocusActivityType.QUIZ to 10_000L),
                dao.sessions.map { it.activityType to it.durationMs },
            )
        }
    }

    @Test
    fun `stop with a stale owner is ignored`() = runTest {
        val h = harness(this, dayStartMs)
        with(h) {
            val oldOwner = Any()
            val newOwner = Any()
            tracker.start(Channel.FOREGROUND, FocusActivityType.READING, "es", newOwner)
            runCurrent()
            advance(10_000)
            tracker.stop(Channel.FOREGROUND, oldOwner)
            advance(10_000)
            tracker.stop(Channel.FOREGROUND, newOwner)
            advance(0)

            assertEquals(20_000L, dao.sessions.single().durationMs)
        }
    }

    @Test
    fun `a flush across midnight splits the session at the boundary`() = runTest {
        val h = harness(this, dayStartMs - 10_000)
        with(h) {
            tracker.start(Channel.FOREGROUND, FocusActivityType.READING, "es")
            runCurrent()
            advance(30_000)

            assertEquals(2, dao.sessions.size)
            val (before, after) = dao.sessions
            assertEquals(10_000L, before.durationMs)
            assertEquals(day - 1, before.epochDay)
            assertEquals(20_000L, after.durationMs)
            assertEquals(day, after.epochDay)
            assertEquals(dayStartMs, after.startedAt)

            tracker.stop(Channel.FOREGROUND)
            advance(0)
        }
    }

    @Test
    fun `a session is capped at the maximum length`() = runTest {
        val h = harness(this, dayStartMs)
        with(h) {
            tracker.flushIntervalMs = 5 * 60 * 60 * 1_000L
            tracker.start(Channel.FOREGROUND, FocusActivityType.READING, "es")
            runCurrent()
            advance(5 * 60 * 60 * 1_000L)

            assertEquals(FocusTimeTracker.MAX_SESSION_MS, dao.sessions.single().durationMs)

            // The capped session is closed; more time accrues nothing.
            advance(5 * 60 * 60 * 1_000L)
            assertEquals(1, dao.sessions.size)
        }
    }
}
