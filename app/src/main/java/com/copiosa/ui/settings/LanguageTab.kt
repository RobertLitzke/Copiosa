package com.copiosa.ui.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.settings.AppSettings
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.srs.SpacedRepetitionScheduler
import com.copiosa.ui.components.LabeledDropdown
import com.copiosa.ui.components.SectionLabel
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.system.exitProcess

@Composable
internal fun LanguageTab(settings: AppSettings, viewModel: SettingsViewModel) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LabeledDropdown(
                label = stringResource(R.string.settings_native_lang),
                options = LANGUAGE_TAGS,
                selected = settings.nativeLanguage,
                optionLabel = { langName(it) },
                onSelect = { tag -> viewModel.update { it.copy(nativeLanguage = tag) } },
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(R.string.settings_default_level))
                CefrPills(
                    selected = settings.defaultLevel,
                    onSelect = { lvl -> viewModel.update { it.copy(defaultLevel = lvl) } },
                )
            }
            // Owns its text locally once edited, so keystrokes register instantly instead of waiting
            // for each async DataStore round-trip (binding `value` straight to the flow made characters
            // lag or drop when typing quickly). We still persist on every change; the local value seeds
            // from the persisted one until the user starts typing.
            var countryEdit by remember { mutableStateOf<String?>(null) }
            OutlinedTextField(
                value = countryEdit ?: settings.defaultCountry,
                onValueChange = { c ->
                    countryEdit = c
                    viewModel.update { it.copy(defaultCountry = c) }
                },
                label = { Text(stringResource(R.string.settings_default_country)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            LabeledDropdown(
                label = stringResource(R.string.settings_app_language),
                options = APP_LANGUAGE_TAGS,
                selected = if (settings.appLanguage in APP_LANGUAGE_TAGS) settings.appLanguage else "en",
                optionLabel = { langName(it) },
                onSelect = { tag -> viewModel.setAppLanguage(tag) },
            )
        }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_goal_section))
            GoalSlider(settings = settings, viewModel = viewModel)
        }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_srs_section))
            RetentionSlider(settings = settings, viewModel = viewModel)
        }
    }
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_notifications_section))
            // Enabling the reminder needs the runtime notification permission on API 33+. The setting
            // is stored either way; the notifier no-ops while notifications stay disallowed.
            val permissionLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { /* result reflected by the system; nothing to store */ }
            SwitchRow(
                title = stringResource(R.string.settings_review_reminders),
                subtitle = stringResource(R.string.settings_review_reminders_desc),
                checked = settings.reviewReminders,
                onCheckedChange = { on ->
                    if (on && Build.VERSION.SDK_INT >= 33) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    viewModel.update { it.copy(reviewReminders = on) }
                },
            )
        }
    }
    BackupCard(viewModel)
}

/**
 * Full backup (DB + media + settings) to a user-picked zip, and restore from one. Restoring
 * replaces everything and then kills the process — Room/DataStore hold pre-restore state that
 * must not leak back — so the user is warned and confirms first.
 */
