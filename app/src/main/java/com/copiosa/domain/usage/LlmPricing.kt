package com.copiosa.domain.usage

import com.copiosa.domain.model.ProviderId

/** List price of one model, in US dollars per million tokens. */
data class ModelPrice(
    val inputPerM: Double,
    /** What a prompt token served from the provider's cache costs instead of [inputPerM]. */
    val cachedPerM: Double,
    /** Thinking is billed at this rate too, which is why the meter counts it as output. */
    val outputPerM: Double,
) {
    /** [promptTokens] includes [cachedTokens], as both providers report it. */
    fun cost(promptTokens: Long, cachedTokens: Long, outputTokens: Long): Double {
        val cached = cachedTokens.coerceIn(0, promptTokens.coerceAtLeast(0))
        val fresh = (promptTokens - cached).coerceAtLeast(0)
        return (fresh * inputPerM + cached * cachedPerM + outputTokens.coerceAtLeast(0) * outputPerM) / 1_000_000.0
    }
}

/**
 * Prices for the calls whose provider doesn't say what they cost.
 *
 * OpenRouter reports the charge on every response, so nothing of its is listed here and its figures
 * are as billed. Gemini reports tokens only, so a Gemini cost is always an *estimate*: these are the
 * paid-tier list prices (ai.google.dev/gemini-api/docs/pricing, read 2026-10-05), and a key on the
 * free tier pays none of it. The meter labels the two differently for that reason.
 *
 * Applied when usage is read, not when it is recorded — the table stores tokens — so correcting a
 * price here corrects the months already on record.
 *
 * `UsageSummaryTest` requires a price for every model the Gemini picker offers; adding a model to
 * [com.copiosa.data.settings.AppSettings.GEMINI_MODELS] without one fails the build.
 */
object LlmPricing {

    // Order doesn't matter: [priceFor] takes the longest matching id, so "gemini-2.5-flash-lite"
    // is never priced as "gemini-2.5-flash".
    private val GEMINI = mapOf(
        "gemini-3.5-flash" to ModelPrice(inputPerM = 1.50, cachedPerM = 0.15, outputPerM = 9.00),
        "gemini-2.5-flash-lite" to ModelPrice(inputPerM = 0.10, cachedPerM = 0.01, outputPerM = 0.40),
        "gemini-2.5-flash" to ModelPrice(inputPerM = 0.30, cachedPerM = 0.03, outputPerM = 2.50),
        // The under-200k-token tier. Nothing the app sends comes near the boundary: a chat is capped
        // at 60 messages and a book section at a few thousand characters.
        "gemini-2.5-pro" to ModelPrice(inputPerM = 1.25, cachedPerM = 0.125, outputPerM = 10.00),
        // No longer on the pricing page; this is its last list price.
        "gemini-2.0-flash" to ModelPrice(inputPerM = 0.10, cachedPerM = 0.025, outputPerM = 0.40),
    )

    /**
     * The price for [model], or null when there is none to estimate from. A dated or suffixed id
     * (`gemini-2.5-flash-preview-09-2025`) is priced as the model it names.
     */
    fun priceFor(provider: ProviderId, model: String): ModelPrice? = when (provider) {
        ProviderId.GEMINI -> {
            val id = model.trim().lowercase().removePrefix("models/")
            GEMINI.entries
                .filter { (known, _) -> id == known || id.startsWith("$known-") }
                .maxByOrNull { it.key.length }
                ?.value
        }
        ProviderId.OPENROUTER -> null
    }
}
