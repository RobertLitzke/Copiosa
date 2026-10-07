package com.copiosa.ui.quiz

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.data.quiz.PoolSeedState
import com.copiosa.domain.quiz.CueKind
import com.copiosa.domain.quiz.CueType
import com.copiosa.domain.quiz.QuizPoolConfig
import com.copiosa.domain.quiz.TimeStyle
import com.copiosa.ui.audio.TtsDownloadPrompt
import com.copiosa.ui.components.ProgressBar
import com.copiosa.ui.components.ScreenHeader

/**
 * Pool management, doubling as first-time setup: pick cue types, number range, time phrasing style
 * and pool size, then generate — the seeding (LLM phrasing) and rendering (TTS) phases report live
 * below. Also shows the current pool's state, disk usage, and delete/retry actions.
 */
@Composable
fun QuizPoolScreen(
    onBack: () -> Unit,
    viewModel: QuizPoolViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    // This screen manages a single cue type's pool (Numbers or Times) — the type came from the mode the
    // user is already in, so it isn't re-selectable here.
    val cueType = viewModel.cueType
    // Config selections, seeded from the existing pool's config when there is one.
    val existing = state.progress?.config
    var numberMax by remember(existing) { mutableIntStateOf(existing?.numberMax ?: 100) }
    var timeStyle by remember(existing) { mutableStateOf(existing?.timeStyle ?: TimeStyle.MIXED) }
    var poolSize by remember(existing) { mutableIntStateOf(existing?.poolSize ?: 250) }

    if (!state.loading && !state.modelReady && !state.modelDownloading) {
        TtsDownloadPrompt(onConfirm = viewModel::downloadModel, onDismiss = onBack)
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.quiz_pool_title_format, cueLabel(cueType)), onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.loading) {
                CircularProgressIndicator(Modifier.padding(top = 48.dp))
                return@Column
            }

            state.progress?.let { progress ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stringResource(R.string.quiz_pool_current),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            stringResource(R.string.quiz_pool_ready, progress.ready, progress.total),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (progress.ready < progress.total && progress.failed < progress.total - progress.ready) {
                            ProgressBar(
                                progress = { if (progress.total == 0) 0f else progress.ready.toFloat() / progress.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        Text(
                            stringResource(R.string.quiz_pool_disk, state.diskUsageBytes / (1024 * 1024)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (progress.failed > 0) {
                            Text(
                                stringResource(R.string.quiz_pool_failed, progress.failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            OutlinedButton(onClick = viewModel::retryFailed) {
                                Text(stringResource(R.string.quiz_pool_retry))
                            }
                        }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text(stringResource(R.string.quiz_pool_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }

            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        stringResource(R.string.quiz_pool_config),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )

                    if (cueType.kind == CueKind.PHRASE) {
                        Text(
                            stringResource(R.string.quiz_pool_phrase_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (cueType == CueType.NUMBER) {
                        SectionLabel(stringResource(R.string.quiz_pool_number_range))
                        ChipRow {
                            listOf(100, 1_000, 100_000, 1_000_000).forEach { max ->
                                FilterChip(
                                    selected = numberMax == max,
                                    onClick = { numberMax = max },
                                    label = { Text("1–${"%,d".format(max)}") },
                                )
                            }
                        }
                    }

                    if (cueType == CueType.TIME) {
                        SectionLabel(stringResource(R.string.quiz_pool_time_style))
                        ChipRow {
                            TimeStyle.entries.forEach { style ->
                                FilterChip(
                                    selected = timeStyle == style,
                                    onClick = { timeStyle = style },
                                    label = { Text(timeStyleLabel(style)) },
                                )
                            }
                        }
                        if (timeStyle != TimeStyle.NUMERIC) {
                            Text(
                                stringResource(R.string.quiz_pool_llm_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    SectionLabel(stringResource(R.string.quiz_pool_size))
                    ChipRow {
                        listOf(100, 250, 400).forEach { size ->
                            FilterChip(
                                selected = poolSize == size,
                                onClick = { poolSize = size },
                                label = { Text(size.toString()) },
                            )
                        }
                    }
                    Text(
                        stringResource(R.string.quiz_pool_estimate, poolSize / 8),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = {
                            viewModel.buildPool(
                                QuizPoolConfig(
                                    langCode = state.lang,
                                    cueType = cueType,
                                    numberMax = numberMax,
                                    timeStyle = timeStyle,
                                    poolSize = poolSize,
                                ),
                            )
                        },
                        enabled = state.modelReady && state.seedState != PoolSeedState.Seeding,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            stringResource(
                                if (state.progress == null) R.string.quiz_pool_generate else R.string.quiz_pool_regenerate,
                            ),
                        )
                    }
                    SeedStateNote(state.seedState)
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.quiz_pool_delete)) },
            text = { Text(stringResource(R.string.quiz_pool_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; viewModel.deletePool() }) {
                    Text(stringResource(R.string.quiz_pool_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun SeedStateNote(seedState: PoolSeedState) {
    when (seedState) {
        PoolSeedState.Idle -> Unit
        PoolSeedState.Seeding -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.height(18.dp).padding(end = 8.dp))
            Text(stringResource(R.string.quiz_pool_seeding), style = MaterialTheme.typography.bodySmall)
        }
        is PoolSeedState.Done -> {
            val note = when {
                seedState.noKey -> stringResource(R.string.quiz_pool_seed_no_key)
                seedState.skippedCount > 0 -> stringResource(R.string.quiz_pool_seed_fallbacks, seedState.skippedCount)
                else -> null
            }
            note?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        is PoolSeedState.Failed -> Text(
            seedState.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun cueLabel(cue: CueType): String = stringResource(
    when (cue) {
        CueType.NUMBER -> R.string.quiz_cue_numbers
        CueType.TIME -> R.string.quiz_cue_times
        CueType.RESTAURANT -> R.string.quiz_cue_restaurant
    },
)

@Composable
private fun timeStyleLabel(style: TimeStyle): String = stringResource(
    when (style) {
        TimeStyle.NUMERIC -> R.string.quiz_time_numeric
        TimeStyle.COLLOQUIAL -> R.string.quiz_time_colloquial
        TimeStyle.MIXED -> R.string.quiz_time_mixed
    },
)
