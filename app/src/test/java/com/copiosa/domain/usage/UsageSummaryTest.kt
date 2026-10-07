package com.copiosa.domain.usage

import com.copiosa.data.settings.AppSettings
import com.copiosa.domain.model.ProviderId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

class UsageSummaryTest {

    private fun slice(
        provider: ProviderId = ProviderId.GEMINI,
        model: String = "gemini-2.5-flash",
        feature: String = LlmFeature.CHAT.name,
        costReported: Boolean = false,
        calls: Int = 1,
        prompt: Long = 0,
        cached: Long = 0,
        output: Long = 0,
        cost: Double = 0.0,
    ) = UsageSlice(provider, model, feature, costReported, calls, prompt, cached, output, cost)

    // --- pricing ---

    @Test
    fun `every model the gemini picker offers has a price`() {
        for (model in AppSettings.GEMINI_MODELS) {
            assertNotNull("no price for $model", LlmPricing.priceFor(ProviderId.GEMINI, model))
        }
    }

    @Test
    fun `the longest matching id wins, and a dated suffix is priced as its model`() {
        val flash = LlmPricing.priceFor(ProviderId.GEMINI, "gemini-2.5-flash")!!
        val lite = LlmPricing.priceFor(ProviderId.GEMINI, "gemini-2.5-flash-lite")!!
        assertTrue(lite.outputPerM < flash.outputPerM)
        assertEquals(lite, LlmPricing.priceFor(ProviderId.GEMINI, "gemini-2.5-flash-lite-preview-09-2025"))
        assertEquals(flash, LlmPricing.priceFor(ProviderId.GEMINI, "models/Gemini-2.5-Flash"))
        // A different model that merely shares leading characters is not the same model.
        assertNull(LlmPricing.priceFor(ProviderId.GEMINI, "gemini-2.5-flashy"))
        assertNull(LlmPricing.priceFor(ProviderId.GEMINI, "gemini-9-ultra"))
    }

    @Test
    fun `openrouter is never estimated`() {
        assertNull(LlmPricing.priceFor(ProviderId.OPENROUTER, "google/gemini-2.5-flash"))
    }

    @Test
    fun `cached prompt tokens are charged at the cached rate, not on top`() {
        val price = ModelPrice(inputPerM = 1.0, cachedPerM = 0.1, outputPerM = 10.0)
        // 600k fresh at $1 + 400k cached at $0.10 + 100k out at $10.
        assertEquals(0.6 + 0.04 + 1.0, price.cost(promptTokens = 1_000_000, cachedTokens = 400_000, outputTokens = 100_000), 1e-9)
        // A cached count larger than the prompt can't make the fresh share negative.
        assertEquals(0.1, price.cost(promptTokens = 1_000_000, cachedTokens = 5_000_000, outputTokens = 0), 1e-9)
    }

    // --- totals ---

    @Test
    fun `a billed slice is taken at its word, whatever the price table says`() {
        val totals = UsageTotals.of(
            slice(provider = ProviderId.OPENROUTER, model = "openai/gpt-4o-mini", costReported = true, prompt = 1000, output = 500, cost = 0.25),
        )
        assertEquals(0.25, totals.costUsd, 1e-9)
        assertFalse(totals.estimated)
        assertEquals(0, totals.unpricedCalls)
        assertEquals(1500, totals.tokens)
    }

    @Test
    fun `an unbilled slice is estimated from its tokens`() {
        val totals = UsageTotals.of(slice(model = "gemini-2.5-flash", prompt = 1_000_000, output = 1_000_000))
        assertEquals(0.30 + 2.50, totals.costUsd, 1e-9)
        assertTrue(totals.estimated)
        assertFalse(totals.unpriced)
    }

    @Test
    fun `a model with no price keeps its tokens and admits the cost is missing`() {
        val unknown = UsageTotals.of(slice(model = "gemini-9-ultra", calls = 3, prompt = 900, output = 100))
        assertEquals(1000, unknown.tokens)
        assertEquals(0.0, unknown.costUsd, 0.0)
        assertTrue(unknown.unpriced)
        assertNull(unknown.costPerCall)

        // Mixed with something priced, the sum is a floor rather than a total.
        val mixed = unknown + UsageTotals.of(slice(provider = ProviderId.OPENROUTER, costReported = true, cost = 0.10))
        assertFalse(mixed.unpriced)
        assertEquals(3, mixed.unpricedCalls)
        assertEquals(0.10, mixed.costUsd, 1e-9)
    }

    @Test
    fun `cost per call is the mean, and absent when there is nothing to divide`() {
        assertEquals(0.05, UsageTotals(calls = 4, costUsd = 0.20).costPerCall!!, 1e-9)
        assertNull(UsageTotals.ZERO.costPerCall)
        assertNull(UsageTotals(calls = 2, costUsd = 0.0).costPerCall)
    }

    // --- summary ---

