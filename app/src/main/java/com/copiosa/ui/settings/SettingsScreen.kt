package com.copiosa.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.copiosa.R
import com.copiosa.data.settings.AppSettings
import com.copiosa.data.tts.SupertonicVoices
import com.copiosa.data.tts.TtsModelState
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.ReadingFont
import com.copiosa.domain.model.ReadingTextSize
import com.copiosa.domain.model.ThemeMode
import com.copiosa.domain.prompt.PromptBuilder
import com.copiosa.ui.components.LabeledDropdown
import com.copiosa.ui.components.ScreenHeader
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.theme.readingFontFamily
import com.copiosa.ui.theme.readingScale
import java.util.Locale
import kotlin.math.roundToInt

internal val LANGUAGE_TAGS = listOf("en", "es", "fr", "de", "it", "pt")
internal val APP_LANGUAGE_TAGS = listOf("en", "es")

internal fun langName(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).ifBlank { tag }

private fun missingPlaceholders(prompt: String): List<String> =
    PromptBuilder.CORE_PLACEHOLDERS.filter { !prompt.contains(it) }

/** The settings tabs, in display order. Language is the default landing tab. */
enum class SettingsTab { LANGUAGE, MODELS, APPEARANCE }

/**
 * The settings screen, organised into [SettingsTab]s. Language is the default; the chat's "model
 * settings" shortcut opens this same screen on the Models tab (with a back arrow) via [initialTab] +
 * [onBack], replacing the old one-off model sub-screen.
 */
@Composable
fun SettingsScreen(
    initialTab: SettingsTab = SettingsTab.LANGUAGE,
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val keys by viewModel.keys.collectAsState()
    val ttsState by viewModel.ttsModelState.collectAsState()
    val previewing by viewModel.previewing.collectAsState()
    val voiceCount by viewModel.ttsVoiceCount.collectAsState()
    val profiles by viewModel.profiles.collectAsState()
    val activeProfile by viewModel.activeProfile.collectAsState()

    var tab by remember { mutableStateOf(initialTab) }
    var showAddLanguage by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.settings_title), onBack = onBack)

        ProfileSelectorRow(
            profiles = profiles,
            active = activeProfile,
            onSwitch = viewModel::switchProfile,
            onAddLanguage = { showAddLanguage = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 12.dp),
        )

        if (showAddLanguage) {
            AddLanguageDialog(
                existing = profiles,
                onAdd = { tag ->
                    showAddLanguage = false
                    viewModel.addProfile(tag)
                },
                onDismiss = { showAddLanguage = false },
            )
        }

        TabRow(selectedTabIndex = tab.ordinal) {
            SettingsTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(stringResource(t.labelRes())) },
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (tab) {
                SettingsTab.LANGUAGE -> LanguageTab(settings, viewModel)
                SettingsTab.MODELS -> ModelsTab(settings, keys, ttsState, voiceCount, previewing, viewModel)
                SettingsTab.APPEARANCE -> AppearanceTab(settings, viewModel)
            }
        }
    }
}

private fun SettingsTab.labelRes(): Int = when (this) {
    SettingsTab.LANGUAGE -> R.string.settings_language_section
    SettingsTab.MODELS -> R.string.settings_model_title
    SettingsTab.APPEARANCE -> R.string.settings_appearance_section
}

// ---------------------------------------------------------------------------
// Language profiles
// ---------------------------------------------------------------------------

/** Sentinel dropdown entry that opens the add-language dialog instead of switching. */
private const val ADD_LANGUAGE = "+"

/**
 * The profile switcher: a dropdown of the user's language profiles with a pinned
 * "Add language…" entry. Sits above the tabs so it's visible from all of them.
 */
@Composable
private fun ProfileSelectorRow(
    profiles: List<String>,
    active: String,
    onSwitch: (String) -> Unit,
    onAddLanguage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val canAdd = LANGUAGE_TAGS.any { it !in profiles }
    val addLabel = stringResource(R.string.settings_add_language)
    LabeledDropdown(
        label = stringResource(R.string.settings_target_lang),
        options = if (canAdd) profiles + ADD_LANGUAGE else profiles,
        selected = active,
        optionLabel = { tag -> if (tag == ADD_LANGUAGE) addLabel else langName(tag) },
        onSelect = { tag -> if (tag == ADD_LANGUAGE) onAddLanguage() else onSwitch(tag) },
        modifier = modifier,
    )
}

