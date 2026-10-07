package com.copiosa.data.usage

import android.util.Log
import com.copiosa.data.local.LlmUsageDao
import com.copiosa.data.local.LlmUsageEntity
import com.copiosa.data.local.LlmUsageGroup
import com.copiosa.data.remote.LlmRequest
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.usage.BookSpend
import com.copiosa.domain.usage.TokenUsage
import com.copiosa.domain.usage.UsageSlice
import com.copiosa.domain.usage.UsageSummary
import com.copiosa.domain.usage.UsageTotals
import com.copiosa.domain.usage.UsageWindow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The usage meter's store: providers [record] what each call consumed, and Settings and the book
 * dialog read it back priced.
 *
 * Recording happens inside the provider, on the response itself, rather than at the call sites —
 * that is the only place every call passes through, including the ones whose reply is then thrown
 * away as a refusal or a misaligned section. Those were billed like any other.
 */
@Singleton
class LlmUsageRepository @Inject constructor(
    private val dao: LlmUsageDao,
) {
    /** Injectable for tests; the device's own calendar day everywhere else. */
    internal var today: () -> LocalDate = LocalDate::now

    /**
     * Adds one finished call to today's totals. A null [usage] is a response that carried no usage
     * block: there is nothing to meter, and a row of zeroes would only inflate the call count.
     *
     * Never throws and never cancels. The call has already been paid for by the time this runs, so
     * a caller that has since gone away still owes the entry; and a meter that could fail a chat
     * reply over a write error would cost more than it measures.
     */
    suspend fun record(provider: ProviderId, req: LlmRequest, usage: TokenUsage?) {
        if (usage == null) return
        withContext(NonCancellable) {
            try {
                dao.add(
                    LlmUsageEntity(
                        day = today().toEpochDay(),
                        provider = provider,
                        model = req.model,
                        feature = req.feature.name,
                        bookId = req.bookId ?: NO_BOOK,
                        costReported = usage.costUsd != null,
                        calls = 1,
                        promptTokens = usage.promptTokens,
                        cachedTokens = usage.cachedTokens,
                        outputTokens = usage.outputTokens,
                        costUsd = usage.costUsd ?: 0.0,
                    ),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not record usage for ${req.feature}", e)
            }
        }
    }

    /** Usage inside [window], priced and broken down. Re-emits as calls land. */
    fun observe(window: UsageWindow): Flow<UsageSummary> =
        dao.observeBetween(window.fromDay, window.untilDay).map { groups -> UsageSummary.of(groups.map { it.toSlice() }) }

    /**
     * The first day anything was recorded, or null before the first call. Usage from before it was
     * never written down, so the reader of a period that reaches back past it needs to be told.
     */
    fun observeCountingSince(): Flow<LocalDate?> =
        dao.observeFirstDay().map { day -> day?.let(LocalDate::ofEpochDay) }

    /**
     * What a book has cost — sections, title, retries, re-translations — and since when; null when
     * nothing is on record for it.
     */
    suspend fun forBook(bookId: Long): BookSpend? {
        val since = dao.firstDayForBook(bookId) ?: return null
        return BookSpend(UsageTotals.of(dao.forBook(bookId).map { it.toSlice() }), LocalDate.ofEpochDay(since))
    }

    private fun LlmUsageGroup.toSlice() = UsageSlice(
        provider = provider,
        model = model,
        feature = feature,
        costReported = costReported,
        calls = calls,
        promptTokens = promptTokens,
        cachedTokens = cachedTokens,
        outputTokens = outputTokens,
        costUsd = costUsd,
    )

    private companion object {
        const val TAG = "LlmUsage"

        /** `llm_usage.bookId` for a call made for no book; real ids start at 1. */
        const val NO_BOOK = 0L
    }
}
