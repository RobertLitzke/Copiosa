package com.copiosa.ui.components

/**
 * A representative emoji flag for a BCP-47 language tag, used as an at-a-glance cue for which
 * language a card prompt is in (so e.g. "son" — English noun vs. Spanish "they are" — is unambiguous).
 *
 * Best-effort and intentionally simple: a small table of common languages, then a fall-back that
 * builds a flag from the tag's region subtag (e.g. "en-US" → 🇺🇸), then a neutral flag.
 */
fun flagForLanguage(tag: String): String {
    val lang = tag.substringBefore('-').lowercase()
    LANGUAGE_FLAGS[lang]?.let { return it }
    val region = tag.substringAfter('-', "").take(2)
    return regionFlag(region) ?: NEUTRAL_FLAG
}

private const val NEUTRAL_FLAG = "🏳️"

private val LANGUAGE_FLAGS = mapOf(
    "es" to "🇪🇸", "en" to "🇬🇧", "fr" to "🇫🇷", "de" to "🇩🇪", "it" to "🇮🇹",
    "pt" to "🇵🇹", "nl" to "🇳🇱", "ru" to "🇷🇺", "ja" to "🇯🇵", "zh" to "🇨🇳",
    "ko" to "🇰🇷", "ar" to "🇸🇦", "hi" to "🇮🇳", "pl" to "🇵🇱", "sv" to "🇸🇪",
    "tr" to "🇹🇷", "el" to "🇬🇷", "he" to "🇮🇱", "uk" to "🇺🇦", "cs" to "🇨🇿",
)

/** Two-letter ISO region code → emoji flag (regional indicator symbols), or null if malformed. */
private fun regionFlag(code: String): String? {
    if (code.length != 2 || !code.all { it.isLetter() }) return null
    val upper = code.uppercase()
    val base = 0x1F1E6 // regional indicator 'A'
    return String(Character.toChars(base + (upper[0] - 'A'))) +
        String(Character.toChars(base + (upper[1] - 'A')))
}