@Composable
private fun BackupCard(viewModel: SettingsViewModel) {
    val status by viewModel.backup.collectAsState()
    var pendingRestore by remember { mutableStateOf<Uri?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> pendingRestore = uri }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionLabel(stringResource(R.string.settings_backup_section))
            Text(
                stringResource(R.string.settings_backup_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("copiosa-backup.zip") },
                    enabled = status != BackupStatus.WORKING,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_backup_export)) }
                OutlinedButton(
                    onClick = { restoreLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = status != BackupStatus.WORKING,
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.settings_backup_restore)) }
            }
            when (status) {
                BackupStatus.WORKING -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                BackupStatus.DONE -> Text(
                    stringResource(R.string.settings_backup_saved),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                BackupStatus.ERROR -> Text(
                    stringResource(R.string.settings_backup_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                BackupStatus.IDLE -> Unit
            }
        }
    }

    pendingRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.settings_restore_confirm_title)) },
            text = { Text(stringResource(R.string.settings_restore_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    viewModel.restoreBackup(uri) { exitProcess(0) }
                }) { Text(stringResource(R.string.settings_backup_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Daily study-time goal (minutes) driving the home tracker's target line. Persists on release. */
@Composable
private fun GoalSlider(settings: AppSettings, viewModel: SettingsViewModel) {
    var local by remember(settings.dailyGoalMinutes) { mutableStateOf(settings.dailyGoalMinutes.toFloat()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.settings_goal_label), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.settings_goal_value, local.roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Slider(
        value = local,
        onValueChange = { local = it },
        onValueChangeFinished = { viewModel.update { it.copy(dailyGoalMinutes = local.roundToInt()) } },
        valueRange = AppSettings.DAILY_GOAL_MIN.toFloat()..AppSettings.DAILY_GOAL_MAX.toFloat(),
        // 5–60 in 5-minute increments: 12 stops -> 10 internal steps.
        steps = 10,
    )
    Text(
        stringResource(R.string.settings_goal_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * Target-recall slider for this language's review schedule. Unlike the other settings rows this one
 * does *not* persist as you drag: retention reshapes the whole review schedule, so the pending value
 * is held locally, its effect on real intervals is shown, and it only lands via Apply + confirm.
 */
@Composable
private fun RetentionSlider(settings: AppSettings, viewModel: SettingsViewModel) {
    val saved = settings.desiredRetention
    // Re-seeds when the saved value changes — including on a profile switch, since retention is per language.
    var pending by remember(saved) { mutableStateOf(saved.toFloat()) }
    var confirming by remember { mutableStateOf(false) }
    val dirty = kotlin.math.abs(pending - saved.toFloat()) > 0.0005f

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.settings_srs_retention), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.settings_srs_retention_value, (pending * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Slider(
        value = pending,
        onValueChange = { pending = it },
        valueRange = SpacedRepetitionScheduler.MIN_RETENTION.toFloat()..
            SpacedRepetitionScheduler.MAX_RETENTION.toFloat(),
        // 85%–97% in 1-point increments: 13 stops -> 11 internal steps.
        steps = 11,
    )
    Text(
        stringResource(R.string.settings_srs_explainer),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (dirty) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.settings_srs_preview_title),
                style = MaterialTheme.typography.labelMedium,
            )
            viewModel.retentionPreview(saved, pending.toDouble()).forEach { ex ->
                Text(
                    stringResource(
                        R.string.settings_srs_preview_row,
                        intervalLabel(ex.currentDays),
                        intervalLabel(ex.projectedDays),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { pending = saved.toFloat() }) {
                Text(stringResource(R.string.action_cancel))
            }
            Button(onClick = { confirming = true }) {
                Text(stringResource(R.string.settings_srs_apply))
            }
        }
    }

    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(R.string.settings_srs_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_srs_confirm_body,
                        (pending * 100).roundToInt(),
                        langName(settings.targetLanguage),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setDesiredRetention(pending.toDouble())
                    confirming = false
                }) { Text(stringResource(R.string.settings_srs_apply)) }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** "14 days" / "3 months" / "2.5 years" — coarse on purpose, since FSRS fuzzes exact due dates anyway. */
@Composable
private fun intervalLabel(days: Int): String = when {
    days < 30 -> stringResource(R.string.settings_srs_days, days)
    days < 365 -> {
        val months = (days / 30.44).roundToInt().coerceAtLeast(1)
        if (months == 1) stringResource(R.string.settings_srs_month_one)
        else stringResource(R.string.settings_srs_months, months)
    }
    days < 730 -> stringResource(R.string.settings_srs_year_one)
    // One decimal past a year: "2.5 years" reads better than "30 months" at this scale.
    else -> stringResource(R.string.settings_srs_years, String.format(Locale.getDefault(), "%.1f", days / 365.0))
}

/** Tappable CEFR level pills (A1–C2); the selected one is filled. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CefrPills(selected: Cefr, onSelect: (Cefr) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Cefr.entries.forEach { lvl ->
            FilterChip(
                selected = lvl == selected,
                onClick = { onSelect(lvl) },
                label = { Text(lvl.name) },
            )
        }
    }
}
