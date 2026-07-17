package com.copiosa.ui.theme.skin

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import com.copiosa.domain.model.Persona

/** Decorations for saved-word highlights that SpanStyle can't express; drawn over the text layout. */
enum class FocusDecoration {
    NONE,
    /** Artist: wavy underline. */
    WAVY_UNDERLINE,
    /** Explorer: dashed field-note box. */
    DASHED_BOX,
    /** Chef: outlined comanda pill. */
    OUTLINED_PILL,
}

/**
 * How the active skin marks saved vocabulary in a reply: a span-level style (color, background,
 * underline) plus an optional drawn [decoration] in [decorationColor].
 */
@Immutable
data class FocusWordStyle(
    val span: SpanStyle,
    val decoration: FocusDecoration = FocusDecoration.NONE,
    val decorationColor: Color = Color.Unspecified,
)

/** The persona's saved-word treatment, tinted by the country palette. Unstyled keeps the stock tint. */
fun focusWordStyle(skin: AppSkin): FocusWordStyle {
    val palette = skin.palette
    return when (skin.persona) {
        // The stock look: saved words tinted the theme primary (== palette accent).
        Persona.UNSTYLED -> FocusWordStyle(SpanStyle(color = palette.accent))
        // Marginalia wash: accent-2 highlight with a firm underline.
        Persona.PROFESSOR -> FocusWordStyle(
            SpanStyle(
                background = palette.accent2.copy(alpha = 0.25f),
                textDecoration = TextDecoration.Underline,
            ),
        )
        // Comanda pill: outlined in accent-2, drawn around the word.
        Persona.CHEF -> FocusWordStyle(
            SpanStyle(fontWeight = FontWeight.Medium),
            decoration = FocusDecoration.OUTLINED_PILL,
            decorationColor = palette.accent2,
        )
        // A verse aside: italic, colored, underlined.
        Persona.POET -> FocusWordStyle(
            SpanStyle(
                color = palette.accent2,
                fontStyle = FontStyle.Italic,
                textDecoration = TextDecoration.Underline,
            ),
        )
        // Sketch mark: bold with a wavy accent-2 underline.
        Persona.ARTIST -> FocusWordStyle(
            SpanStyle(fontWeight = FontWeight.SemiBold),
            decoration = FocusDecoration.WAVY_UNDERLINE,
            decorationColor = palette.accent2,
        )
        // Timetable highlight pill: soft accent fill.
        Persona.COMMUTER -> FocusWordStyle(
            SpanStyle(
                background = palette.accentSoft,
                color = palette.accentSoftInk,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        // Field note: dashed accent-2 box around the word.
        Persona.EXPLORER -> FocusWordStyle(
            SpanStyle(fontWeight = FontWeight.Medium),
            decoration = FocusDecoration.DASHED_BOX,
            decorationColor = palette.accent2,
        )
    }
}
