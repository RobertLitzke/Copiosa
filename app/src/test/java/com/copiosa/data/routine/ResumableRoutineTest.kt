package com.copiosa.data.routine

import com.copiosa.data.local.RoutineRunEntity
import com.copiosa.domain.model.Cefr
import com.copiosa.domain.routine.ActivityInstance
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.GuessThenFlipContent
import com.copiosa.domain.routine.RecognitionCardsContent
import com.copiosa.domain.routine.RoutinePlan
import com.copiosa.domain.routine.RoutinePlanCodec
import com.copiosa.domain.routine.RoutineRunStatus
import com.copiosa.domain.routine.RoutineType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What an unfinished run looks like to the "continue" cards, and when it is not offered at all. */
class ResumableRoutineTest {

    private val threeSteps = RoutinePlanCodec.encode(
        RoutinePlan(
            routineType = RoutineType.BEDTIME,
            durationMinutes = 10,
            targetLanguage = "es",
            activities = listOf(
                ActivityInstance(ActivityType.GUESS_THEN_FLIP, ArousalBand.ENGAGE, content = GuessThenFlipContent(8)),
                ActivityInstance(ActivityType.RECOGNITION_CARDS, ArousalBand.SETTLE, content = RecognitionCardsContent(6)),
                ActivityInstance(ActivityType.RECOGNITION_CARDS, ArousalBand.DRIFT, content = RecognitionCardsContent(6)),
            ),
        ),
    )

    private fun run(status: RoutineRunStatus, planJson: String? = threeSteps, currentIndex: Int = 0) =
        RoutineRunEntity(
            id = 7,
            routineType = RoutineType.BEDTIME,
            durationMinutes = 10,
            targetLanguage = "es",
            level = Cefr.B1,
            status = status,
            planJson = planJson,
            currentIndex = currentIndex,
            createdAt = 0L,
        )

    @Test
    fun `a run left part-way resumes on the step it stopped at`() {
        val resumable = resumableOf(run(RoutineRunStatus.IN_PROGRESS, currentIndex = 1))!!
        assertEquals(7L, resumable.runId)
        assertEquals(2, resumable.step)
        assertEquals(3, resumable.total)
        assertFalse(resumable.preparing)
        assertFalse(resumable.notStarted)
    }

    @Test
    fun `a prepared run nobody opened reads as not started`() {
        assertTrue(resumableOf(run(RoutineRunStatus.READY))!!.notStarted)
    }

    @Test
    fun `a run still being prepared has no plan to count`() {
        val resumable = resumableOf(run(RoutineRunStatus.SYNTHESIZING, planJson = null))!!
        assertTrue(resumable.preparing)
        assertFalse(resumable.notStarted)
        assertEquals(0, resumable.total)
    }

    @Test
    fun `a run with nothing to open is not offered`() {
        // A plan that no longer decodes, and a position already past the last step.
        assertNull(resumableOf(run(RoutineRunStatus.IN_PROGRESS, planJson = "{\"not\":\"a plan\"}")))
        assertNull(resumableOf(run(RoutineRunStatus.IN_PROGRESS, currentIndex = 3)))
    }
}
