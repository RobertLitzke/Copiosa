package com.copiosa.ui.theme.skin

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import com.copiosa.domain.model.ThemeCountry

/** Decorative motif drawn in the top band, the strip above the bottom nav, and the tab indicator. */
enum class Motif {
    /** Spain — red azulejo scallops on gold. */
    SCALLOPS,
    /** Portugal — blue/white azulejo checkerboard. */
    CHECKERBOARD,
    /** Mexico — papel picado: green scallops + white dots on a red string. */
    PAPEL_PICADO,
    /** Germany — diagonal black/red/gold tricolor. */
    DIAGONAL_STRIPES,
    /** Brazil — Copacabana wave in blue/green on gold. */
    WAVES,
}

/**
 * Localized UI labels a country supplies. The Talk tab label is normally overridden by the
 * persona's own `talkTab`; [talk] is the country fallback for the unstyled persona.
 */
@Immutable
data class CountryStrings(
    val talk: String,
    val texts: String,
    val lexicon: String,
    val audio: String,
    val routines: String,
    val settings: String,
    /** Professor signature-band prefix, e.g. "Cuaderno de tertulia". */
    val notes: String,
    /** Artist signature-band prefix, e.g. "Estudio abierto". */
    val studio: String,
    /** Word for "level", e.g. "Nivel" / "Niveau". */
    val level: String,
)

/** Everything the country axis contributes: palettes, motif, tutor identity, localized labels. */
@Immutable
data class CountryTheme(
    val country: ThemeCountry,
    val light: CountryPalette,
    val dark: CountryPalette,
    val motif: Motif,
    /** Tutor display name shown in the chat header and message kickers. */
    val tutorName: String,
    /** Language of all themed copy: "es", "pt", or "de". */
    val languageTag: String,
    /** Localized language label for pickers/headers, e.g. "Español · México". */
    val languageLabel: String,
    /** Place label shown under the tutor name, e.g. "Deutschland". */
    val place: String,
    /** Map coordinates for the Explorer signature band, e.g. "19.4°N · 99.1°W". */
    val coords: String,
    val strings: CountryStrings,
    /** Flag stripe colors for picker previews and avatars (2–3 stops, flag order). */
    val flagColors: List<Color>,
)

/**
 * Country-style UI strings for a persona running without a theme country, per language, so
 * signature bands still read naturally. Coordinates/place stay absent — those are country facts.
 */
fun fallbackCountryStrings(languageTag: String): CountryStrings = when (languageTag) {
    "pt" -> CountryStrings(
        talk = "Conversas", texts = "Textos", lexicon = "Léxico",
        audio = "Áudio", routines = "Rotinas", settings = "Ajustes",
        notes = "Caderno de leitura", studio = "Ateliê aberto", level = "Nível",
    )
    "de" -> CountryStrings(
        talk = "Gespräche", texts = "Texte", lexicon = "Lexikon",
        audio = "Audio", routines = "Routinen", settings = "Einstellungen",
        notes = "Aus dem Lesesaal", studio = "Offenes Atelier", level = "Niveau",
    )
    else -> CountryStrings(
        talk = "Charlas", texts = "Textos", lexicon = "Léxico",
        audio = "Audio", routines = "Rutinas", settings = "Ajustes",
        notes = "Cuaderno de tertulia", studio = "Estudio abierto", level = "Nivel",
    )
}

/** Resolves a [ThemeCountry] to its theme, or null for [ThemeCountry.NONE] (stock palette). */
fun countryTheme(country: ThemeCountry): CountryTheme? = when (country) {
    ThemeCountry.NONE -> null
    ThemeCountry.SPAIN -> Spain
    ThemeCountry.PORTUGAL -> Portugal
    ThemeCountry.MEXICO -> Mexico
    ThemeCountry.GERMANY -> Germany
    ThemeCountry.BRAZIL -> Brazil
}

// Palette hex values are generated from the design spec's oklch tokens by
// tools/oklch_to_compose.py (hue-preserving gamut mapping) — regenerate there, don't hand-edit.

private val SpainLight = CountryPalette(
    bg = Color(0xFFFCF6EB),
    surface = Color(0xFFFFFDFA),
    surface2 = Color(0xFFF6EDE0),
    line = Color(0xFFE7DCCE),
    text = Color(0xFF382620),
    textDim = Color(0xFF6A564D),
    textMute = Color(0xFF91837B),
    accent = Color(0xFFC51D24),
    accentInk = Color(0xFFFFFBF5),
    accentSoft = Color(0xFFFFD98B),
    accentSoftInk = Color(0xFF8D3600),
    accent2 = Color(0xFFD7A10C),
    accent2Ink = Color(0xFF472400),
    shadow = Color(0x14783214),
)

