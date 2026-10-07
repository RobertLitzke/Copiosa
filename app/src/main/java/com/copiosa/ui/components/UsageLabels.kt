package com.copiosa.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.copiosa.R
import com.copiosa.domain.usage.UsageFormat
import com.copiosa.domain.usage.UsageTotals
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * A cost as the meter writes it everywhere: `$0.42` when a provider billed it, `≈ $0.42` when any of
 * it came from the price table, a trailing `+` when some calls could not be priced at all, and words
 * instead of a figure when none could — a bare `$0.00` there would read as "free".
 */
@Composable
fun usageCostLabel(totals: UsageTotals): String {
    if (totals.unpriced) return stringResource(R.string.usage_cost_unknown)
    var label = UsageFormat.usd(totals.costUsd)
    if (totals.estimated) label = stringResource(R.string.usage_cost_estimated, label)
    if (totals.unpricedCalls > 0) label = stringResource(R.string.usage_cost_floor, label)
    return label
}

/** The day a count starts from, e.g. "Oct 5, 2026" — said wherever a figure doesn't reach back further. */
fun usageDateLabel(day: LocalDate): String =
    day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
