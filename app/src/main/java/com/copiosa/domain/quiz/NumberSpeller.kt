package com.copiosa.domain.quiz

/**
 * Converts a cardinal number to words in a target language ("73" -> "soixante-treize"). An interface
 * so the domain layer stays JVM-testable: the app binds an android.icu RuleBasedNumberFormat
 * implementation (unavailable in unit tests), tests bind an icu4j-backed twin with the same CLDR rules.
 */
interface NumberSpeller {
    fun spell(value: Int, langCode: String): String
}
