package com.copiosa.domain.quiz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CueGeneratorTest {

    private val speller = Icu4jNumberSpeller()

    private fun config(
        lang: String = "es",
        max: Int = 100,
        style: TimeStyle = TimeStyle.NUMERIC,
    ) = QuizPoolConfig(langCode = lang, numberMax = max, timeStyle = style)

    // --- numbers ---

    @Test
    fun `number values are distinct and in range`() {
        val values = NumberCueGenerator.generateValues(150, config(max = 100), Random(1))
        val ints = values.map { (it as CueValue.Number).value }
        assertEquals(ints.size, ints.distinct().size)
        assertTrue(ints.all { it in 1..100 })
        // Can't exceed the range size, but should fill most of it.
        assertTrue(ints.size in 50..100)
    }

    @Test
    fun `pool always contains 1 through 10`() {
        val values = NumberCueGenerator.generateValues(200, config(max = 1_000_000), Random(1))
            .map { (it as CueValue.Number).value }.toSet()
        (1..10).forEach { assertTrue("$it missing from pool", it in values) }
    }

    @Test
    fun `pool skews toward smaller magnitudes`() {
        val values = NumberCueGenerator.generateValues(400, config(max = 1_000_000), Random(7))
            .map { (it as CueValue.Number).value }
        val small = values.count { it < 1000 }
        val large = values.count { it >= 100_000 }
        assertTrue("expected far more small than large (small=$small large=$large)", small > large * 3)
    }

    @Test
    fun `values never exceed the configured max`() {
        val values = NumberCueGenerator.generateValues(300, config(max = 1_000), Random(4))
            .map { (it as CueValue.Number).value }
        assertTrue(values.all { it in 1..1_000 })
    }

    @Test
    fun `large numbers defer spelling to the LLM but small ones stay local`() {
        assertEquals(null, NumberCueGenerator.localSpokenText(CueValue.Number(123_456), config(), speller, Random(1)))
        assertTrue(NumberCueGenerator.localSpokenText(CueValue.Number(999), config(), speller, Random(1)) != null)
    }

    @Test
    fun `large-number distractors are distinct in-range near-misses`() {
        val random = Random(11)
        repeat(30) {
            val n = 100_000 + random.nextInt(900_000)
            val out = NumberCueGenerator.distractors(CueValue.Number(n), config(max = 1_000_000), random, 3, 1)
            val ints = out.map { (it as CueValue.Number).value }
            assertTrue(ints.isNotEmpty())
            assertFalse(n in ints)
            assertEquals(ints.size, ints.distinct().size)
            assertTrue(ints.all { it in 1..1_000_000 })
        }
    }

    @Test
    fun `number distractors exclude answer and stay in range`() {
        val random = Random(7)
        repeat(50) {
            val answer = CueValue.Number(random.nextInt(1, 101))
            val out = NumberCueGenerator.distractors(answer, config(), random, 3, difficultyLevel = 1)
            val ints = out.map { (it as CueValue.Number).value }
            assertFalse(answer.value in ints)
            assertEquals(ints.size, ints.distinct().size)
            assertTrue(ints.all { it in 1..100 })
            assertTrue(ints.isNotEmpty())
        }
    }

    @Test
    fun `curated teen-ty partner appears at high difficulty`() {
        val out = NumberCueGenerator.distractors(CueValue.Number(13), config(), Random(3), 3, difficultyLevel = 2)
        assertTrue(out.contains(CueValue.Number(30)))
    }

    @Test
    fun `distractors are deterministic under a seeded random`() {
        val a = NumberCueGenerator.distractors(CueValue.Number(73), config(), Random(42), 3, 1)
        val b = NumberCueGenerator.distractors(CueValue.Number(73), config(), Random(42), 3, 1)
        assertEquals(a, b)
    }

    @Test
    fun `tiny range still produces a choice`() {
        val out = NumberCueGenerator.distractors(CueValue.Number(3), config(max = 5), Random(1), 3)
        assertTrue(out.isNotEmpty())
        assertTrue(out.all { (it as CueValue.Number).value in 1..5 })
    }

    // --- times ---

    @Test
    fun `numeric time values span the full minute grid, colloquial stays on fives`() {
        // Numeric phrasing spells any minute, so its pool uses the whole 1-minute grid.
        val numeric = TimeCueGenerator.generateValues(200, config(style = TimeStyle.NUMERIC), Random(1))
        assertEquals(numeric.size, numeric.distinct().size)
        assertTrue(numeric.all { (it as CueValue.Time).let { t -> t.hour in 1..12 && t.minute in 0..59 } })
        assertTrue("expected some off-five minutes", numeric.any { (it as CueValue.Time).minute % 5 != 0 })

        // Colloquial phrasing (quarter/half/to) only exists on the 5-minute grid.
        val colloquial = TimeCueGenerator.generateValues(200, config(style = TimeStyle.COLLOQUIAL), Random(1))
        assertTrue(colloquial.all { (it as CueValue.Time).minute % 5 == 0 })
    }

    @Test
    fun `time pool reaches 250 for numeric-mixed but caps at 144 colloquial`() {
        assertEquals(250, TimeCueGenerator.generateValues(250, config(style = TimeStyle.NUMERIC), Random(1)).size)
        assertEquals(250, TimeCueGenerator.generateValues(250, config(style = TimeStyle.MIXED), Random(1)).size)
        // 12 hours × 12 five-minute slots is the whole colloquial space.
        assertEquals(144, TimeCueGenerator.generateValues(250, config(style = TimeStyle.COLLOQUIAL), Random(1)).size)
    }

    @Test
    fun `mixed speaks off-grid times numerically, never null`() {
        // An off-five minute has no colloquial reading, so MIXED must phrase it numerically (non-null).
        repeat(20) { m ->
            val minute = m + 1 // 1..20, includes plenty of non-multiples of 5
            if (minute % 5 == 0) return@repeat
            val out = TimeCueGenerator.localSpokenText(
                CueValue.Time(6, minute), config(style = TimeStyle.MIXED), speller, Random(minute.toLong()),
            )
            assertTrue("off-grid MIXED time 6:$minute should be numeric, got null", out != null)
        }
    }

    @Test
    fun `quarter mirror trap appears for 6_15`() {
        val out = TimeCueGenerator.distractors(CueValue.Time(6, 15), config(), Random(2), 3, difficultyLevel = 2)
        // 6:50 (teen/ty) and 6:45 (past/to mirror) are the curated traps; at least one must survive.
        assertTrue(out.any { it == CueValue.Time(6, 50) || it == CueValue.Time(6, 45) })
    }

    @Test
    fun `german halb trap offers next hour half`() {
        val out = TimeCueGenerator.distractors(
            CueValue.Time(6, 30), config(lang = "de"), Random(2), 3, difficultyLevel = 2,
        )
        assertTrue(out.contains(CueValue.Time(7, 30)))
    }

    @Test
    fun `time distractors exclude answer and stay on grid`() {
        val random = Random(9)
        repeat(50) {
            val answer = CueValue.Time(random.nextInt(1, 13), random.nextInt(0, 12) * 5)
            val out = TimeCueGenerator.distractors(answer, config(), random, 3, 1)
            assertFalse(answer in out)
            assertEquals(out.size, out.distinct().size)
            assertTrue(
                out.all {
                    val t = it as CueValue.Time
                    t.hour in 1..12 && t.minute in 0..55 && t.minute % 5 == 0
                },
            )
        }
    }

    // --- local numeric phrasing ---

    @Test
    fun `numeric time phrasing per language`() {
        fun phrase(lang: String, h: Int, m: Int): String? =
            TimeCueGenerator.localSpokenText(CueValue.Time(h, m), config(lang = lang), speller, Random(1))

        assertEquals("la una en punto", phrase("es", 1, 0))
        assertEquals("las seis y quince", phrase("es", 6, 15))
        assertEquals("une heure cinq", phrase("fr", 1, 5))
        assertEquals("sept heures quarante-cinq", phrase("fr", 7, 45))
        assertEquals("ein Uhr", phrase("de", 1, 0))
        assertEquals("sechs Uhr fünfzehn", phrase("de", 6, 15))
        assertEquals("l'una e quindici", phrase("it", 1, 15))
        assertEquals("le sei in punto", phrase("it", 6, 0))
        assertEquals("duas e trinta", phrase("pt", 2, 30))
        assertEquals("six o'clock", phrase("en", 6, 0))
        assertEquals("six fifteen", phrase("en", 6, 15))
    }

    @Test
    fun `colloquial style defers to the LLM`() {
        val out = TimeCueGenerator.localSpokenText(
            CueValue.Time(6, 15), config(style = TimeStyle.COLLOQUIAL), speller, Random(1),
        )
        assertEquals(null, out)
    }

    @Test
    fun `numbers always phrase locally`() {
        val out = NumberCueGenerator.localSpokenText(CueValue.Number(73), config(lang = "fr"), speller, Random(1))
        assertEquals("soixante-treize", out)
    }

    // --- value keys ---

    @Test
    fun `value keys round trip`() {
        assertEquals(CueValue.Number(73), CueValue.parse(CueType.NUMBER, CueValue.Number(73).key()))
        assertEquals(CueValue.Time(6, 5), CueValue.parse(CueType.TIME, CueValue.Time(6, 5).key()))
        assertEquals("06:05", CueValue.Time(6, 5).key())
    }

    @Test
    fun `config codec round trips`() {
        val config = QuizPoolConfig("de", CueType.TIME, 1000, TimeStyle.COLLOQUIAL, 400)
        assertEquals(config, QuizPoolConfig.decode(config.encode()))
        assertEquals(null, QuizPoolConfig.decode("not json"))
    }
}
