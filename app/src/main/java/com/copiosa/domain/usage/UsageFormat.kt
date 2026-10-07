package com.copiosa.domain.usage

import java.time.LocalDate
import java.time.YearMonth
import java.util.Locale

/** How the meter writes its two kinds of number. Pure, so the rounding rules are testable. */
object UsageFormat {

    /** `742`, `8.5k`, `85k`, `1.2M` — three significant figures at most; a meter, not a ledger. */
    fun tokens(count: Long, locale: Locale = Locale.getDefault()): String {
        val n = count.coerceAtLeast(0)
        return when {
            n < 1_000 -> n.toString()
            // The boundary is where "%.0fk" would round up to "1000k".
            n < 999_500 -> scaled(n / 1_000.0, "k", locale)
            else -> scaled(n / 1_000_000.0, "M", locale)
        }
    }

    private fun scaled(value: Double, suffix: String, locale: Locale): String =
        if (value < 9.95) String.format(locale, "%.1f%s", value, suffix) else String.format(locale, "%.0f%s", value, suffix)

    /**
     * Dollars to the cent once there are cents to show. Below that — which is where a single routine
     * or one book section lives — two significant figures, since "$0.00" would be the one answer the
     * meter exists not to give.
     */
    fun usd(amount: Double, locale: Locale = Locale.getDefault()): String {
        val a = amount.coerceAtLeast(0.0)
        return when {
            a == 0.0 -> String.format(locale, "$%.2f", 0.0)
            a >= 0.01 -> String.format(locale, "$%.2f", a)
            a >= 0.001 -> String.format(locale, "$%.4f", a)
            a >= 0.0001 -> String.format(locale, "$%.5f", a)
            else -> "<" + String.format(locale, "$%.4f", 0.0001)
        }
    }
}

/** A calendar month as the half-open range of local epoch days the usage table is keyed by. */
data class UsageWindow(val fromDay: Long, val untilDay: Long) {
    companion object {
        fun month(month: YearMonth) = UsageWindow(
            fromDay = month.atDay(1).toEpochDay(),
            untilDay = month.plusMonths(1).atDay(1).toEpochDay(),
        )

        fun monthOf(day: LocalDate) = month(YearMonth.from(day))
    }
}
