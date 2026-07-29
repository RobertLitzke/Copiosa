package com.copiosa.domain.conjugation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ConjugationDrillSelectorTest {

    private val verbs = VerbLexicon.verbs
    private val allTenses = Tense.entries.toSet()
    private val now = 1_700_000_000_000L

    private fun selector(seed: Int = 1) = ConjugationDrillSelector(Random(seed))

    private fun draw(
        config: ConjugationDrillConfig,
        seed: Int = 1,
        rounds: Int = 1,
        stats: Map<String, ItemStat> = emptyMap(),
        familyMastery: Map<IrregularityFamily, Double> = emptyMap(),
        seenVerbs: Set<String> = emptySet(),
    ): List<DrillItem> {
        val picked = mutableListOf<DrillItem>()
        repeat(rounds) { round ->
            picked += ConjugationDrillSelector(Random(seed + round))
                .select(verbs, config, stats, familyMastery, seenVerbs, now)
        }
        return picked
    }

    // -- Calibration -------------------------------------------------------------------------------

    /**
     * The tuning target: roughly three quarters of a cold-start session should land in the forty
     * commonest verbs, so the drill feels like it is teaching the language rather than a word list.
     * `ConjugationDrillSelector.FREQ_EXPONENT` is set from this test, not by eye — if it fails,
     * retune the exponent rather than widening the band.
     */
    @Test
    fun topFortyShareIsInTargetBand() {
        val topForty = verbs.filter { it.rank <= 40 }.map { it.infinitive }.toSet()
        val drawn = draw(ConjugationDrillConfig(itemCount = 20), rounds = 500)
        val share = drawn.count { it.infinitive in topForty }.toDouble() / drawn.size
        assertTrue(
            "top-40 share was %.1f%%, wanted 75-80%% — retune FREQ_EXPONENT".format(share * 100),
            share in 0.75..0.80,
        )
    }

    @Test
    fun theHugeVerbsAreDampedBelowTheirRawFrequencyShare() {
        val damped = setOf("ser", "estar", "haber", "ir")
        val rawShare = verbs.filter { it.infinitive in damped }.sumOf { it.frequency } /
            verbs.sumOf { it.frequency }
        val drawn = draw(ConjugationDrillConfig(itemCount = 20), rounds = 400)
        val share = drawn.count { it.infinitive in damped }.toDouble() / drawn.size
        assertTrue(
            "ser/estar/haber/ir took %.1f%% of draws against a raw %.1f%%".format(share * 100, rawShare * 100),
            share < rawShare,
        )
        assertTrue("...but they should not vanish either", share > 0.0)
    }

    // -- Determinism and session shape ------------------------------------------------------------

    @Test
    fun sameSeedProducesTheSameSession() {
        val config = ConjugationDrillConfig(itemCount = 20, tenses = allTenses)
        assertEquals(draw(config, seed = 7), draw(config, seed = 7))
        assertFalse(draw(config, seed = 7) == draw(config, seed = 8))
    }

    @Test
    fun aSessionFillsToTheRequestedCountWithoutRepeatingACell() {
        val items = draw(ConjugationDrillConfig(itemCount = 30, tenses = allTenses))
        assertEquals(30, items.size)
        val cells = items.map { it.infinitive to it.target }
        assertEquals("a cell should not be asked twice in one session", cells.size, cells.toSet().size)
    }

    @Test
    fun configFiltersAreHonoured() {
        val config = ConjugationDrillConfig(
            itemCount = 40,
            tenses = setOf(Tense.IMPERFECT_SUBJUNCTIVE),
            directions = setOf(DrillDirection.PRODUCE),
            includeVosotros = false,
        )
        val items = draw(config, rounds = 5)
        assertTrue(items.isNotEmpty())
        assertTrue(items.all { it.target.tense == Tense.IMPERFECT_SUBJUNCTIVE })
        assertTrue(items.all { it.direction == DrillDirection.PRODUCE })
        assertTrue("vosotros is off", items.none { it.target.person == Person.VOSOTROS })

        val withVosotros = draw(config.copy(includeVosotros = true), rounds = 20)
        assertTrue(withVosotros.any { it.target.person == Person.VOSOTROS })
    }

    @Test
    fun anEmptySelectionFallsBackRatherThanReturningNothing() {
        val items = draw(ConjugationDrillConfig(tenses = emptySet(), directions = emptySet()))
        assertTrue(items.isNotEmpty())
    }

    // -- Adaptation --------------------------------------------------------------------------------

    @Test
    fun weakItemsResurfaceMoreThanMasteredOnes() {
        val config = ConjugationDrillConfig(itemCount = 20, tenses = setOf(Tense.PRESENT))
        val cell = Cell(Tense.PRESENT, Person.YO)
        val weak = DrillItem.Produce("tener", cell).itemKey
        val strong = DrillItem.Produce("poder", cell).itemKey
        val stats = mapOf(
            weak to ItemStat(attempts = 6, mastery = 0.05, lastSeenAt = null),
            strong to ItemStat(attempts = 6, mastery = 0.98, lastSeenAt = null),
        )
        val drawn = draw(config, rounds = 300, stats = stats)
        val weakHits = drawn.count { it.itemKey == weak }
        val strongHits = drawn.count { it.itemKey == strong }
        assertTrue(
            "weak item drawn $weakHits times vs mastered $strongHits",
            weakHits > strongHits,
        )
    }

    @Test
    fun justSeenItemsAreHeldBack() {
        val config = ConjugationDrillConfig(itemCount = 20, tenses = setOf(Tense.PRESENT))
        val key = DrillItem.Produce("tener", Cell(Tense.PRESENT, Person.YO)).itemKey
        val fresh = draw(config, rounds = 200, stats = mapOf(key to ItemStat(attempts = 3, lastSeenAt = null)))
        val justSeen = draw(config, rounds = 200, stats = mapOf(key to ItemStat(attempts = 3, lastSeenAt = now)))
        assertTrue(
            "an item seen seconds ago should come back less often",
            justSeen.count { it.itemKey == key } < fresh.count { it.itemKey == key },
        )
    }

    // -- The derived directions --------------------------------------------------------------------

    @Test
    fun derivedCardsStayRareUntilTheFamilyLands() {
        val config = ConjugationDrillConfig(
            itemCount = 20,
            tenses = allTenses,
            directions = DrillDirection.entries.toSet(),
        )
        val derived = setOf(DrillDirection.TRANSFORM, DrillDirection.GENERALIZE)
        val cold = draw(config, rounds = 60).count { it.direction in derived }
        val warm = draw(
            config, rounds = 60,
            familyMastery = IrregularityFamily.entries.associateWith { 0.95 },
        ).count { it.direction in derived }
        assertTrue("derived cards were $cold cold and $warm warm", warm > cold)
    }

    @Test
    fun transformCardsOnlyAskAboutCellsThatActuallyDeviate() {
        val config = ConjugationDrillConfig(
            itemCount = 40, tenses = allTenses,
            directions = setOf(DrillDirection.TRANSFORM),
        )
        val items = draw(config, rounds = 20, familyMastery = IrregularityFamily.entries.associateWith { 1.0 })
            .filterIsInstance<DrillItem.Transform>()
        assertTrue("no transform cards were produced", items.isNotEmpty())
        items.forEach {
            val verb = VerbLexicon.byInfinitive(it.infinitive)!!
            assertTrue(
                "${it.infinitive} ${it.source.slug} is regular — nothing to transform",
                verb.overrideAt(it.source.tense, it.source.person) != null,
            )
            assertEquals(it.rule.sourceCell(), it.source)
            assertEquals(it.rule.targetTense, it.target.tense)
        }
    }

    @Test
    fun generalizeCardsOnlyUseVerbsTheLearnerHasNeverDrilled() {
        val config = ConjugationDrillConfig(
            itemCount = 40, tenses = allTenses,
            directions = setOf(DrillDirection.GENERALIZE),
        )
        val warm = IrregularityFamily.entries.associateWith { 1.0 }
        val items = draw(config, rounds = 20, familyMastery = warm).filterIsInstance<DrillItem.Generalize>()
        assertTrue("no generalize cards were produced", items.isNotEmpty())
        items.forEach {
            val verb = VerbLexicon.byInfinitive(it.infinitive)!!
            assertTrue(
                "${it.infinitive} ${it.target.slug} does not exhibit ${it.cls.slug}",
                verb.overrideAt(it.target.tense, it.target.person)?.classes?.contains(it.cls) == true,
            )
        }
        // Knowing every verb in a class retires it rather than re-showing a memorised one.
        val everything = verbs.map { it.infinitive }.toSet()
        assertTrue(
            draw(config, rounds = 20, familyMastery = warm, seenVerbs = everything)
                .none { it is DrillItem.Generalize },
        )
    }

    @Test
    fun generalizeKeysOnTheClassNotTheVerb() {
        // Two learners meeting the same pattern through different verbs share one mastery record.
        val a = DrillItem.Generalize(IrregularityClass.STRONG_PRET_U, "andar", Cell(Tense.PRETERITE, Person.YO))
        val b = DrillItem.Generalize(IrregularityClass.STRONG_PRET_U, "caber", Cell(Tense.PRETERITE, Person.YO))
        assertEquals(a.itemKey, b.itemKey)
        assertEquals("1|generalize|strong_pret_u||pret.1s", a.itemKey)
    }

    // -- Restrictions ------------------------------------------------------------------------------

    @Test
    fun haberIsRecognisedButNeverAskedToBeProduced() {
        val config = ConjugationDrillConfig(
            itemCount = 40, tenses = allTenses,
            directions = DrillDirection.entries.toSet(),
        )
        val items = draw(config, rounds = 120, familyMastery = IrregularityFamily.entries.associateWith { 1.0 })
        val haber = items.filter { it.infinitive == "haber" }
        assertTrue("haber never appeared at all", haber.isNotEmpty())
        assertTrue(
            "compound tenses are out of scope, so producing 'hemos' alone drills nothing",
            haber.all { it.direction == DrillDirection.DECODE },
        )
    }

    // -- Key format --------------------------------------------------------------------------------

    @Test
    fun itemKeysAreStableAndDistinguishAllFourShapes() {
        val cell = Cell(Tense.IMPERFECT_SUBJUNCTIVE, Person.YO)
        assertEquals("1|produce|hablar||sub_imp.1s", DrillItem.Produce("hablar", cell).itemKey)
        assertEquals("1|decode|hablar||sub_imp.1s", DrillItem.Decode("hablar", cell).itemKey)
        assertEquals(
            "1|transform|saber|pret.3p|sub_imp.1s",
            DrillItem.Transform(
                "saber", TransformRules.PRETERITE_TO_IMPERFECT_SUBJUNCTIVE,
                Cell(Tense.PRETERITE, Person.ELLOS), cell,
            ).itemKey,
        )
        val keys = listOf(
            DrillItem.Produce("hablar", cell), DrillItem.Decode("hablar", cell),
            DrillItem.Generalize(IrregularityClass.STRONG_PRET_U, "andar", cell),
        ).map { it.itemKey }
        assertEquals("every shape must key distinctly", keys.size, keys.toSet().size)
        keys.forEach { assertEquals("five fields", 5, it.split("|").size) }
    }
}
