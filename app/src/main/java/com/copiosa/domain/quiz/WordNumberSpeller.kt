package com.copiosa.domain.quiz

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hand-rolled cardinal spell-out for the six supported languages, 0..1000 (the range the quiz phrases
 * locally). Android's public android.icu API doesn't expose RuleBasedNumberFormat, and shipping icu4j
 * for six languages of bounded cardinals isn't worth ~13 MB — so these rules are written here and held
 * to CLDR by a parity test that compares every value 0..1000 against real icu4j RBNF output
 * (WordNumberSpellerParityTest). The pool only ever asks this speller for numbers in range; larger
 * numbers are phrased by the LLM at seed time (see QuizPoolSeeder) and simply skipped when no key is
 * available, so a guessed large-number spelling never reaches the pool.
 */
@Singleton
class WordNumberSpeller @Inject constructor() : NumberSpeller {

    override fun spell(value: Int, langCode: String): String {
        if (value < 0) return "-" + spell(-value, langCode)
        return when (langCode.substringBefore('-').lowercase()) {
            "es" -> spanish(value)
            "fr" -> french(value)
            "de" -> german(value)
            "it" -> italian(value)
            "pt" -> portuguese(value)
            else -> english(value)
        }
    }

    // --- English ---

    private val enOnes = listOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen",
    )
    private val enTens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

    private fun english(n: Int): String = when {
        n < 20 -> enOnes[n]
        n < 100 -> enTens[n / 10] + (if (n % 10 > 0) "-${enOnes[n % 10]}" else "")
        n < 1000 -> "${enOnes[n / 100]} hundred" + (if (n % 100 > 0) " ${english(n % 100)}" else "")
        n == 1000 -> "one thousand"
        else -> "${english(n / 1000)} thousand" + (if (n % 1000 > 0) " ${english(n % 1000)}" else "")
    }

    // --- Spanish ---

    private val esOnes = listOf(
        "cero", "uno", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
        "once", "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve",
    )
    private val esTwenties = listOf(
        "veinte", "veintiuno", "veintidós", "veintitrés", "veinticuatro",
        "veinticinco", "veintiséis", "veintisiete", "veintiocho", "veintinueve",
    )
    private val esTens = listOf("", "", "", "treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa")
    private val esHundreds = listOf(
        "", "ciento", "doscientos", "trescientos", "cuatrocientos", "quinientos",
        "seiscientos", "setecientos", "ochocientos", "novecientos",
    )

    private fun spanish(n: Int): String = when {
        n < 20 -> esOnes[n]
        n < 30 -> esTwenties[n - 20]
        n < 100 -> esTens[n / 10] + (if (n % 10 > 0) " y ${esOnes[n % 10]}" else "")
        n == 100 -> "cien"
        n < 1000 -> esHundreds[n / 100] + (if (n % 100 > 0) " ${spanish(n % 100)}" else "")
        n == 1000 -> "mil"
        else -> "${spanish(n / 1000)} mil" + (if (n % 1000 > 0) " ${spanish(n % 1000)}" else "")
    }

    // --- French (CLDR uses the 1990-reform orthography: hyphens between all elements) ---

    private val frOnes = listOf(
        "zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix",
        "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf",
    )
    private val frTens = listOf("", "", "vingt", "trente", "quarante", "cinquante", "soixante")

    private fun french(n: Int): String = when {
        n < 20 -> frOnes[n]
        n < 70 -> {
            val unit = n % 10
            when (unit) {
                0 -> frTens[n / 10]
                1 -> "${frTens[n / 10]}-et-un"
                else -> "${frTens[n / 10]}-${frOnes[unit]}"
            }
        }
        n < 80 -> if (n == 71) "soixante-et-onze" else "soixante-${frOnes[n - 60]}"
        n == 80 -> "quatre-vingts"
        n < 100 -> "quatre-vingt-${french(n - 80)}"
        n == 100 -> "cent"
        // CLDR hyphenates within tens (1990 reform) but keeps spaces around cent/mille.
        n < 200 -> "cent ${french(n % 100)}"
        n < 1000 -> {
            val h = "${frOnes[n / 100]} cent"
            if (n % 100 == 0) "${h}s" else "$h ${french(n % 100)}"
        }
        n == 1000 -> "mille"
        else -> "mille ${french(n % 1000)}"
    }

    // --- German ---

    private val deOnes = listOf(
        "null", "eins", "zwei", "drei", "vier", "fünf", "sechs", "sieben", "acht", "neun", "zehn",
        "elf", "zwölf", "dreizehn", "vierzehn", "fünfzehn", "sechzehn", "siebzehn", "achtzehn", "neunzehn",
    )
    private val deTens = listOf("", "", "zwanzig", "dreißig", "vierzig", "fünfzig", "sechzig", "siebzig", "achtzig", "neunzig")

    /** The unit word inside a compound ("ein" not "eins": einundzwanzig, einhundert). */
    private fun deUnit(n: Int): String = if (n == 1) "ein" else deOnes[n]

    private fun german(n: Int): String = when {
        n < 20 -> deOnes[n]
        n < 100 -> {
            val unit = n % 10
            if (unit == 0) deTens[n / 10] else "${deUnit(unit)}und${deTens[n / 10]}"
        }
        n < 1000 -> "${deUnit(n / 100)}hundert" + (if (n % 100 > 0) german(n % 100) else "")
        n == 1000 -> "eintausend"
        else -> "${deUnit(n / 1000)}tausend" + (if (n % 1000 > 0) german(n % 1000) else "")
    }

    // --- Italian ---

    private val itOnes = listOf(
        "zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove", "dieci",
        "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove",
    )
    private val itTens = listOf("", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta", "novanta")

    private fun italian(n: Int): String = when {
        n < 20 -> itOnes[n]
        n < 100 -> {
            val unit = n % 10
            val ten = itTens[n / 10]
            when (unit) {
                0 -> ten
                // The tens elide their final vowel before uno/otto (ventuno, ventotto)…
                1, 8 -> ten.dropLast(1) + itOnes[unit]
                // …and a final 3 takes the accent (ventitré).
                3 -> "${ten}tré"
                else -> "$ten${itOnes[unit]}"
            }
        }
        n < 1000 -> {
            val prefix = if (n / 100 == 1) "cento" else "${itOnes[n / 100]}cento"
            val rem = n % 100
            when {
                rem == 0 -> prefix
                // A bare 3 is accented as the compound's final element (centotré).
                rem == 3 -> prefix + "tré"
                // cento elides before the vowel of ottanta/otto (centottanta, centotto).
                rem / 10 == 8 || rem == 8 -> prefix.dropLast(1) + italian(rem)
                else -> prefix + italian(rem)
            }
        }
        n == 1000 -> "mille"
        else -> "mille" + (if (n % 1000 > 0) italian(n % 1000) else "")
    }

    // --- Portuguese (CLDR root follows pt-BR wording) ---

    private val ptOnes = listOf(
        "zero", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez",
        "onze", "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove",
    )
    private val ptTens = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta", "sessenta", "setenta", "oitenta", "noventa")
    private val ptHundreds = listOf(
        "", "cento", "duzentos", "trezentos", "quatrocentos", "quinhentos",
        "seiscentos", "setecentos", "oitocentos", "novecentos",
    )

    private fun portuguese(n: Int): String = when {
        n < 20 -> ptOnes[n]
        n < 100 -> ptTens[n / 10] + (if (n % 10 > 0) " e ${ptOnes[n % 10]}" else "")
        n == 100 -> "cem"
        n < 1000 -> ptHundreds[n / 100] + (if (n % 100 > 0) " e ${portuguese(n % 100)}" else "")
        n == 1000 -> "mil"
        else -> "${portuguese(n / 1000)} mil" + (if (n % 1000 > 0) " e ${portuguese(n % 1000)}" else "")
    }
}
