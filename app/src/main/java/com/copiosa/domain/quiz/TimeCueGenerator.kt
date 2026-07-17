package com.copiosa.domain.quiz

import kotlin.random.Random

/**
 * Clock times on the 5-minute grid, hours 1..12. Distractors are the mishearings that actually
 * happen: teen/ty minutes (6:15 vs 6:50), the past/to mirror (quarter past six vs quarter to seven),
 * an off-by-one hour, and German's "halb sieben" = 6:30 next-hour trap.
 *
 * Spoken phrasing: [TimeStyle.NUMERIC] is built locally from hour words + spelled minutes
 * ("las seis y quince"); [TimeStyle.COLLOQUIAL] returns null from [localSpokenText] so the pool
 * seeder sends the cue to the LLM, which owns quarter/half/to idioms per language.
 */
object TimeCueGenerator : CueGenerator {
    override val type: CueType = CueType.TIME

    override fun generateValues(count: Int, config: QuizPoolConfig, random: Random): List<CueValue> {
        // Colloquial phrasing (quarter/half/to) only exists on the 5-minute grid (144 times); numeric
        // phrasing spells any minute, so those pools use the full 1-minute grid (720) and can reach the
        // larger pool sizes. MIXED speaks off-grid times numerically (see localSpokenText).
        val minuteStep = if (config.timeStyle == TimeStyle.COLLOQUIAL) 5 else 1
        val all = (1..12).flatMap { h -> (0..59 step minuteStep).map { m -> CueValue.Time(h, m) } }
        return all.shuffled(random).take(count)
    }

    override fun distractors(
        answer: CueValue,
        config: QuizPoolConfig,
        random: Random,
        count: Int,
        difficultyLevel: Int,
    ): List<CueValue> {
        val t = answer as CueValue.Time
        val traps = buildList {
            // Teen/ty minute mishearings that land back on the grid.
            when (t.minute) {
                15 -> add(t.copy(minute = 50))
                50 -> add(t.copy(minute = 15))
                5 -> add(t.copy(minute = 55))
                55 -> add(t.copy(minute = 5))
            }
            // Past/to mirror: quarter past six vs quarter to seven read the same minute word.
            if (t.minute != 0 && t.minute != 30) add(t.copy(minute = 60 - t.minute))
            // German halb trap: "halb sieben" is 6:30, so 7:30 is the classic wrong parse.
            if (t.minute == 30 && config.langCode == "de") add(CueValue.Time(nextHour(t.hour), 30))
        }
        val nearby = buildList {
            add(t.copy(hour = nextHour(t.hour)))
            add(t.copy(hour = prevHour(t.hour)))
            add(t.copy(minute = (t.minute + 15) % 60))
            add(t.copy(minute = (t.minute + 45) % 60)) // -15, wrapped
            add(t.copy(minute = (t.minute + 5) % 60))
            add(t.copy(minute = (t.minute + 55) % 60)) // -5, wrapped
        }
        // Low difficulty keeps at most one true trap in the set; mastery earns the full gauntlet.
        val trapCap = if (difficultyLevel >= 1) count else 1
        return (traps.shuffled(random).take(trapCap) + nearby.shuffled(random))
            .filter { it != t }
            .distinct()
            .take(count)
    }

    override fun localSpokenText(
        value: CueValue,
        config: QuizPoolConfig,
        speller: NumberSpeller,
        random: Random,
    ): String? {
        val t = value as CueValue.Time
        val numeric = when (config.timeStyle) {
            TimeStyle.NUMERIC -> true
            TimeStyle.COLLOQUIAL -> false
            // An off-5-minute time has no colloquial reading, so it must be spoken numerically; a
            // grid-aligned time still flips a coin between the two styles.
            TimeStyle.MIXED -> t.minute % 5 != 0 || random.nextBoolean()
        }
        if (!numeric) return null
        return numericPhrase(t.hour, t.minute, config.langCode, speller)
    }

    override fun displayText(value: CueValue): String {
        val t = value as CueValue.Time
        return "${t.hour}:${t.minute.toString().padStart(2, '0')}"
    }

    /**
     * The plain hour-then-minutes reading per language. Hour words are hardcoded (twelve per
     * language) because they carry the gender/elision quirks ICU's plain cardinals don't ("la una",
     * "l'una", "ein Uhr"); minutes come from the [speller].
     */
    private fun numericPhrase(hour: Int, minute: Int, lang: String, speller: NumberSpeller): String {
        val h = hourWord(lang, hour)
        val m = if (minute == 0) null else speller.spell(minute, lang)
        return when (lang) {
            "es" -> {
                val article = if (hour == 1) "la" else "las"
                if (m == null) "$article $h en punto" else "$article $h y $m"
            }
            "fr" -> {
                val heures = if (hour == 1) "heure" else "heures"
                if (m == null) "$h $heures" else "$h $heures $m"
            }
            "de" -> if (m == null) "$h Uhr" else "$h Uhr $m"
            // Italian hour words carry their own article/elision ("le sei", "l'una").
            "it" -> if (m == null) "$h in punto" else "$h e $m"
            "pt" -> {
                val horas = if (hour == 1) "hora" else "horas"
                if (m == null) "$h $horas" else "$h e $m"
            }
            else -> if (m == null) "$h o'clock" else "$h $m"
        }
    }

    private fun hourWord(lang: String, hour: Int): String = HOUR_WORDS[lang]?.get(hour - 1)
        ?: HOUR_WORDS["en"]!![hour - 1]

    // Feminine/elided/clock-specific hour forms per language, indexed by hour-1.
    private val HOUR_WORDS: Map<String, List<String>> = mapOf(
        "en" to listOf(
            "one", "two", "three", "four", "five", "six",
            "seven", "eight", "nine", "ten", "eleven", "twelve",
        ),
        "es" to listOf(
            "una", "dos", "tres", "cuatro", "cinco", "seis",
            "siete", "ocho", "nueve", "diez", "once", "doce",
        ),
        "fr" to listOf(
            "une", "deux", "trois", "quatre", "cinq", "six",
            "sept", "huit", "neuf", "dix", "onze", "douze",
        ),
        "de" to listOf(
            "ein", "zwei", "drei", "vier", "fünf", "sechs",
            "sieben", "acht", "neun", "zehn", "elf", "zwölf",
        ),
        "it" to listOf(
            "l'una", "le due", "le tre", "le quattro", "le cinque", "le sei",
            "le sette", "le otto", "le nove", "le dieci", "le undici", "le dodici",
        ),
        "pt" to listOf(
            "uma", "duas", "três", "quatro", "cinco", "seis",
            "sete", "oito", "nove", "dez", "onze", "doze",
        ),
    )

    private fun nextHour(h: Int): Int = if (h == 12) 1 else h + 1
    private fun prevHour(h: Int): Int = if (h == 1) 12 else h - 1
}
