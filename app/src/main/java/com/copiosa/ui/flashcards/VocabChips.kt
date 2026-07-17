package com.copiosa.ui.flashcards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copiosa.R

/** Small part-of-speech pill. [pos] is the normalized key the LLM returns (noun/verb/…). */
@Composable
fun PosChip(pos: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            posLabel(pos),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A small neutral pill for gender ("el"/"la"), region, or other compact card metadata. */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// Faint gender cues: masculine articles tinted blue, feminine pink. Mid tones that stay legible in
// both the light (cream) and dark (forest) themes; the fill is the same hue at low alpha.
private val GenderMasculine = Color(0xFF4F86C6)
private val GenderFeminine = Color(0xFFC76B98)

/**
 * Gender pill ("el"/"la"/…) faintly colored by grammatical gender — blue for masculine, pink for
 * feminine — as a quiet visual cue. Common-gender ("el/la") and unknown codes fall back to a neutral
 * [InfoChip]; renders nothing when the card carries no gender.
 */
@Composable
fun GenderChip(gender: String?, modifier: Modifier = Modifier) {
    val article = genderArticle(gender) ?: return
    val tint = when (gender?.uppercase()) {
        "M", "MPL" -> GenderMasculine
        "F", "FPL" -> GenderFeminine
        else -> null
    }
    if (tint == null) {
        InfoChip(article, modifier)
        return
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint.copy(alpha = 0.18f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(article, style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** The Spanish article implied by a stored gender code (M/F/MF/MPL/FPL), for display; null if unset. */
fun genderArticle(gender: String?): String? = when (gender?.uppercase()) {
    "M" -> "el"
    "F" -> "la"
    "MF" -> "el/la"
    "MPL" -> "los"
    "FPL" -> "las"
    else -> null
}

/** Display label for a region slug emitted by the deck cleanup (e.g. "mx" → "México"). */
fun regionLabel(region: String): String = when (region.lowercase()) {
    "mx" -> "México"
    "centroamerica" -> "Centroamérica"
    "sudamerica" -> "Sudamérica"
    "latam" -> "Latinoamérica"
    "spain" -> "España"
    "ar" -> "Argentina"
    "co" -> "Colombia"
    "cl" -> "Chile"
    "pe" -> "Perú"
    "ve" -> "Venezuela"
    "caribe" -> "Caribe"
    "conosur" -> "Cono Sur"
    else -> region.replaceFirstChar { it.uppercase() }
}

/** Localized label for a normalized part-of-speech key; unknown keys display as-is. */
@Composable
fun posLabel(pos: String): String = when (pos.trim().lowercase()) {
    "noun" -> stringResource(R.string.pos_noun)
    "verb" -> stringResource(R.string.pos_verb)
    "adjective" -> stringResource(R.string.pos_adjective)
    "adverb" -> stringResource(R.string.pos_adverb)
    "phrase" -> stringResource(R.string.pos_phrase)
    "expression" -> stringResource(R.string.pos_expression)
    "grammar" -> stringResource(R.string.pos_grammar)
    "other" -> stringResource(R.string.pos_other)
    "unknown" -> stringResource(R.string.pos_unknown)
    else -> pos
}
