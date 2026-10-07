package com.copiosa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.tts.SupertonicVoices
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.model.OpenRouterModel
import com.copiosa.domain.model.OpenRouterModels
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.prompt.PromptBuilder
import com.copiosa.ui.books.BookDownloadModePicker
import com.copiosa.ui.components.LabeledDropdown
import com.copiosa.ui.components.ProgressBar
import com.copiosa.ui.components.SectionLabel
import kotlin.math.roundToInt

private fun missingPlaceholders(prompt: String): List<String> =
    PromptBuilder.CORE_PLACEHOLDERS.filter { !prompt.contains(it) }

@Composable
internal fun ModelsTab(
    settings: AppSettings,
    keys: KeyPresence,
    usage: UsageOverview,
    ttsState: TtsModelState,
    voiceCount: Int,
    previewing: Boolean,
    viewModel: SettingsViewModel,
) {
    // Which provider's settings are shown below. Defaults to the active one; the chips only change
    // what you're editing — the "Use …" button is what makes a provider active.
    var selected by remember(settings.provider) { mutableStateOf(settings.provider) }

    SectionLabel(stringResource(R.string.settings_provider))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ProviderId.entries.forEach { p ->
            val isActive = settings.provider == p
            FilterChip(
                selected = selected == p,
                onClick = { selected = p },
                label = { Text(p.displayLabel()) },
                leadingIcon = if (isActive) {
                    {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = stringResource(R.string.settings_provider_in_use),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else null,
            )
        }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProviderConfig(
                provider = selected,
                isActive = settings.provider == selected,
                keySet = if (selected == ProviderId.GEMINI) keys.gemini else keys.openRouter,
                initialKey = remember(selected) { viewModel.apiKey(selected) },
                geminiModel = settings.geminiModel,
                openRouterModel = settings.openRouterModel,
                openRouterModels = settings.openRouterModels,
                onUse = { key, model, models ->
                    if (key.isNotBlank()) viewModel.setApiKey(selected, key)
                    viewModel.update {
                        when (selected) {
                            ProviderId.GEMINI -> it.copy(provider = ProviderId.GEMINI, geminiModel = model)
                            ProviderId.OPENROUTER -> it.copy(
                                provider = ProviderId.OPENROUTER,
                                openRouterModel = model.trim(),
                                openRouterModels = OpenRouterModels.normalize(models, model),
                            )
                        }
                    }
                },
            )
        }
    }

    SectionLabel(stringResource(R.string.settings_usage_section), modifier = Modifier.padding(top = 8.dp))
    UsageCard(usage, settings)

    SectionLabel(stringResource(R.string.settings_background_section), modifier = Modifier.padding(top = 8.dp))
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            SwitchRow(
                title = stringResource(R.string.settings_background_tasks),
                subtitle = stringResource(R.string.settings_background_tasks_desc),
                checked = settings.backgroundLlmTasks,
                onCheckedChange = viewModel::setBackgroundLlmTasks,
            )
            BackgroundModelPicker(
                settings = settings,
                onSelect = viewModel::setBackgroundModel,
                modifier = Modifier.padding(top = 12.dp),
            )
            SectionLabel(
                stringResource(R.string.settings_book_download_mode),
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                stringResource(R.string.settings_book_download_mode_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BookDownloadModePicker(
                selected = settings.bookDefaultDownloadMode,
                onSelect = viewModel::setBookDefaultDownloadMode,
            )
        }
    }

    PromptSection(
        corePrompt = settings.corePrompt,
        customInstructions = settings.customInstructions,
        onSave = { core, custom -> viewModel.update { it.copy(corePrompt = core, customInstructions = custom) } },
    )

    SectionLabel(stringResource(R.string.settings_speech_section), modifier = Modifier.padding(top = 8.dp))
    TtsModelCard(
        state = ttsState,
        voiceCount = voiceCount,
        selectedVoiceSid = settings.ttsVoiceSid,
        speed = settings.ttsSpeed,
        autoDeletePlayedDays = settings.autoDeletePlayedAfterDays,
        previewing = previewing,
        onDownload = viewModel::downloadTts,
        onRemove = viewModel::removeTts,
        onSelectVoice = viewModel::setTtsVoice,
        onSetSpeed = viewModel::setTtsSpeed,
        onSetAutoDeletePlayedDays = viewModel::setAutoDeletePlayedDays,
        onPreviewVoice = viewModel::previewVoice,
    )
}