/** Picks a language that doesn't have a profile yet and creates one for it. */
@Composable
private fun AddLanguageDialog(
    existing: List<String>,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val available = LANGUAGE_TAGS.filter { it !in existing }
    if (available.isEmpty()) {
        onDismiss()
        return
    }
    var chosen by remember { mutableStateOf(available.first()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_add_language_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.settings_add_language_hint))
                LabeledDropdown(
                    label = stringResource(R.string.settings_target_lang),
                    options = available,
                    selected = chosen,
                    optionLabel = { langName(it) },
                    onSelect = { chosen = it },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(chosen) }) { Text(stringResource(R.string.settings_add_language_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

// ---------------------------------------------------------------------------
// Language tab
// ---------------------------------------------------------------------------

@Composable
private fun LanguageTab(settings: AppSettings, viewModel: SettingsViewModel) {
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

// ---------------------------------------------------------------------------
// Appearance tab
// ---------------------------------------------------------------------------

@Composable
private fun AppearanceTab(settings: AppSettings, viewModel: SettingsViewModel) {
    SectionLabel(stringResource(R.string.settings_theme))
    SegmentedToggle(
        selected = settings.themeMode.ordinal,
        labels = listOf(
            stringResource(R.string.theme_system),
            stringResource(R.string.theme_light),
            stringResource(R.string.theme_dark),
        ),
        onSelect = { idx -> viewModel.update { it.copy(themeMode = ThemeMode.entries[idx]) } },
    )

    SectionLabel(stringResource(R.string.settings_persona), modifier = Modifier.padding(top = 8.dp))
    PersonaPicker(
        selected = settings.persona,
        onSelect = { p -> viewModel.update { it.copy(persona = p) } },
    )

    SectionLabel(stringResource(R.string.settings_theme_country), modifier = Modifier.padding(top = 8.dp))
    ThemeCountryPicker(
        selected = settings.themeCountry,
        onSelect = { c -> viewModel.update { it.copy(themeCountry = c) } },
    )

    SectionLabel(stringResource(R.string.settings_reading_font), modifier = Modifier.padding(top = 8.dp))
    ReadingFontPicker(
        selected = settings.readingFont,
        onSelect = { font -> viewModel.update { it.copy(readingFont = font) } },
    )

    SectionLabel(stringResource(R.string.settings_text_size), modifier = Modifier.padding(top = 8.dp))
    ReadingSizePicker(
        font = settings.readingFont,
        selected = settings.readingTextSize,
        onSelect = { size -> viewModel.update { it.copy(readingTextSize = size) } },
    )
}

// ---------------------------------------------------------------------------
// Models tab (provider + key + model + prompts + speech)
// ---------------------------------------------------------------------------

@Composable
private fun ModelsTab(
    settings: AppSettings,
    keys: KeyPresence,
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
                onUse = { key, model ->
                    if (key.isNotBlank()) viewModel.setApiKey(selected, key)
                    viewModel.update {
                        when (selected) {
                            ProviderId.GEMINI -> it.copy(provider = ProviderId.GEMINI, geminiModel = model)
                            ProviderId.OPENROUTER ->
                                it.copy(provider = ProviderId.OPENROUTER, openRouterModel = model.trim())
                        }
                    }
                },
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
 * Editor for one provider's API key + model. The key field and model control are staged locally; the
 * "Use …" button persists them and makes this the active provider in one step (so there's no separate
 * "Save", and it's clear which provider the app is actually using).
 */
@Composable
private fun ProviderConfig(
    provider: ProviderId,
    isActive: Boolean,
    keySet: Boolean,
    initialKey: String,
    geminiModel: String,
    openRouterModel: String,
    onUse: (key: String, model: String) -> Unit,
) {
    var keyText by remember(provider, initialKey) { mutableStateOf(initialKey) }
    var keyVisible by remember(provider) { mutableStateOf(false) }
    var modelText by remember(provider, openRouterModel) { mutableStateOf(openRouterModel) }
    var geminiSelected by remember(provider, geminiModel) { mutableStateOf(geminiModel) }

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
        ProviderId.OPENROUTER -> OutlinedTextField(
            value = modelText,
            onValueChange = { modelText = it },
            label = { Text(stringResource(R.string.settings_openrouter_model)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val model = if (provider == ProviderId.GEMINI) geminiSelected else modelText.trim()
    val keyChanged = keyText != initialKey
    val modelChanged = model != (if (provider == ProviderId.GEMINI) geminiModel else openRouterModel)
    // Enabled when switching provider, or applying edits to the already-active one.
    val canApply = !isActive || keyChanged || modelChanged

    Button(
        onClick = { onUse(keyText, model) },
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
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
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
private val AUTO_DELETE_OPTIONS = listOf(0, 7, 30, 90)

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

private val READING_FONTS = listOf(
    ReadingFont.SERIF to R.string.font_serif,
    ReadingFont.SLAB to R.string.font_slab,
    ReadingFont.SANS to R.string.font_sans,
)

private val READING_SIZES = listOf(
    ReadingTextSize.SMALL to R.string.size_small,
    ReadingTextSize.MEDIUM to R.string.size_medium,
    ReadingTextSize.LARGE to R.string.size_large,
    ReadingTextSize.XLARGE to R.string.size_xlarge,
)

/** Stacked font choices, each previewed in its own typeface with a sample line. */
@Composable
private fun ReadingFontPicker(selected: ReadingFont, onSelect: (ReadingFont) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        READING_FONTS.forEach { (font, labelRes) ->
            ReadingFontRow(
                family = readingFontFamily(font),
                label = stringResource(labelRes),
                selected = font == selected,
                onClick = { onSelect(font) },
            )
        }
    }
}

@Composable
private fun ReadingFontRow(
    family: FontFamily,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, fontFamily = family, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.reading_sample),
                    fontFamily = family,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** Side-by-side size chips, each rendering "Aa" at the size it selects. */
@Composable
private fun ReadingSizePicker(
    font: ReadingFont,
    selected: ReadingTextSize,
    onSelect: (ReadingTextSize) -> Unit,
) {
    val family = readingFontFamily(font)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        READING_SIZES.forEach { (size, labelRes) ->
            val sel = size == selected
            Surface(
                onClick = { onSelect(size) },
                shape = RoundedCornerShape(12.dp),
                color = if (sel) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = if (sel) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                modifier = Modifier.weight(1f),
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        "Aa",
                        fontFamily = family,
                        fontSize = (18 * readingScale(size)).sp,
                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(labelRes),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sel) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
