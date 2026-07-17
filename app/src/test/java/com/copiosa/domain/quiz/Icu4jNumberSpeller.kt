package com.copiosa.domain.quiz

import com.ibm.icu.text.RuleBasedNumberFormat
import com.ibm.icu.util.ULocale

/**
 * Test-only twin of the app's android.icu-backed speller (android.icu isn't on the JVM test
 * classpath; icu4j ships the same CLDR spell-out rules), so per-language wording asserts run
 * against the real RBNF data the device will use.
 */
class Icu4jNumberSpeller : NumberSpeller {
    private val formatters = mutableMapOf<String, RuleBasedNumberFormat>()

    override fun spell(value: Int, langCode: String): String {
        val formatter = formatters.getOrPut(langCode) {
            RuleBasedNumberFormat(ULocale(langCode), RuleBasedNumberFormat.SPELLOUT)
        }
        // RBNF marks optional break points in de/it compounds with soft hyphens ("ein­und­zwanzig");
        // they must never reach the TTS text. The production speller strips them identically.
        return formatter.format(value.toLong()).replace(SOFT_HYPHEN, "")
    }

    private companion object {
        const val SOFT_HYPHEN = "­"
    }
}