/**
 * Which model does the work nobody is waiting on, for the provider in use. The first entry follows
 * the main model, and is what an untouched profile has; the rest are that provider's own list — the
 * fixed Gemini set, or the OpenRouter roster above, which is where a cheaper id gets added.
 *
 * Unlike the provider card this applies on selection: there is no key or roster edit to stage with it.
 */
@Composable
private fun BackgroundModelPicker(
    settings: AppSettings,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val models = when (settings.provider) {
        ProviderId.GEMINI -> AppSettings.GEMINI_MODELS
        ProviderId.OPENROUTER -> settings.openRouterModels.map { it.id }
    }
    // Precomputed because optionLabel runs outside composition (can't call stringResource).
    val sameAsMain = stringResource(R.string.settings_background_model_same, settings.label(settings.activeModel))
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LabeledDropdown(
            label = stringResource(R.string.settings_background_model),
            options = listOf("") + models,
            selected = settings.backgroundChoiceFor(settings.provider),
            optionLabel = { if (it.isEmpty()) sameAsMain else settings.label(it) },
            onSelect = onSelect,
        )
        Text(
            stringResource(R.string.settings_background_model_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Editor for one provider's API key + model. The key field and model control are staged locally; the
 * "Use …" button persists them and makes this the active provider in one step (so there's no separate
 * "Save", and it's clear which provider the app is actually using).
 *
 * Gemini offers a fixed list; OpenRouter is a roster the learner builds themselves — any number of
 * ids, all on the one key, one of them marked as the default for new work.
 */
@Composable
private fun ProviderConfig(
    provider: ProviderId,
    isActive: Boolean,
    keySet: Boolean,
    initialKey: String,
    geminiModel: String,
    openRouterModel: String,
    openRouterModels: List<OpenRouterModel>,
    onUse: (key: String, model: String, openRouterModels: List<OpenRouterModel>) -> Unit,
) {
    var keyText by remember(provider, initialKey) { mutableStateOf(initialKey) }
    var keyVisible by remember(provider) { mutableStateOf(false) }
    var geminiSelected by remember(provider, geminiModel) { mutableStateOf(geminiModel) }
    // The roster is edited in place, so it's staged as a list plus the *index* of the default. An id
    // can't be the selection key here: it changes on every keystroke while that row is being typed.
    var models by remember(provider, openRouterModels) { mutableStateOf(openRouterModels) }
    var defaultIndex by remember(provider, openRouterModels, openRouterModel) {
        mutableIntStateOf(openRouterModels.indexOfFirst { it.id == openRouterModel }.coerceAtLeast(0))
    }

    val keyLabel = stringResource(
        if (provider == ProviderId.GEMINI) R.string.settings_gemini_key else R.string.settings_openrouter_key,
    )

    OutlinedTextField(
        value = keyText,
        onValueChange = { keyText = it },
        label = { Text(keyLabel) },
        placeholder = {
            Text(if (keySet) stringResource(R.string.settings_key_set) else stringResource(R.string.settings_key_not_set))
        },
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                    if (keyVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(R.string.settings_key_visibility),
                )
            }
        },
        supportingText = { Text(stringResource(R.string.settings_api_key_shared)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    when (provider) {
        ProviderId.GEMINI -> LabeledDropdown(
            label = stringResource(R.string.settings_model),
            options = AppSettings.GEMINI_MODELS,
            selected = geminiSelected,
            optionLabel = { AppSettings.displayName(it) },
            onSelect = { geminiSelected = it },
        )
        ProviderId.OPENROUTER -> OpenRouterRoster(
            models = models,
            defaultIndex = defaultIndex,
            onEdit = { index, entry -> models = models.toMutableList().also { it[index] = entry } },
            onSetDefault = { defaultIndex = it },
            onAdd = { models = models + OpenRouterModel("") },
            onRemove = { index ->
                models = models.toMutableList().also { it.removeAt(index) }
                // Removing the default hands the role to whatever took its place in the list.
                defaultIndex = when {
                    index < defaultIndex -> defaultIndex - 1
                    else -> defaultIndex
                }.coerceIn(0, (models.size - 1).coerceAtLeast(0))
            },
        )
    }

    val model = if (provider == ProviderId.GEMINI) {
        geminiSelected
    } else {
        models.getOrNull(defaultIndex)?.id?.trim().orEmpty()
    }
    val keyChanged = keyText != initialKey
    val modelChanged = model != (if (provider == ProviderId.GEMINI) geminiModel else openRouterModel)
    val rosterChanged = provider == ProviderId.OPENROUTER &&
        OpenRouterModels.normalize(models, model) != openRouterModels
    // Enabled when switching provider, or applying edits to the already-active one. A roster whose
    // default row has been emptied has nothing to apply — the model id is what actually gets sent.
    val canApply = model.isNotBlank() && (!isActive || keyChanged || modelChanged || rosterChanged)

    Button(
        onClick = { onUse(keyText, model, models) },
        enabled = canApply,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.settings_use_provider, provider.displayLabel()))
    }
    Text(
        stringResource(if (isActive) R.string.settings_provider_in_use else R.string.settings_provider_not_in_use),
        style = MaterialTheme.typography.bodySmall,
        color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
    )
}

/**
 * The OpenRouter model roster: any number of `vendor/model` ids, each with an optional nickname, and
 * a radio marking which one new chats, texts and books start on. Individual books can be pointed at
 * any of the others from their download dialog — that is what makes a roster worth keeping.
 *
 * The last row can't be deleted: OpenRouter with no model is not a state worth being able to reach.
 */
@Composable
private fun OpenRouterRoster(
    models: List<OpenRouterModel>,
    defaultIndex: Int,
    onEdit: (Int, OpenRouterModel) -> Unit,
    onSetDefault: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel(stringResource(R.string.settings_openrouter_models))
        models.forEachIndexed { index, entry ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = index == defaultIndex,
                    onClick = { onSetDefault(index) },
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    OutlinedTextField(
                        value = entry.id,
                        onValueChange = { onEdit(index, entry.copy(id = it)) },
                        label = { Text(stringResource(R.string.settings_openrouter_model)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = entry.name,
                        onValueChange = { onEdit(index, entry.copy(name = it)) },
                        label = { Text(stringResource(R.string.settings_openrouter_model_name)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                IconButton(onClick = { onRemove(index) }, enabled = models.size > 1) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.settings_openrouter_model_remove),
                    )
                }
            }
        }
        Text(
            stringResource(R.string.settings_openrouter_models_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.settings_openrouter_model_add),
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

@Composable
private fun PromptSection(
    corePrompt: String,
    customInstructions: String,
    onSave: (String, String) -> Unit,
) {
    var coreText by remember(corePrompt) { mutableStateOf(corePrompt) }
    var customText by remember(customInstructions) { mutableStateOf(customInstructions) }
    var showWarning by remember { mutableStateOf(false) }

    val savedMissing = missingPlaceholders(corePrompt)

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(stringResource(R.string.settings_core_prompt))
        if (savedMissing.isNotEmpty()) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.settings_prompt_warning_icon),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    OutlinedTextField(
        value = coreText,
        onValueChange = { coreText = it },
        modifier = Modifier.fillMaxWidth(),
        minLines = 5,
    )
    Text(
        stringResource(R.string.settings_prompt_placeholders, PromptBuilder.CORE_PLACEHOLDERS.joinToString("  ")),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.outline,
    )

    SectionLabel(stringResource(R.string.settings_custom_instructions))
    OutlinedTextField(
        value = customText,
        onValueChange = { customText = it },
        modifier = Modifier.fillMaxWidth(),
        minLines = 3,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            if (missingPlaceholders(coreText).isEmpty()) onSave(coreText, customText) else showWarning = true
        }) { Text(stringResource(R.string.settings_save_prompt)) }
        OutlinedButton(onClick = { coreText = PromptBuilder.DEFAULT_CORE_PROMPT }) {
            Text(stringResource(R.string.settings_reset_prompt))
        }
    }

    if (showWarning) {
        val missing = missingPlaceholders(coreText).joinToString("  ")
        AlertDialog(
            onDismissRequest = { showWarning = false },
            confirmButton = {
                TextButton(onClick = {
                    onSave(coreText, customText)
                    showWarning = false
                }) { Text(stringResource(R.string.action_save_anyway)) }
            },
            dismissButton = {
                TextButton(onClick = { showWarning = false }) { Text(stringResource(R.string.action_cancel)) }
            },
            title = { Text(stringResource(R.string.prompt_warning_title)) },
            text = { Text(stringResource(R.string.prompt_warning_message, missing)) },
        )
    }
}

/** Manages the opt-in on-device TTS voice: download / progress / installed + voice picker + speed + remove. */
@Composable
private fun TtsModelCard(
    state: TtsModelState,
    voiceCount: Int,
    selectedVoiceSid: Int,
    speed: Float,
    autoDeletePlayedDays: Int,
    previewing: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    onSelectVoice: (Int) -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSetAutoDeletePlayedDays: (Int) -> Unit,
    onPreviewVoice: (Int) -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    Icons.Filled.RecordVoiceOver,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_tts_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        ttsSubtitle(state),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when (state) {
                is TtsModelState.NotDownloaded, is TtsModelState.Failed ->
                    Button(onClick = onDownload) { Text(stringResource(R.string.audio_download_tts)) }
                is TtsModelState.Downloading -> {
                    if (state.progress >= 0f) {
                        ProgressBar(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                is TtsModelState.Ready -> {
                    // Precomputed because optionLabel runs outside composition (can't call stringResource).
                    // Named voices where known; numbered fallback for any beyond the curated list.
                    val voiceLabels = (0 until voiceCount).map {
                        SupertonicVoices.name(it) ?: stringResource(R.string.settings_tts_voice_n, it + 1)
                    }
                    LabeledDropdown(
                        label = stringResource(R.string.settings_tts_voice),
                        options = (0 until voiceCount).toList(),
                        selected = selectedVoiceSid.coerceIn(0, (voiceCount - 1).coerceAtLeast(0)),
                        optionLabel = { voiceLabels[it] },
                        onSelect = onSelectVoice,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = { onPreviewVoice(selectedVoiceSid) },
                            enabled = !previewing,
                        ) {
                            if (previewing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Text(stringResource(R.string.settings_tts_preview), modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = onRemove) { Text(stringResource(R.string.settings_tts_remove)) }
                    }
                }
            }
            // Speed applies to all generated audio, so it's shown whether or not the voice is installed.
            SpeedSlider(speed = speed, onSpeed = onSetSpeed)
            // Retention runs at app launch; 0 keeps played audio forever.
            val retentionLabels = AUTO_DELETE_OPTIONS.associateWith { days ->
                if (days == 0) {
                    stringResource(R.string.settings_audio_retention_off)
                } else {
                    stringResource(R.string.settings_audio_retention_days, days)
                }
            }
            LabeledDropdown(
                label = stringResource(R.string.settings_audio_retention),
                options = AUTO_DELETE_OPTIONS,
                selected = if (autoDeletePlayedDays in AUTO_DELETE_OPTIONS) autoDeletePlayedDays else 0,
                optionLabel = { retentionLabels[it].orEmpty() },
                onSelect = onSetAutoDeletePlayedDays,
            )
        }
    }
}

/** Auto-delete windows for played playlist audio, in days; 0 = never. */
private val AUTO_DELETE_OPTIONS = listOf(0, 7, 14, 30, 90)

/** Playback-speed slider (50%–150%); the value persists on release. */
@Composable
private fun SpeedSlider(speed: Float, onSpeed: (Float) -> Unit) {
    var local by remember(speed) { mutableStateOf(speed) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.settings_tts_speed), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.settings_tts_speed_value, (local * 100).roundToInt()),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onSpeed(local) },
            valueRange = AppSettings.TTS_SPEED_MIN..AppSettings.TTS_SPEED_MAX,
            // 0.5–1.5 in 0.1 increments: 11 stops -> 9 internal steps.
            steps = 9,
        )
    }
}

@Composable
private fun ttsSubtitle(state: TtsModelState): String = when (state) {
    is TtsModelState.NotDownloaded -> stringResource(R.string.settings_tts_not_downloaded)
    is TtsModelState.Downloading ->
        if (state.progress >= 0f) {
            stringResource(R.string.audio_downloading, (state.progress.coerceIn(0f, 1f) * 100).toInt())
        } else {
            stringResource(R.string.audio_downloading_indeterminate)
        }
    is TtsModelState.Ready -> stringResource(R.string.settings_tts_ready)
    is TtsModelState.Failed -> stringResource(R.string.settings_tts_failed, state.message)
}