private val SpainDark = CountryPalette(
    bg = Color(0xFF211310),
    surface = Color(0xFF2C1D19),
    surface2 = Color(0xFF392723),
    line = Color(0xFF473632),
    text = Color(0xFFF2EAE0),
    textDim = Color(0xFFBAAFA6),
    textMute = Color(0xFF8B817A),
    accent = Color(0xFFED4C3A),
    accentInk = Color(0xFF120805),
    accentSoft = Color(0xFF6B3900),
    accentSoftInk = Color(0xFFFFD98B),
    accent2 = Color(0xFFE8B631),
    accent2Ink = Color(0xFF341600),
    shadow = Color(0x66000000),
)

private val PortugalLight = CountryPalette(
    bg = Color(0xFFF3F8EF),
    surface = Color(0xFFFCFEFB),
    surface2 = Color(0xFFEBF1E5),
    line = Color(0xFFDBE0D4),
    text = Color(0xFF1E2A20),
    textDim = Color(0xFF4E5C51),
    textMute = Color(0xFF81897F),
    accent = Color(0xFF006A3C),
    accentInk = Color(0xFFF7FEF8),
    accentSoft = Color(0xFFFFD6AA),
    accentSoftInk = Color(0xFF8E2E00),
    accent2 = Color(0xFFC51E21),
    accent2Ink = Color(0xFFFFFBF9),
    shadow = Color(0x1214461E),
)

private val PortugalDark = CountryPalette(
    bg = Color(0xFF0F1912),
    surface = Color(0xFF19241B),
    surface2 = Color(0xFF232F25),
    line = Color(0xFF333E35),
    text = Color(0xFFE4EAE2),
    textDim = Color(0xFFA6ADA5),
    textMute = Color(0xFF7B837B),
    accent = Color(0xFF29975E),
    accentInk = Color(0xFF040C06),
    accentSoft = Color(0xFF663305),
    accentSoftInk = Color(0xFFFFCD9B),
    accent2 = Color(0xFFED4B40),
    accent2Ink = Color(0xFF100605),
    shadow = Color(0x61000000),
)

private val MexicoLight = CountryPalette(
    bg = Color(0xFFF4F8F1),
    surface = Color(0xFFFDFEFC),
    surface2 = Color(0xFFEBF0E8),
    line = Color(0xFFDBE0D7),
    text = Color(0xFF212922),
    textDim = Color(0xFF555D56),
    textMute = Color(0xFF858B84),
    accent = Color(0xFF007835),
    accentInk = Color(0xFFF6FEF8),
    accentSoft = Color(0xFFCCF4D3),
    accentSoftInk = Color(0xFF114C24),
    accent2 = Color(0xFFCC2827),
    accent2Ink = Color(0xFFFFFBF9),
    shadow = Color(0x1214462D),
)

private val MexicoDark = CountryPalette(
    bg = Color(0xFF141815),
    surface = Color(0xFF1D221E),
    surface2 = Color(0xFF272D28),
    line = Color(0xFF353B36),
    text = Color(0xFFE6E9E2),
    textDim = Color(0xFFA8ACA5),
    textMute = Color(0xFF7D827B),
    accent = Color(0xFF32A155),
    accentInk = Color(0xFF071009),
    accentSoft = Color(0xFF1D4128),
    accentSoftInk = Color(0xFFA8EBB5),
    accent2 = Color(0xFFED4B40),
    accent2Ink = Color(0xFF100605),
    shadow = Color(0x5C000000),
)

private val GermanyLight = CountryPalette(
    bg = Color(0xFFF5F7F9),
    surface = Color(0xFFFEFFFF),
    surface2 = Color(0xFFEDEFF2),
    line = Color(0xFFDBDEE2),
    text = Color(0xFF181B1E),
    textDim = Color(0xFF4F5358),
    textMute = Color(0xFF808388),
    accent = Color(0xFFC91114),
    accentInk = Color(0xFFFEFCF6),
    accentSoft = Color(0xFFF6D78C),
    accentSoftInk = Color(0xFF673C00),
    accent2 = Color(0xFFD5A209),
    accent2Ink = Color(0xFF331B06),
    shadow = Color(0x1A0F0F19),
)

private val GermanyDark = CountryPalette(
    bg = Color(0xFF111315),
    surface = Color(0xFF1B1D21),
    surface2 = Color(0xFF26292C),
    line = Color(0xFF35383C),
    text = Color(0xFFECEFF2),
    textDim = Color(0xFFAEB1B5),
    textMute = Color(0xFF7D8084),
    accent = Color(0xFFED4138),
    accentInk = Color(0xFF100704),
    accentSoft = Color(0xFF523B0A),
    accentSoftInk = Color(0xFFF3D489),
    accent2 = Color(0xFFE6B731),
    accent2Ink = Color(0xFF2B1401),
    shadow = Color(0x6B000000),
)

