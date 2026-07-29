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

// Faint gender cues: masculine articles tinted blue, feminine pink, neuter a muted teal. Mid tones
// that stay legible in both the light (cream) and dark (forest) themes; fill is the hue at low alpha.
private val GenderMasculine = Color(0xFF4F86C6)
private val GenderFeminine = Color(0xFFC76B98)
private val GenderNeuter = Color(0xFF4FA88A)

/**
 * Gender pill ("el"/"la"/"der"/…) faintly colored by grammatical gender — blue for masculine, pink
 * for feminine, teal for neuter — as a quiet visual cue. Common-gender and unknown codes fall back to
 * a neutral [InfoChip]; renders nothing when the card carries no article for its [lang].
 */
@Composable
fun GenderChip(gender: String?, lang: String, modifier: Modifier = Modifier) {
    val article = genderArticle(gender, lang) ?: return
    val tint = when (gender?.uppercase()) {
        "M", "MPL" -> GenderMasculine
        "F", "FPL" -> GenderFeminine
        "N" -> GenderNeuter
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

/**
 * The definite article implied by a stored gender code (M/F/MF/N/MPL/FPL) in [lang], for display;
 * null if the code has no article in that language (e.g. neuter in Spanish) or is unset. Supports
 * Spanish/Portuguese/French/German — the languages whose nouns carry grammatical gender here.
 */
fun genderArticle(gender: String?, lang: String): String? {
    val code = gender?.uppercase() ?: return null
    return when (lang.lowercase().substringBefore('-')) {
        "es" -> when (code) {
            "M" -> "el"; "F" -> "la"; "MF" -> "el/la"; "MPL" -> "los"; "FPL" -> "las"; else -> null
        }
        "pt" -> when (code) {
            "M" -> "o"; "F" -> "a"; "MF" -> "o/a"; "MPL" -> "os"; "FPL" -> "as"; else -> null
        }
        "fr" -> when (code) {
            "M" -> "le"; "F" -> "la"; "MF" -> "le/la"; "MPL" -> "les"; "FPL" -> "les"; else -> null
        }
        "de" -> when (code) {
            "M" -> "der"; "F" -> "die"; "N" -> "das"; "MF" -> "der/die"; "MPL", "FPL" -> "die"; else -> null
        }
        else -> null
    }
}

/**
 * The gender codes offered in the card editor for [lang], as `null` (none) plus the language's set.
 * Neuter (N) is offered only for languages that have it (German).
 */
fun genderOptions(lang: String): List<String?> = when (lang.lowercase().substringBefore('-')) {
    "de" -> listOf(null, "M", "F", "N")
    "es", "pt", "fr" -> listOf(null, "M", "F", "MF")
    else -> listOf(null, "M", "F", "MF", "N")
}

/**
 * Human-readable form of a stored [FlashcardEntity.country]. Countries are persisted whitespace-free
 * (spaces collapsed to underscores) so they survive an Anki `la:country` tag round-trip; this turns
 * "Costa_Rica" back into "Costa Rica" for display.
 */
fun countryLabel(country: String): String = country.replace('_', ' ')

/** Normalizes a typed country for storage: trims and collapses runs of whitespace to single underscores. */
fun countrySlug(country: String): String? =
    country.trim().replace(Regex("\\s+"), "_").ifBlank { null }

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
