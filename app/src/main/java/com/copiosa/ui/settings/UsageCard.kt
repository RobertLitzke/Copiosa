package com.copiosa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.settings.AppSettings
import com.copiosa.domain.usage.LlmFeature
import com.copiosa.domain.usage.UsageFormat
import com.copiosa.domain.usage.UsageSummary
import com.copiosa.domain.usage.UsageTotals
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.usageCostLabel
import com.copiosa.ui.components.usageDateLabel
import java.time.LocalDate

/** What the usage card draws: the month in progress in full, and last month as one line beside it. */
data class UsageOverview(
    val thisMonth: UsageSummary = UsageSummary(),
    val lastMonth: UsageTotals = UsageTotals.ZERO,
    /**
     * The day the meter's first entry was made, when that falls inside either month shown — so a
     * figure that only covers part of its month says so. Null once both months are measured whole.
     */
    val countingSince: LocalDate? = null,
)

/**
 * Month-to-date model usage: the total, then the same total cut by feature and by model.
 *
 * It counts every profile together, unlike nearly everything else in Settings — the API keys are
 * shared across profiles, so the bill is too. Tokens are exact; see [usageCostLabel] for how far each
 * cost can be trusted, and the footnotes below for what the marks mean.
 */
@Composable
internal fun UsageCard(overview: UsageOverview, settings: AppSettings) {
    val month = overview.thisMonth
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (month.isEmpty) {
                Text(
                    stringResource(R.string.usage_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column {
                    Text(usageCostLabel(month.total), style = MaterialTheme.typography.headlineSmall)
                    Text(
                        tokensAndCalls(month.total),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(
                            R.string.usage_in_out,
                            UsageFormat.tokens(month.total.promptTokens),
                            UsageFormat.tokens(month.total.outputTokens),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (overview.lastMonth.calls > 0) {
                Text(
                    stringResource(
                        R.string.usage_last_month,
                        usageCostLabel(overview.lastMonth),
                        UsageFormat.tokens(overview.lastMonth.tokens),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!month.isEmpty) {
                SectionLabel(stringResource(R.string.usage_by_feature))
                month.byFeature.forEach { row ->
                    // "Each" is per call — for a routine, which is one call, the price of a routine.
                    UsageRow(featureLabel(row.feature), row.totals, showEach = true)
                }
                SectionLabel(stringResource(R.string.usage_by_model))
                month.byModel.forEach { row ->
                    UsageRow("${settings.label(row.model)} · ${row.provider.displayLabel()}", row.totals, showEach = false)
                }
            }
            overview.countingSince?.let {
                UsageNote(stringResource(R.string.usage_counting_since, usageDateLabel(it)))
            }
            if (month.total.estimated || overview.lastMonth.estimated) {
                UsageNote(stringResource(R.string.usage_note_estimated))
            }
            if (month.total.unpricedCalls > 0 || overview.lastMonth.unpricedCalls > 0) {
                UsageNote(stringResource(R.string.usage_note_unpriced))
            }
        }
    }
}

@Composable
private fun UsageRow(title: String, totals: UsageTotals, showEach: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            val each = totals.costPerCall?.takeIf { showEach && totals.calls > 1 }
            Text(
                if (each == null) {
                    tokensAndCalls(totals)
                } else {
                    tokensAndCalls(totals) + " · " + stringResource(R.string.usage_each, UsageFormat.usd(each))
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(usageCostLabel(totals), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun UsageNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )
}

@Composable
private fun tokensAndCalls(totals: UsageTotals): String =
    pluralStringResource(R.plurals.usage_tokens_calls, totals.calls, UsageFormat.tokens(totals.tokens), totals.calls)

@Composable
private fun featureLabel(feature: LlmFeature?): String = stringResource(
    when (feature) {
        LlmFeature.CHAT -> R.string.usage_feature_chat
        LlmFeature.TEXT -> R.string.usage_feature_text
        LlmFeature.BOOK -> R.string.usage_feature_book
        LlmFeature.ROUTINE -> R.string.usage_feature_routine
        LlmFeature.COURSE -> R.string.usage_feature_course
        LlmFeature.CLOZE_BANK -> R.string.usage_feature_cloze
        LlmFeature.QUIZ_POOL -> R.string.usage_feature_quiz
        LlmFeature.VOCAB -> R.string.usage_feature_vocab
        LlmFeature.BULK_ADD -> R.string.usage_feature_bulk_add
        LlmFeature.EXPLAIN -> R.string.usage_feature_explain
        null -> R.string.usage_feature_other
    },
)