private val BrazilLight = CountryPalette(
    bg = Color(0xFFF3F9F1),
    surface = Color(0xFFFCFFFB),
    surface2 = Color(0xFFEAF1E7),
    line = Color(0xFFDAE0D6),
    text = Color(0xFF182230),
    textDim = Color(0xFF485463),
    textMute = Color(0xFF748389),
    accent = Color(0xFF008542),
    accentInk = Color(0xFFF6FEF8),
    accentSoft = Color(0xFFF3DB65),
    accentSoftInk = Color(0xFF004395),
    accent2 = Color(0xFF0049B3),
    accent2Ink = Color(0xFFF9FCFF),
    shadow = Color(0x14143C5A),
)

private val BrazilDark = CountryPalette(
    bg = Color(0xFF0F1621),
    surface = Color(0xFF19212D),
    surface2 = Color(0xFF232C39),
    line = Color(0xFF333B47),
    text = Color(0xFFE5ECF3),
    textDim = Color(0xFFA7AFB8),
    textMute = Color(0xFF7B858C),
    accent = Color(0xFF00AF59),
    accentInk = Color(0xFF040B06),
    accentSoft = Color(0xFF5A4D00),
    accentSoftInk = Color(0xFFF2E075),
    accent2 = Color(0xFF427FF7),
    accent2Ink = Color(0xFFF9FCFF),
    shadow = Color(0x66000000),
)

private val Spain = CountryTheme(
    country = ThemeCountry.SPAIN,
    light = SpainLight,
    dark = SpainDark,
    motif = Motif.SCALLOPS,
    tutorName = "Martina",
    languageTag = "es",
    languageLabel = "Español · España",
    place = "España",
    coords = "40.4°N · 3.7°W",
    strings = CountryStrings(
        talk = "Charlas", texts = "Textos", lexicon = "Léxico",
        audio = "Audio", routines = "Rutinas", settings = "Ajustes",
        notes = "Cuaderno de tertulia", studio = "Estudio abierto", level = "Nivel",
    ),
    flagColors = listOf(Color(0xFFC60B1E), Color(0xFFFFC400), Color(0xFFC60B1E)),
)

private val Portugal = CountryTheme(
    country = ThemeCountry.PORTUGAL,
    light = PortugalLight,
    dark = PortugalDark,
    motif = Motif.CHECKERBOARD,
    tutorName = "Inês",
    languageTag = "pt",
    languageLabel = "Português · Portugal",
    place = "Portugal",
    coords = "38.7°N · 9.1°W",
    strings = CountryStrings(
        talk = "Conversas", texts = "Textos", lexicon = "Léxico",
        audio = "Áudio", routines = "Rotinas", settings = "Definições",
        notes = "Caderno de tertúlia", studio = "Estúdio aberto", level = "Nível",
    ),
    flagColors = listOf(Color(0xFF006600), Color(0xFFFF0000)),
)

private val Mexico = CountryTheme(
    country = ThemeCountry.MEXICO,
    light = MexicoLight,
    dark = MexicoDark,
    motif = Motif.PAPEL_PICADO,
    tutorName = "Lucía",
    languageTag = "es",
    languageLabel = "Español · México",
    place = "México",
    coords = "19.4°N · 99.1°W",
    strings = CountryStrings(
        talk = "Charlas", texts = "Textos", lexicon = "Léxico",
        audio = "Audio", routines = "Rutinas", settings = "Ajustes",
        notes = "Cuaderno de tertulia", studio = "Estudio abierto", level = "Nivel",
    ),
    flagColors = listOf(Color(0xFF006847), Color(0xFFFFFFFF), Color(0xFFCE1126)),
)

private val Germany = CountryTheme(
    country = ThemeCountry.GERMANY,
    light = GermanyLight,
    dark = GermanyDark,
    motif = Motif.DIAGONAL_STRIPES,
    tutorName = "Jonas",
    languageTag = "de",
    languageLabel = "Deutsch · Deutschland",
    place = "Deutschland",
    coords = "52.5°N · 13.4°E",
    strings = CountryStrings(
        talk = "Gespräche", texts = "Texte", lexicon = "Lexikon",
        audio = "Audio", routines = "Routinen", settings = "Einstellungen",
        notes = "Aus dem Lesesaal", studio = "Offenes Atelier", level = "Niveau",
    ),
    flagColors = listOf(Color(0xFF000000), Color(0xFFDD0000), Color(0xFFFFCE00)),
)

private val Brazil = CountryTheme(
    country = ThemeCountry.BRAZIL,
    light = BrazilLight,
    dark = BrazilDark,
    motif = Motif.WAVES,
    tutorName = "Beatriz",
    languageTag = "pt",
    languageLabel = "Português · Brasil",
    place = "Brasil",
    coords = "22.9°S · 43.2°W",
    strings = CountryStrings(
        talk = "Conversas", texts = "Textos", lexicon = "Léxico",
        audio = "Áudio", routines = "Rotinas", settings = "Ajustes",
        notes = "Caderno de leitura", studio = "Ateliê aberto", level = "Nível",
    ),
    flagColors = listOf(Color(0xFF009C3B), Color(0xFFFFDF00), Color(0xFF0033A0)),
)