    @Test
    fun `a summary totals everything and breaks it down both ways, dearest first`() {
        val summary = UsageSummary.of(
            listOf(
                slice(feature = "BOOK", model = "gemini-2.5-flash", calls = 40, prompt = 400_000, output = 200_000),
                slice(feature = "BOOK", provider = ProviderId.OPENROUTER, model = "x/y", costReported = true, calls = 2, prompt = 1000, output = 1000, cost = 0.01),
                slice(feature = "ROUTINE", model = "gemini-2.5-flash", calls = 5, prompt = 20_000, output = 10_000),
                slice(feature = "CHAT", provider = ProviderId.OPENROUTER, model = "x/y", costReported = true, calls = 10, prompt = 5000, output = 2000, cost = 3.0),
            ),
        )
        assertEquals(57, summary.total.calls)
        assertEquals(listOf(LlmFeature.CHAT, LlmFeature.BOOK, LlmFeature.ROUTINE), summary.byFeature.map { it.feature })
        assertEquals(42, summary.byFeature[1].totals.calls)
        // Books mix a billed slice with an estimated one, and say so.
        assertTrue(summary.byFeature[1].totals.estimated)
        assertFalse(summary.byFeature[0].totals.estimated)

        assertEquals(listOf("x/y", "gemini-2.5-flash"), summary.byModel.map { it.model })
        assertEquals(12, summary.byModel[0].totals.calls)
        assertEquals(summary.total.costUsd, summary.byFeature.sumOf { it.totals.costUsd }, 1e-9)
        assertEquals(summary.total.costUsd, summary.byModel.sumOf { it.totals.costUsd }, 1e-9)
    }

    @Test
    fun `a feature name from a newer build is pooled as other, not dropped`() {
        val summary = UsageSummary.of(
            listOf(
                slice(feature = "HOLOGRAMS", calls = 2, prompt = 10),
                slice(feature = "TELEPATHY", calls = 3, prompt = 10),
                slice(feature = "CHAT", calls = 1, prompt = 10),
            ),
        )
        assertEquals(6, summary.total.calls)
        assertEquals(5, summary.byFeature.single { it.feature == null }.totals.calls)
    }

    @Test
    fun `no slices is an empty summary`() {
        assertTrue(UsageSummary.of(emptyList()).isEmpty)
        assertTrue(UsageSummary().isEmpty)
    }

    @Test
    fun `feature names round-trip and are unique`() {
        for (feature in LlmFeature.entries) assertEquals(feature, LlmFeature.fromName(feature.name))
        assertNull(LlmFeature.fromName("chat"))
    }

    // --- formatting ---

    @Test
    fun `tokens are written to at most three figures`() {
        val us = Locale.US
        assertEquals("0", UsageFormat.tokens(0, us))
        assertEquals("742", UsageFormat.tokens(742, us))
        assertEquals("1.0k", UsageFormat.tokens(1_000, us))
        assertEquals("8.5k", UsageFormat.tokens(8_512, us))
        assertEquals("85k", UsageFormat.tokens(85_400, us))
        assertEquals("999k", UsageFormat.tokens(999_400, us))
        // Never "1000k": the rounding that would produce it crosses to the next unit instead.
        assertEquals("1.0M", UsageFormat.tokens(999_960, us))
        assertEquals("1.2M", UsageFormat.tokens(1_234_000, us))
        assertEquals("46M", UsageFormat.tokens(46_100_000, us))
    }

    @Test
    fun `small costs keep two figures instead of rounding to nothing`() {
        val us = Locale.US
        assertEquals("$0.00", UsageFormat.usd(0.0, us))
        assertEquals("$12.35", UsageFormat.usd(12.345, us))
        assertEquals("$0.42", UsageFormat.usd(0.42, us))
        assertEquals("$0.01", UsageFormat.usd(0.01, us))
        assertEquals("$0.0042", UsageFormat.usd(0.0042, us))
        assertEquals("$0.00042", UsageFormat.usd(0.00042, us))
        assertEquals("<$0.0001", UsageFormat.usd(0.00002, us))
        assertEquals("$0,42", UsageFormat.usd(0.42, Locale.forLanguageTag("es-ES")))
    }

    // --- windows ---

    @Test
    fun `a month window is half-open and meets its neighbours exactly`() {
        val october = UsageWindow.month(YearMonth.of(2026, 10))
        assertEquals(LocalDate.of(2026, 10, 1).toEpochDay(), october.fromDay)
        assertEquals(LocalDate.of(2026, 11, 1).toEpochDay(), october.untilDay)
        assertEquals(october.fromDay, UsageWindow.month(YearMonth.of(2026, 9)).untilDay)
        // December rolls the year.
        assertEquals(LocalDate.of(2027, 1, 1).toEpochDay(), UsageWindow.month(YearMonth.of(2026, 12)).untilDay)
        assertEquals(october, UsageWindow.monthOf(LocalDate.of(2026, 10, 31)))
    }
}
