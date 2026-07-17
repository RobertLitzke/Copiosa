package com.copiosa.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.copiosa.domain.model.Persona
import com.copiosa.domain.model.ReadingFont
import com.copiosa.domain.model.ReadingTextSize
import com.copiosa.domain.model.ThemeCountry
import com.copiosa.ui.theme.skin.AppSkin
import com.copiosa.ui.theme.skin.CountryPalette
import com.copiosa.ui.theme.skin.LocalAppSkin

// Warm cream paper with a forest-green accent (see design mocks).
private val LightColors = lightColorScheme(
    primary = Color(0xFF2E7D5B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFCFE8D8), // user message bubbles
    onPrimaryContainer = Color(0xFF14432C),
    secondary = Color(0xFF52796A),
    onSecondary = Color(0xFFFFFFFF),
    tertiary = Color(0xFFB5651D),
    background = Color(0xFFF5F3EC),
    onBackground = Color(0xFF2B2A26),
    surface = Color(0xFFFBFAF6),
    onSurface = Color(0xFF2B2A26),
    surfaceVariant = Color(0xFFE8E4DA), // cards, chips, assistant surfaces
    onSurfaceVariant = Color(0xFF55504A),
    outline = Color(0xFFB8B2A6),
    outlineVariant = Color(0xFFD9D4C8),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD3AC),
    onPrimary = Color(0xFF06301C),
    primaryContainer = Color(0xFF2E4A3B), // user message bubbles
    onPrimaryContainer = Color(0xFFCFE8D8),
    secondary = Color(0xFFA7C7B6),
    onSecondary = Color(0xFF10342A),
    tertiary = Color(0xFFE0A872),
    background = Color(0xFF1A1916),
    onBackground = Color(0xFFECE7DD),
    surface = Color(0xFF232220),
    onSurface = Color(0xFFECE7DD),
    surfaceVariant = Color(0xFF3A3833),
    onSurfaceVariant = Color(0xFFC9C3B7),
    outline = Color(0xFF6F695E),
    outlineVariant = Color(0xFF49453E),
)

/**
 * Maps a country's token set onto the Material slots the app's screens actually use, so every
 * screen picks up the palette without knowing about the skin system. Based on the mode defaults
 * so unmapped slots stay sane.
 */
private fun CountryPalette.toColorScheme(dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = accentInk,
        primaryContainer = accentSoft,
        onPrimaryContainer = accentSoftInk,
        secondary = accent2,
        onSecondary = accent2Ink,
        secondaryContainer = accentSoft,
        onSecondaryContainer = accentSoftInk,
        tertiary = accent2,
        onTertiary = accent2Ink,
        tertiaryContainer = accentSoft,
        onTertiaryContainer = accentSoftInk,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surface2,
        onSurfaceVariant = textDim,
        surfaceContainerLowest = surface,
        surfaceContainerLow = lerp(surface, surface2, 0.25f),
        surfaceContainer = lerp(surface, surface2, 0.5f),
        surfaceContainerHigh = lerp(surface, surface2, 0.75f),
        surfaceContainerHighest = surface2,
        outline = textMute,
        outlineVariant = line,
        scrim = shadow,
        inverseSurface = text,
        inverseOnSurface = bg,
    )
}

@Composable
fun CopiosaTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    readingFont: ReadingFont = ReadingFont.SERIF,
    readingTextSize: ReadingTextSize = ReadingTextSize.MEDIUM,
    persona: Persona = Persona.UNSTYLED,
    themeCountry: ThemeCountry = ThemeCountry.NONE,
    profileLanguage: String = "es",
    content: @Composable () -> Unit,
) {
    val reading = ReadingStyle(readingFontFamily(readingFont), readingScale(readingTextSize))
    val skin = remember(persona, themeCountry, darkTheme, profileLanguage) {
        AppSkin.resolve(persona, themeCountry, darkTheme, profileLanguage)
    }
    val colorScheme = when {
        skin.countryTheme != null -> skin.palette.toColorScheme(darkTheme)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val typography = skin.personaSpec?.let { appTypography(it.uiFont) } ?: AppTypography
    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
    ) {
        CompositionLocalProvider(
            LocalReadingStyle provides reading,
            LocalAppSkin provides skin,
            content = content,
        )
    }
}
