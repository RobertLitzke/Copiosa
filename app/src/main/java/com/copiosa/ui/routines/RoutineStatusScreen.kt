package com.copiosa.ui.routines

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.domain.routine.AppearFrequency
import com.copiosa.domain.routine.ComfortBand
import com.copiosa.domain.routine.DifficultyTier
import com.copiosa.ui.components.ScreenHeader
import java.text.DateFormat
import java.util.Date

/**
 * Routine "status" sub-screen: shows the comfort level the adaptation engine has established for each
 * scored drill the target language supports — per contrast-cloze category and referential PI — plus a note that the
 * unscored affective PI just follows the chosen input level. Read-only; data is live via the VM.
 */
@Composable
fun RoutineStatusScreen(
    onBack: () -> Unit,
    viewModel: RoutineStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.routine_status_title), onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(R.string.routine_status_legend),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Rapid-fire listening buckets exist for every supported language.
            if (state.rapidFire.isNotEmpty()) {
                StatusCard(stringResource(R.string.routine_status_group_rapid_fire)) {
                    state.rapidFire.forEachIndexed { i, bucket ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BucketRow(bucket)
                    }
                }
            }

            if (!state.hasGrammarProfile) {
                Text(
                    stringResource(R.string.routine_status_no_grammar_profile),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                if (state.conjugation.isNotEmpty()) {
                    StatusCard(stringResource(R.string.routine_status_group_conjugation)) {
                        state.conjugation.forEachIndexed { i, bucket ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            BucketRow(bucket)
                        }
                    }
                }
                StatusCard(stringResource(R.string.routine_status_group_cloze)) {
                    state.clozeCategories.forEachIndexed { i, bucket ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BucketRow(bucket)
                    }
                }
                state.referentialPi?.let { pi ->
                    StatusCard(stringResource(R.string.routine_status_group_referential)) { BucketRow(pi) }
                }
                state.multipleChoiceCloze?.let { mc ->
                    StatusCard(stringResource(R.string.routine_status_group_mc_cloze)) { BucketRow(mc) }
                }
                StatusCard(stringResource(R.string.routine_status_group_affective)) {
                    Text(
                        stringResource(R.string.routine_status_affective_note, state.level.name),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

@Composable
private fun BucketRow(status: BucketStatus) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        status.label?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                bandLabel(status.band),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            status.scorePercent?.let { ScorePill(it) }
        }
        if (status.attempts > 0) {
            Text(
                metaLine(status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ScorePill(percent: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            "$percent%",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** "Harder · appears sometimes · 12 attempts · last practiced Jun 26, 2026". */
@Composable
private fun metaLine(status: BucketStatus): String {
    val parts = buildList {
        add(tierLabel(status.tier))
        add(freqLabel(status.frequency))
        add(pluralStringResource(R.plurals.routine_status_attempts, status.attempts, status.attempts))
        status.lastSeenAt?.let {
            val date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))
            add(stringResource(R.string.routine_status_last_practiced, date))
        }
    }
    return parts.joinToString(" · ")
}

@Composable
private fun bandLabel(band: ComfortBand): String = stringResource(
    when (band) {
        ComfortBand.NOT_PRACTICED -> R.string.routine_status_band_new
        ComfortBand.BUILDING -> R.string.routine_status_band_building
        ComfortBand.COMFORTABLE -> R.string.routine_status_band_comfortable
        ComfortBand.MASTERED -> R.string.routine_status_band_mastered
    },
)

@Composable
private fun tierLabel(tier: DifficultyTier): String = stringResource(
    when (tier) {
        DifficultyTier.BASELINE -> R.string.routine_status_tier_baseline
        DifficultyTier.HARDER -> R.string.routine_status_tier_harder
        DifficultyTier.HARDEST -> R.string.routine_status_tier_hardest
    },
)

@Composable
private fun freqLabel(frequency: AppearFrequency): String = stringResource(
    when (frequency) {
        AppearFrequency.OFTEN -> R.string.routine_status_freq_often
        AppearFrequency.SOMETIMES -> R.string.routine_status_freq_sometimes
        AppearFrequency.RARELY -> R.string.routine_status_freq_rarely
    },
)
