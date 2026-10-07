package com.copiosa.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.copiosa.R
import com.copiosa.data.settings.AppSettings
import com.copiosa.domain.model.ReadingFont
import com.copiosa.domain.model.ReadingTextSize
import com.copiosa.domain.model.ThemeMode
import com.copiosa.ui.components.SectionLabel
import com.copiosa.ui.components.SegmentedToggle
import com.copiosa.ui.theme.readingFontFamily
import com.copiosa.ui.theme.readingScale

@Composable
internal fun AppearanceTab(settings: AppSettings, viewModel: SettingsViewModel) {
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

    SectionLabel(stringResource(R.string.settings_reading), modifier = Modifier.padding(top = 8.dp))
    SwitchRow(
        title = stringResource(R.string.settings_highlight_words),
        subtitle = stringResource(R.string.settings_highlight_words_explainer),
        checked = settings.highlightSavedWords,
        onCheckedChange = { on -> viewModel.update { it.copy(highlightSavedWords = on) } },
    )
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
