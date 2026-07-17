package com.copiosa.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.copiosa.R
import com.copiosa.domain.model.Persona
import com.copiosa.domain.model.ThemeCountry
import com.copiosa.ui.theme.skin.CountryTheme
import com.copiosa.ui.theme.skin.countryTheme
import com.copiosa.ui.theme.skin.personaSpec

/** English display name for a theme country; picker chrome stays in the app language. */
internal fun themeCountryName(country: ThemeCountry): String = when (country) {
    ThemeCountry.NONE -> ""
    ThemeCountry.SPAIN -> "Spain"
    ThemeCountry.PORTUGAL -> "Portugal"
    ThemeCountry.MEXICO -> "Mexico"
    ThemeCountry.GERMANY -> "Germany"
    ThemeCountry.BRAZIL -> "Brazil"
}

/** Stacked persona choices, each titled in the persona's own title font with a one-line blurb. */
@Composable
internal fun PersonaPicker(selected: Persona, onSelect: (Persona) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Persona.entries.forEach { persona ->
            val spec = personaSpec(persona)
            PickerRow(
                title = spec?.displayName ?: stringResource(R.string.theme_persona_unstyled),
                titleFont = spec?.titleFont,
                subtitle = spec?.blurb ?: stringResource(R.string.theme_persona_unstyled_blurb),
                selected = persona == selected,
                onClick = { onSelect(persona) },
            )
        }
    }
}

/** Stacked country choices with a flag swatch, tutor name, and language label. */
@Composable
internal fun ThemeCountryPicker(selected: ThemeCountry, onSelect: (ThemeCountry) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeCountry.entries.forEach { country ->
            val theme = countryTheme(country)
            PickerRow(
                title = if (theme == null) {
                    stringResource(R.string.theme_country_none)
                } else {
                    themeCountryName(country)
                },
                subtitle = theme?.let { stringResource(R.string.theme_country_tutor, it.tutorName, it.languageLabel) }
                    ?: stringResource(R.string.theme_country_none_blurb),
                selected = country == selected,
                onClick = { onSelect(country) },
                leading = theme?.let { { FlagSwatch(it) } },
            )
        }
    }
}

/** Small rounded stripe rendering the country's flag colors. */
@Composable
private fun FlagSwatch(theme: CountryTheme) {
    Box(
        modifier = Modifier
            .width(34.dp)
            .height(24.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(Brush.horizontalGradient(*flagStops(theme).toTypedArray())),
    )
}

/** Hard-stop gradient stops so the flag reads as bands, not a blur. */
private fun flagStops(theme: CountryTheme): List<Pair<Float, androidx.compose.ui.graphics.Color>> {
    val colors = theme.flagColors
    val n = colors.size
    return buildList {
        colors.forEachIndexed { i, c ->
            add(i / n.toFloat() to c)
            add((i + 1) / n.toFloat() to c)
        }
    }
}

/** Shared selectable row chrome, matching the reading-font picker's look. */
@Composable
private fun PickerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    titleFont: FontFamily? = null,
    leading: (@Composable () -> Unit)? = null,
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
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    title,
                    fontFamily = titleFont,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
