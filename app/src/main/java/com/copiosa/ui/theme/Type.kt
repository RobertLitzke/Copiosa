package com.copiosa.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.copiosa.R
import com.copiosa.domain.model.ReadingFont
import com.copiosa.domain.model.ReadingTextSize

/**
 * App chrome font: Plus Jakarta Sans. Bundled as a single variable TTF (the `wght` axis); each
 * [Font] entry derives its weight via the default variation settings, which take effect on API 26+
 * (the app's minSdk).
 */
val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans, FontWeight.Bold),
)

/** Reading font: Source Serif 4, used for chat replies, definitions, and article text. */
val SourceSerif = FontFamily(
    Font(R.font.source_serif, FontWeight.Normal),
    Font(R.font.source_serif, FontWeight.Medium),
    Font(R.font.source_serif, FontWeight.SemiBold),
    Font(R.font.source_serif, FontWeight.Bold),
    Font(R.font.source_serif_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.source_serif_italic, FontWeight.SemiBold, FontStyle.Italic),
)

/** Alternate reading font: Literata, a warm slab-ish serif (bundled OFL variable TTF). */
val Literata = FontFamily(
    Font(R.font.literata, FontWeight.Normal),
    Font(R.font.literata, FontWeight.Medium),
    Font(R.font.literata, FontWeight.SemiBold),
    Font(R.font.literata, FontWeight.Bold),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_italic, FontWeight.SemiBold, FontStyle.Italic),
)

/** Resolves the [ReadingFont] preference to a concrete [FontFamily]. */
fun readingFontFamily(font: ReadingFont): FontFamily = when (font) {
    ReadingFont.SERIF -> SourceSerif
    ReadingFont.SLAB -> Literata
    ReadingFont.SANS -> PlusJakartaSans
}

/** Multiplier applied to reading font sizes for the [ReadingTextSize] preference. */
fun readingScale(size: ReadingTextSize): Float = when (size) {
    ReadingTextSize.SMALL -> 0.9f
    ReadingTextSize.MEDIUM -> 1f
    ReadingTextSize.LARGE -> 1.15f
    ReadingTextSize.XLARGE -> 1.3f
}

/**
 * The reading typeface + size scale currently in effect, provided by [CopiosaTheme] from
 * settings and read by the reading-text composables (chat replies, definitions, Markdown).
 */
data class ReadingStyle(val fontFamily: FontFamily, val scale: Float)

val LocalReadingStyle = staticCompositionLocalOf { ReadingStyle(SourceSerif, 1f) }

/** Material typography with [font] applied across every role. */
fun appTypography(font: FontFamily): Typography {
    val default = Typography()
    return Typography(
        displayLarge = default.displayLarge.copy(fontFamily = font),
        displayMedium = default.displayMedium.copy(fontFamily = font),
        displaySmall = default.displaySmall.copy(fontFamily = font),
        headlineLarge = default.headlineLarge.copy(fontFamily = font),
        headlineMedium = default.headlineMedium.copy(fontFamily = font),
        headlineSmall = default.headlineSmall.copy(fontFamily = font),
        titleLarge = default.titleLarge.copy(fontFamily = font),
        titleMedium = default.titleMedium.copy(fontFamily = font),
        titleSmall = default.titleSmall.copy(fontFamily = font),
        bodyLarge = default.bodyLarge.copy(fontFamily = font),
        bodyMedium = default.bodyMedium.copy(fontFamily = font),
        bodySmall = default.bodySmall.copy(fontFamily = font),
        labelLarge = default.labelLarge.copy(fontFamily = font),
        labelMedium = default.labelMedium.copy(fontFamily = font),
        labelSmall = default.labelSmall.copy(fontFamily = font),
    )
}

/** Material typography with Plus Jakarta Sans (the stock chrome font) across every role. */
val AppTypography = appTypography(PlusJakartaSans)

/** Serif reading styles, used for assistant replies, the definition card, and article body. */
val SerifBody = TextStyle(
    fontFamily = SourceSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 17.sp,
    lineHeight = 26.sp,
)
val SerifBodyLarge = TextStyle(
    fontFamily = SourceSerif,
    fontWeight = FontWeight.Normal,
    fontSize = 19.sp,
    lineHeight = 28.sp,
)
val SerifTitle = TextStyle(
    fontFamily = SourceSerif,
    fontWeight = FontWeight.SemiBold,
    fontSize = 20.sp,
    lineHeight = 28.sp,
)
