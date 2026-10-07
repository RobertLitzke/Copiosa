package com.copiosa.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.copiosa.R
import com.copiosa.ui.components.LabeledDropdown
import com.copiosa.ui.components.ScreenHeader
import java.util.Locale

internal val LANGUAGE_TAGS = listOf("en", "es", "fr", "de", "it", "pt")
internal val APP_LANGUAGE_TAGS = listOf("en", "es")

internal fun langName(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).ifBlank { tag }

/** The settings tabs, in display order. Language is the default landing tab. */
enum class SettingsTab { LANGUAGE, MODELS, APPEARANCE }

/**
 * The settings screen, organised into [SettingsTab]s. Language is the default; the chat's "model
 * settings" shortcut opens this same screen on the Models tab (with a back arrow) via [initialTab] +
 * [onBack], replacing the old one-off model sub-screen. Each tab's content lives in its own file
 * ([LanguageTab], [ModelsTab], [AppearanceTab]); this file owns the scaffold and profile switcher.
 */
@Composable
fun SettingsScreen(
    initialTab: SettingsTab = SettingsTab.LANGUAGE,
    onBack: (() -> Unit)? = null,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val ttsState by viewModel.ttsModelState.collectAsStateWithLifecycle()
    val previewing by viewModel.previewing.collectAsStateWithLifecycle()
    val voiceCount by viewModel.ttsVoiceCount.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfile by viewModel.activeProfile.collectAsStateWithLifecycle()

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
                SettingsTab.MODELS -> ModelsTab(settings, keys, usage, ttsState, voiceCount, previewing, viewModel)
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

/** Title/subtitle row with a trailing switch, shared by the tab files. */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
