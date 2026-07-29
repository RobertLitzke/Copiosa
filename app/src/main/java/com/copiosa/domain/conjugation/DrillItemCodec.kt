package com.copiosa.domain.conjugation

import com.copiosa.domain.routine.ConjugationDrillItem

/**
 * Converts between the runtime [DrillItem] and the [ConjugationDrillItem] stored in a routine plan.
 *
 * Decoding is deliberately forgiving: an unreadable item is dropped rather than thrown, so a plan
 * saved by an older build — or one naming a verb since removed from the lexicon — degrades to a
 * shorter block instead of a crash, matching how the player already skips unknown content kinds.
 */
object DrillItemCodec {

    fun encode(item: DrillItem): ConjugationDrillItem = when (item) {
        is DrillItem.Produce, is DrillItem.Decode -> ConjugationDrillItem(
            direction = item.direction.slug,
            subject = item.infinitive,
            targetCell = item.target.slug,
        )

        is DrillItem.Transform -> ConjugationDrillItem(
            direction = item.direction.slug,
            subject = item.infinitive,
            targetCell = item.target.slug,
            sourceCell = item.source.slug,
        )

        is DrillItem.Generalize -> ConjugationDrillItem(
            direction = item.direction.slug,
            subject = item.cls.slug,
            targetCell = item.target.slug,
            shownInfinitive = item.infinitive,
        )
    }

    fun decode(stored: ConjugationDrillItem): DrillItem? {
        val target = Cell.fromSlug(stored.targetCell) ?: return null
        return when (DrillDirection.fromSlug(stored.direction)) {
            DrillDirection.PRODUCE -> DrillItem.Produce(stored.subject, target).takeIf { known(it) }
            DrillDirection.DECODE -> DrillItem.Decode(stored.subject, target).takeIf { known(it) }

            DrillDirection.TRANSFORM -> {
                val source = Cell.fromSlug(stored.sourceCell) ?: return null
                val rule = TransformRules.ALL.firstOrNull {
                    it.sourceCell() == source && it.targetTense == target.tense
                } ?: return null
                DrillItem.Transform(stored.subject, rule, source, target).takeIf { known(it) }
            }

            DrillDirection.GENERALIZE -> {
                val cls = IrregularityClass.fromSlug(stored.subject) ?: return null
                val shown = stored.shownInfinitive ?: return null
                DrillItem.Generalize(cls, shown, target).takeIf { known(it) }
            }

            null -> null
        }
    }

    fun decodeAll(stored: List<ConjugationDrillItem>): List<DrillItem> = stored.mapNotNull(::decode)

    private fun known(item: DrillItem): Boolean = VerbLexicon.byInfinitive(item.infinitive) != null
}
