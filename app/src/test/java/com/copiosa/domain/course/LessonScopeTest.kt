package com.copiosa.domain.course

import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.conjugation.IrregularityClass
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.conjugation.VerbClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LessonScope.union] decides what a checkup may ask and what the routine engine may drill on a
 * course's behalf, so its all-or-nothing rule is worth pinning down: a narrowing survives only if
 * every scope carries one, because a null member means "no restriction" and no restriction wins.
 */
class LessonScopeTest {

    private val preterite = setOf(Tense.PRETERITE)

    @Test
    fun `a narrowing every scope shares survives, widened`() {
        val union = LessonScope.union(
            listOf(
                LessonScope(preterite, verbClasses = setOf(VerbClass.AR), regularOnly = true),
                LessonScope(preterite, verbClasses = setOf(VerbClass.ER), regularOnly = true),
            ),
        )!!
        assertEquals(setOf(VerbClass.AR, VerbClass.ER), union.verbClasses)
        assertTrue(union.regularOnly)
    }

    @Test
    fun `one unrestricted scope drops the narrowing entirely`() {
        val union = LessonScope.union(
            listOf(
                LessonScope(preterite, verbClasses = setOf(VerbClass.AR), persons = setOf(Person.YO)),
                LessonScope(preterite),
            ),
        )!!
        assertNull(union.verbClasses)
        assertNull(union.persons)
        assertFalse(union.regularOnly)
    }

    @Test
    fun `tenses and directions always merge`() {
        val union = LessonScope.union(
            listOf(
                LessonScope(preterite, directions = setOf(DrillDirection.DECODE)),
                LessonScope(setOf(Tense.IMPERFECT), directions = setOf(DrillDirection.PRODUCE)),
            ),
        )!!
        assertEquals(setOf(Tense.PRETERITE, Tense.IMPERFECT), union.tenses)
        assertEquals(setOf(DrillDirection.DECODE, DrillDirection.PRODUCE), union.directions)
    }

    @Test
    fun `an irregularity narrowing survives across the lessons that teach patterns`() {
        val union = LessonScope.union(
            listOf(
                LessonScope(preterite, irregularityClasses = setOf(IrregularityClass.STRONG_PRET_U)),
                LessonScope(preterite, irregularityClasses = setOf(IrregularityClass.STRONG_PRET_J)),
            ),
        )!!
        assertEquals(
            setOf(IrregularityClass.STRONG_PRET_U, IrregularityClass.STRONG_PRET_J),
            union.irregularityClasses,
        )
    }

    @Test
    fun `nothing to union is null`() {
        assertNull(LessonScope.union(emptyList()))
    }

    @Test
    fun `a person narrowing reaches the drill filter`() {
        val filter = LessonScope(preterite, persons = setOf(Person.YO, Person.TU)).verbFilter()
        assertEquals(setOf(Person.YO, Person.TU), filter?.persons)
    }

    @Test
    fun `a scope with no narrowing at all has no filter`() {
        assertNull(LessonScope(preterite).verbFilter())
    }
}
