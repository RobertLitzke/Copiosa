package com.copiosa.domain.prompt

import com.copiosa.domain.model.Cefr
import com.copiosa.domain.routine.ActivityDefinition
import com.copiosa.domain.routine.ActivitySlot
import com.copiosa.domain.routine.ActivityType
import com.copiosa.domain.routine.ArousalBand
import com.copiosa.domain.routine.RoutineBlueprint
import com.copiosa.domain.routine.RoutineType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two routines share one generation call, so their difference lives entirely in the prompt's
 * register. A daytime routine that still asks for calm bedtime content would plan correctly and read
 * completely wrong — nothing else in the system would catch that.
 */
class RoutineContentPromptTest {

    private val prompt = RoutineContentPrompt()

    private fun blueprint(routineType: RoutineType, vararg types: ActivityType) = RoutineBlueprint(
        routineType = routineType,
        durationMinutes = 10,
        targetLanguage = "es",
        slots = types.map { type ->
            val def = ActivityDefinition[type]
            ActivitySlot(type, def.eligibleBands.first(), def.arousalWeight, def.estMinutes, 0)
        },
    )

    @Test
    fun `the bedtime prompt asks for calm and the daytime prompt does not`() {
        val bedtime = prompt.systemPrompt("Mexico", "es", "en", Cefr.B2, RoutineType.BEDTIME)
        assertTrue(bedtime.contains("calm bedtime practice routine"))
        assertTrue(bedtime.contains("calming"))

        val daytime = prompt.systemPrompt("Mexico", "es", "en", Cefr.B2, RoutineType.DAYTIME)
        assertTrue(daytime.contains("daytime activation routine"))
        assertTrue(daytime.contains("energetic"))
        assertFalse("a daytime routine must not ask for bedtime content", daytime.contains("bedtime"))
        assertFalse(daytime.contains("calming"))
    }

    @Test
    fun `the theme framing follows the time of day`() {
        val bedtime = prompt.userRequest(blueprint(RoutineType.BEDTIME, ActivityType.SHORT_TEXT), "el mar", null, emptyList())
        assertTrue(bedtime.contains("Tonight's theme: \"el mar\""))

        val daytime = prompt.userRequest(blueprint(RoutineType.DAYTIME, ActivityType.SHORT_TEXT), "el mar", null, emptyList())
        assertTrue(daytime.contains("Today's theme: \"el mar\""))
    }

    @Test
    fun `with no theme each routine asks for one of its own kind`() {
        val bedtime = prompt.userRequest(blueprint(RoutineType.BEDTIME, ActivityType.NARROW_READING), null, null, emptyList())
        assertTrue(bedtime.contains("sleep-friendly"))

        val daytime = prompt.userRequest(blueprint(RoutineType.DAYTIME, ActivityType.NARROW_READING), null, null, emptyList())
        assertTrue(daytime.contains("vivid, everyday"))
        assertFalse(daytime.contains("sleep-friendly"))
    }

    @Test
    fun `each slot gets its own request line, keyed by index`() {
        val request = prompt.userRequest(
            blueprint(RoutineType.DAYTIME, ActivityType.SHORT_TEXT, ActivityType.NARROW_READING),
            topic = null, topicContext = null, vocab = listOf("la marea"),
        )
        assertTrue(request.contains("- id 0: short_text"))
        assertTrue(request.contains("- id 1: narrow_reading"))
        assertTrue("seed vocab must be offered for recycling", request.contains("la marea"))
    }
}
