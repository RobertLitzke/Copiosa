package com.copiosa.data.usage

import com.copiosa.data.local.LlmUsageDao
import com.copiosa.data.local.LlmUsageEntity
import com.copiosa.data.local.LlmUsageGroup
import com.copiosa.data.remote.LlmRequest
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.usage.LlmFeature
import com.copiosa.domain.usage.TokenUsage
import com.copiosa.domain.usage.UsageWindow
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class LlmUsageRepositoryTest {

    /**
     * The table as the real DAO keeps it: one running row per key. `add` is the interface's own
     * default method, so the update-then-insert logic under test here is the code that ships.
     */
    private class FakeDao : LlmUsageDao {
        val rows = mutableListOf<LlmUsageEntity>()
        var failWith: Exception? = null

        private fun LlmUsageEntity.key() = listOf(day, provider, model, feature, bookId, costReported)

        override suspend fun addTo(
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
        ): Int {
            failWith?.let { throw it }
            val i = rows.indexOfFirst { it.key() == listOf(day, provider, model, feature, bookId, costReported) }
            if (i < 0) return 0
            val row = rows[i]
            rows[i] = row.copy(
                calls = row.calls + calls,
                promptTokens = row.promptTokens + promptTokens,
                cachedTokens = row.cachedTokens + cachedTokens,
                outputTokens = row.outputTokens + outputTokens,
                costUsd = row.costUsd + costUsd,
            )
            return 1
        }

        override suspend fun insert(row: LlmUsageEntity): Long {
            rows += row
            return rows.size.toLong()
        }

        private fun grouped(rows: List<LlmUsageEntity>) = rows
            .groupBy { listOf(it.provider, it.model, it.feature, it.costReported) }
            .values.map { group ->
                val first = group.first()
                LlmUsageGroup(
                    provider = first.provider,
                    model = first.model,
                    feature = first.feature,
                    costReported = first.costReported,
                    calls = group.sumOf { it.calls },
                    promptTokens = group.sumOf { it.promptTokens },
                    cachedTokens = group.sumOf { it.cachedTokens },
                    outputTokens = group.sumOf { it.outputTokens },
                    costUsd = group.sumOf { it.costUsd },
                )
            }

        override fun observeBetween(fromDay: Long, untilDay: Long): Flow<List<LlmUsageGroup>> =
            flowOf(grouped(rows.filter { it.day >= fromDay && it.day < untilDay }))

        override suspend fun forBook(bookId: Long): List<LlmUsageGroup> = grouped(rows.filter { it.bookId == bookId })

        override suspend fun firstDayForBook(bookId: Long): Long? = rows.filter { it.bookId == bookId }.minOfOrNull { it.day }

        override fun observeFirstDay(): Flow<Long?> = flowOf(rows.minOfOrNull { it.day })
    }

    private val dao = FakeDao()
    private var day = LocalDate.of(2026, 10, 5)
    private val repo = LlmUsageRepository(dao).also { it.today = { day } }

    private fun request(feature: LlmFeature, model: String = "gemini-2.5-flash", bookId: Long? = null) =
        LlmRequest(systemPrompt = "", messages = emptyList(), model = model, feature = feature, bookId = bookId)

    @Test
    fun `calls with the same key share one running row for the day`() = runBlocking {
        repeat(3) { repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), TokenUsage(100, 40, 20)) }

        val row = dao.rows.single()
        assertEquals(3, row.calls)
        assertEquals(300, row.promptTokens)
        assertEquals(120, row.cachedTokens)
        assertEquals(60, row.outputTokens)
        assertEquals(day.toEpochDay(), row.day)
        assertEquals("CHAT", row.feature)
        assertFalse(row.costReported)
    }

    @Test
    fun `a different feature, model, book or day starts its own row`() = runBlocking {
        val usage = TokenUsage(10, 0, 5)
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), usage)
        repo.record(ProviderId.GEMINI, request(LlmFeature.ROUTINE), usage)
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT, model = "gemini-2.5-pro"), usage)
        repo.record(ProviderId.GEMINI, request(LlmFeature.BOOK, bookId = 7), usage)
        repo.record(ProviderId.GEMINI, request(LlmFeature.BOOK, bookId = 8), usage)
        day = day.plusDays(1)
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), usage)

        assertEquals(6, dao.rows.size)
        assertTrue(dao.rows.all { it.calls == 1 })
    }

    @Test
    fun `a billed call is kept apart from an unbilled one and carries its charge`() = runBlocking {
        val req = request(LlmFeature.CHAT, model = "x/y")
        repo.record(ProviderId.OPENROUTER, req, TokenUsage(10, 0, 5, costUsd = 0.002))
        repo.record(ProviderId.OPENROUTER, req, TokenUsage(10, 0, 5, costUsd = 0.003))
        // The same model answering without a cost must not be averaged into the billed row.
        repo.record(ProviderId.OPENROUTER, req, TokenUsage(10, 0, 5, costUsd = null))

        val billed = dao.rows.single { it.costReported }
        assertEquals(2, billed.calls)
        assertEquals(0.005, billed.costUsd, 1e-9)
        assertEquals(1, dao.rows.single { !it.costReported }.calls)
        // A reported cost of exactly zero (a free model) is still a report.
        repo.record(ProviderId.OPENROUTER, req, TokenUsage(10, 0, 5, costUsd = 0.0))
        assertEquals(3, dao.rows.single { it.costReported }.calls)
    }

    @Test
    fun `a response with no usage block records nothing`() = runBlocking {
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), null)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a failing store never reaches the caller`() = runBlocking {
        dao.failWith = IllegalStateException("disk full")
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), TokenUsage(10, 0, 5))
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `a call whose caller has gone is still counted`() = runBlocking {
        // The reply arrived and was paid for; the coroutine that asked is cancelled before it can
        // be written down.
        val job = Job()
        launch(job) {
            job.cancel()
            repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), TokenUsage(10, 0, 5))
        }.join()
        assertEquals(1, dao.rows.single().calls)
    }

    @Test
    fun `a month reads only its own days`() = runBlocking {
        day = LocalDate.of(2026, 9, 30)
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), TokenUsage(1000, 0, 0))
        day = LocalDate.of(2026, 10, 1)
        repo.record(ProviderId.GEMINI, request(LlmFeature.ROUTINE), TokenUsage(2000, 0, 0))
        day = LocalDate.of(2026, 10, 31)
        repo.record(ProviderId.GEMINI, request(LlmFeature.ROUTINE), TokenUsage(3000, 0, 0))
        day = LocalDate.of(2026, 11, 1)
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), TokenUsage(4000, 0, 0))

        val october = repo.observe(UsageWindow.month(YearMonth.of(2026, 10))).first()
        assertEquals(5000, october.total.tokens)
        assertEquals(2, october.total.calls)
        assertEquals(listOf(LlmFeature.ROUTINE), october.byFeature.map { it.feature })
    }

    @Test
    fun `a book totals every call made for it, across days and models, and says since when`() = runBlocking {
        // An unrelated call first, so the book's date is its own and not the meter's.
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), TokenUsage(9000, 0, 9000))
        day = day.plusDays(3)
        val firstBookDay = day
        repo.record(ProviderId.GEMINI, request(LlmFeature.BOOK, bookId = 7), TokenUsage(1000, 0, 500))
        day = day.plusMonths(2)
        repo.record(ProviderId.OPENROUTER, request(LlmFeature.BOOK, model = "x/y", bookId = 7), TokenUsage(100, 0, 50, costUsd = 0.5))
        repo.record(ProviderId.GEMINI, request(LlmFeature.BOOK, bookId = 8), TokenUsage(9000, 0, 9000))

        val spent = repo.forBook(7)!!
        assertEquals(2, spent.totals.calls)
        assertEquals(1650, spent.totals.tokens)
        assertTrue(spent.totals.costUsd > 0.5)
        assertTrue(spent.totals.estimated)
        assertEquals(firstBookDay, spent.since)
        assertNull(repo.forBook(99))
    }

    @Test
    fun `the meter knows the day it started counting`() = runBlocking {
        assertNull(repo.observeCountingSince().first())
        val started = day
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), TokenUsage(10, 0, 5))
        day = day.plusDays(40)
        repo.record(ProviderId.GEMINI, request(LlmFeature.CHAT), TokenUsage(10, 0, 5))
        assertEquals(started, repo.observeCountingSince().first())
    }
}
