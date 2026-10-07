package com.copiosa.domain.usage

import com.copiosa.domain.model.ProviderId
import java.time.LocalDate

/**
 * What one call consumed, as its provider reported it. [promptTokens] includes [cachedTokens];
 * [outputTokens] includes thinking, which is billed as output. [costUsd] is the provider's own
 * figure where it gives one, and null where it reports tokens only.
 */
data class TokenUsage(
    val promptTokens: Long,
    val cachedTokens: Long,
    val outputTokens: Long,
    val costUsd: Double? = null,
)

/**
 * A group of recorded calls sharing a provider, model and feature — the shape the store hands back.
 * [costReported] says whether [costUsd] is the provider's figure; where it is false, [costUsd] is
 * zero and the cost has to be estimated from the tokens.
 */
data class UsageSlice(
    val provider: ProviderId,
    val model: String,
    /** An [LlmFeature] name; kept as text so a name from a newer build survives the round trip. */
    val feature: String,
    val costReported: Boolean,
    val calls: Int,
    val promptTokens: Long,
    val cachedTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
)

/**
 * Totals for some set of calls. Tokens are always exact. Cost is three things at once, because a
 * month can mix all three: what a provider billed, what the price table estimates, and calls nobody
 * can price — an unknown model's tokens are counted, and its cost is admitted to be missing rather
 * than quietly treated as free.
 */
data class UsageTotals(
    val calls: Int = 0,
    val promptTokens: Long = 0,
    val cachedTokens: Long = 0,
    val outputTokens: Long = 0,
    /** Everything known: billed plus estimated. Excludes the [unpricedCalls]. */
    val costUsd: Double = 0.0,
    /** True when any of [costUsd] came from the price table rather than a provider's bill. */
    val estimated: Boolean = false,
    /** Calls whose cost is in neither — [costUsd] is a floor when this is above zero. */
    val unpricedCalls: Int = 0,
) {
    val tokens: Long get() = promptTokens + outputTokens

    /** True when no call in here could be priced at all, so there is no figure worth showing. */
    val unpriced: Boolean get() = calls > 0 && unpricedCalls == calls

    /** Mean cost of a call, or null when there is nothing meaningful to divide. */
    val costPerCall: Double? get() = if (calls > 0 && !unpriced && costUsd > 0.0) costUsd / calls else null

    operator fun plus(other: UsageTotals) = UsageTotals(
        calls = calls + other.calls,
        promptTokens = promptTokens + other.promptTokens,
        cachedTokens = cachedTokens + other.cachedTokens,
        outputTokens = outputTokens + other.outputTokens,
        costUsd = costUsd + other.costUsd,
        estimated = estimated || other.estimated,
        unpricedCalls = unpricedCalls + other.unpricedCalls,
    )

    companion object {
        val ZERO = UsageTotals()

        fun of(slice: UsageSlice, priceFor: (ProviderId, String) -> ModelPrice? = LlmPricing::priceFor): UsageTotals {
            val price = if (slice.costReported) null else priceFor(slice.provider, slice.model)
            return UsageTotals(
                calls = slice.calls,
                promptTokens = slice.promptTokens,
                cachedTokens = slice.cachedTokens,
                outputTokens = slice.outputTokens,
                costUsd = when {
                    slice.costReported -> slice.costUsd
                    price != null -> price.cost(slice.promptTokens, slice.cachedTokens, slice.outputTokens)
                    else -> 0.0
                },
                estimated = !slice.costReported && price != null,
                unpricedCalls = if (slice.costReported || price != null) 0 else slice.calls,
            )
        }

        fun of(slices: List<UsageSlice>, priceFor: (ProviderId, String) -> ModelPrice? = LlmPricing::priceFor): UsageTotals =
            slices.fold(ZERO) { acc, slice -> acc + of(slice, priceFor) }
    }
}

/**
 * What one book has cost, and the day that count starts from. The date is part of the figure, not
 * decoration: a book translated before the meter existed has sections nobody recorded, and "so far"
 * would claim them.
 */
data class BookSpend(val totals: UsageTotals, val since: LocalDate)

/** One feature's share. [feature] is null for names this build doesn't know, pooled as "other". */
data class FeatureUsage(val feature: LlmFeature?, val totals: UsageTotals)

data class ModelUsage(val provider: ProviderId, val model: String, val totals: UsageTotals)

/** A period's usage, whole and broken down both ways. Both lists run most expensive first. */
data class UsageSummary(
    val total: UsageTotals = UsageTotals.ZERO,
    val byFeature: List<FeatureUsage> = emptyList(),
    val byModel: List<ModelUsage> = emptyList(),
) {
    val isEmpty: Boolean get() = total.calls == 0

    companion object {
        fun of(
            slices: List<UsageSlice>,
            priceFor: (ProviderId, String) -> ModelPrice? = LlmPricing::priceFor,
        ): UsageSummary {
            val priced = slices.map { it to UsageTotals.of(it, priceFor) }
            return UsageSummary(
                total = priced.fold(UsageTotals.ZERO) { acc, (_, totals) -> acc + totals },
                byFeature = priced
                    .groupBy({ (slice, _) -> LlmFeature.fromName(slice.feature) }, { it.second })
                    .map { (feature, totals) -> FeatureUsage(feature, totals.reduce(UsageTotals::plus)) }
                    .sortedWith(compareByDescending<FeatureUsage> { it.totals.costUsd }.thenByDescending { it.totals.tokens }),
                byModel = priced
                    .groupBy({ (slice, _) -> slice.provider to slice.model }, { it.second })
                    .map { (key, totals) -> ModelUsage(key.first, key.second, totals.reduce(UsageTotals::plus)) }
                    .sortedWith(compareByDescending<ModelUsage> { it.totals.costUsd }.thenByDescending { it.totals.tokens }),
            )
        }
    }
}
