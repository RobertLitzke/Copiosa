package com.copiosa.domain.conjugation

import com.copiosa.domain.routine.ConjugationDrillItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A saved routine plan stores drill items by coordinate, so the codec is the seam between a plan
 * written weeks ago and the lexicon shipped today. It has to round-trip cleanly, and it has to drop
 * what it can't place rather than throw — the player skips unusable content, it doesn't crash on it.
 */
class DrillItemCodecTest {

    private val SPANISH = ConjugationSpecs.SPANISH

    private val cell = Cell(Tense.IMPERFECT_SUBJUNCTIVE, Person.YO)

    private val samples = listOf(
        DrillItem.Produce("tener", Cell(Tense.PRESENT, Person.YO)),
        DrillItem.Decode("saber", Cell(Tense.PRETERITE, Person.NOSOTROS)),
        DrillItem.Transform(
            "saber", TransformRules.PRETERITE_TO_IMPERFECT_SUBJUNCTIVE,
            Cell(Tense.PRETERITE, Person.ELLOS), cell,
        ),
        DrillItem.Generalize(IrregularityClass.STRONG_PRET_U, "poner", Cell(Tense.PRETERITE, Person.YO)),
    )

    @Test
    fun everyShapeRoundTrips() {
        samples.forEach { assertEquals(it, DrillItemCodec.decode(DrillItemCodec.encode(it), SPANISH)) }
    }

    @Test
    fun theShownVerbSurvivesAGeneralizeRoundTrip() {
        // The key omits the verb, so the stored item has to carry it separately or the card would
        // come back naming a class with nothing to ask about.
        val item = samples.last() as DrillItem.Generalize
        val restored = DrillItemCodec.decode(DrillItemCodec.encode(item), SPANISH) as DrillItem.Generalize
        assertEquals("poner", restored.infinitive)
        assertEquals(IrregularityClass.STRONG_PRET_U, restored.cls)
    }

    @Test
    fun unusableItemsAreDroppedRatherThanThrown() {
        // A verb no longer in the lexicon, a slug from a future build, a transform with no source.
        assertNull(DrillItemCodec.decode(ConjugationDrillItem("produce", "gorblefy", "pres.1s"), SPANISH))
        assertNull(DrillItemCodec.decode(ConjugationDrillItem("teleport", "tener", "pres.1s"), SPANISH))
        assertNull(DrillItemCodec.decode(ConjugationDrillItem("produce", "tener", "future_perfect.1s"), SPANISH))
        assertNull(DrillItemCodec.decode(ConjugationDrillItem("transform", "saber", "sub_imp.1s"), SPANISH))
        assertNull(DrillItemCodec.decode(ConjugationDrillItem("generalize", "strong_pret_u", "pret.1s"), SPANISH))
    }

    @Test
    fun decodeAllKeepsWhatItCanAndSkipsTheRest() {
        val stored = samples.map(DrillItemCodec::encode) + ConjugationDrillItem("produce", "gorblefy", "pres.1s")
        val decoded = DrillItemCodec.decodeAll(stored, SPANISH)
        assertEquals(samples.size, decoded.size)
        assertTrue(decoded.none { it.infinitive == "gorblefy" })
    }

    @Test
    fun aRoundTrippedItemKeepsItsPersistenceKey() {
        // The key is what per-item mastery is stored against; a codec that changed it would silently
        // orphan a learner's history for every in-routine card.
        samples.forEach {
            assertEquals(it.itemKey, DrillItemCodec.decode(DrillItemCodec.encode(it), SPANISH)!!.itemKey)
        }
    }
}
