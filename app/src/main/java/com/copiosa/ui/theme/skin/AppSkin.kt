package com.copiosa.ui.theme.skin

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.copiosa.domain.model.Persona
import com.copiosa.domain.model.ThemeCountry

/**
 * The resolved persona × country skin in effect, provided by CopiosaTheme for every mode —
 * including fully unstyled — so consumers never null-check the local itself. [palette] always
 * holds usable tokens: the country's when one is selected, otherwise the stock forest/cream
 * palette mapped into token shape (so persona chrome renders sensibly without a country).
 */
@Immutable
data class AppSkin(
    val persona: Persona,
    val country: ThemeCountry,
    val dark: Boolean,
    val palette: CountryPalette,
    /** Country theme data, or null when [country] is NONE. */
    val countryTheme: CountryTheme?,
    /** Persona spec, or null when [persona] is UNSTYLED. */
    val personaSpec: PersonaSpec?,
    /** Language of themed copy: the country's language, else the active profile's (if covered). */
    val languageTag: String,
) {
    val motif: Motif? get() = countryTheme?.motif

    /** Whether the chat screens take the full persona treatment. */
    val isThemedChat: Boolean get() = personaSpec != null

    /** The persona's localized copy for [languageTag], or null when unstyled. */
    val personaStrings: PersonaStrings? get() = personaSpec?.i18n?.get(languageTag)

    companion object {
        /** Languages the persona copy covers; other profiles fall back to Spanish copy. */
        private val THEME_LANGUAGES = setOf("es", "pt", "de")

        fun unstyled(dark: Boolean): AppSkin = resolve(Persona.UNSTYLED, ThemeCountry.NONE, dark, "es")

        fun resolve(persona: Persona, country: ThemeCountry, dark: Boolean, profileLanguage: String): AppSkin {
            val theme = countryTheme(country)
            val palette = theme?.let { if (dark) it.dark else it.light }
                ?: if (dark) StockDark else StockLight
            return AppSkin(
                persona = persona,
                country = country,
                dark = dark,
                palette = palette,
                countryTheme = theme,
                personaSpec = personaSpec(persona),
                languageTag = theme?.languageTag
                    ?: profileLanguage.takeIf { it in THEME_LANGUAGES } ?: "es",
            )
        }
    }
}

val LocalAppSkin = staticCompositionLocalOf { AppSkin.unstyled(dark = false) }

// The stock forest/cream scheme (Theme.kt) expressed as country tokens, used when no theme
// country is selected so persona chrome still has a full token set to draw from.

private val StockLight = CountryPalette(
    bg = Color(0xFFF5F3EC),
    surface = Color(0xFFFBFAF6),
    surface2 = Color(0xFFE8E4DA),
    line = Color(0xFFD9D4C8),
    text = Color(0xFF2B2A26),
    textDim = Color(0xFF55504A),
    textMute = Color(0xFF8A857B),
    accent = Color(0xFF2E7D5B),
    accentInk = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFCFE8D8),
    accentSoftInk = Color(0xFF14432C),
    accent2 = Color(0xFFB5651D),
    accent2Ink = Color(0xFFFFFFFF),
    shadow = Color(0x14000000),
)

private val StockDark = CountryPalette(
    bg = Color(0xFF1A1916),
    surface = Color(0xFF232220),
    surface2 = Color(0xFF3A3833),
    line = Color(0xFF49453E),
    text = Color(0xFFECE7DD),
    textDim = Color(0xFFC9C3B7),
    textMute = Color(0xFF8F887C),
    accent = Color(0xFF8FD3AC),
    accentInk = Color(0xFF06301C),
    accentSoft = Color(0xFF2E4A3B),
    accentSoftInk = Color(0xFFCFE8D8),
    accent2 = Color(0xFFE0A872),
    accent2Ink = Color(0xFF33200A),
    shadow = Color(0x66000000),
)
