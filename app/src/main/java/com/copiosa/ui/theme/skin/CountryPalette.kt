package com.copiosa.ui.theme.skin

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Full color token set a theme country supplies for one mode (light or dark). Personas only ever
 * reference these tokens (never hardcoded color), which keeps every persona × country combination
 * working without combinatorial styling.
 */
@Immutable
data class CountryPalette(
    /** Screen background. */
    val bg: Color,
    /** Cards, headers, input bar. */
    val surface: Color,
    /** Insets, secondary fills. */
    val surface2: Color,
    /** Borders, dividers. */
    val line: Color,
    /** Primary text. */
    val text: Color,
    /** Secondary text (e.g. the learner's own messages). */
    val textDim: Color,
    /** Tertiary text: timestamps, placeholders. */
    val textMute: Color,
    /** Primary brand accent. */
    val accent: Color,
    /** Text on [accent]. */
    val accentInk: Color,
    /** Soft accent fill. */
    val accentSoft: Color,
    /** Text on [accentSoft]. */
    val accentSoftInk: Color,
    /** Secondary accent: focus-word highlight, status dot. */
    val accent2: Color,
    /** Text on [accent2]. */
    val accent2Ink: Color,
    /** Elevation shadow tint. */
    val shadow: Color,
    /**
     * Soft paragraph-correspondence tints for the book reader, cycled by paragraph ord. Two core
     * hues with three variations each, interleaved so consecutive paragraphs alternate cores —
     * repetition is the point: a handful of related washes reads as one palette, where six
     * independent hues would read as a rainbow. Opaque fills painted *under* body text, so they
     * sit within a few percent of [bg] in lightness. Deliberately their own family rather than
     * shades of [accent]/[accent2] — those are UI colors, and a paragraph wearing one would read
     * as a highlight rather than as a correspondence marker.
     */
    val tints: List<Color>,
)
