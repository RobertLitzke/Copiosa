package com.copiosa.domain.routine

import com.copiosa.domain.language.LanguageProfiles

import com.copiosa.domain.routine.activities.ShortText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one prose activity reshapes itself per routine rather than the library carrying three
 * near-identical types, so the flavour rule is where that merge can quietly go wrong.
 */
class ShortTextTest {

    private val slot = ActivitySlot(
        type = ActivityType.SHORT_TEXT,
        band = ArousalBand.DRIFT,
        arousalWeight = ShortText.definition.arousalWeight,
        estMinutes = ShortText.definition.estMinutes,
        difficultyLevel = 0,
    )

    private fun ctx(routineType: RoutineType, storyContext: String? = null) =
        RequestContext(routineType, durationMinutes = 10, storyContext = storyContext, profile = LanguageProfiles.SPANISH)

    @Test
    fun `daytime always motivates, even when a story thread exists`() {
        assertEquals(
            TextFlavor.DAYTIME_MOTIVATION,
            ShortText.flavorFor(RoutineType.DAYTIME, "the lighthouse keeper climbed the stairs"),
        )
    }

    @Test
    fun `bedtime continues a thread when there is one`() {
        assertEquals(
            TextFlavor.SERIAL_CHAPTER,
            ShortText.flavorFor(RoutineType.BEDTIME, "the lighthouse keeper climbed the stairs"),
        )
    }

    @Test
    fun `bedtime starts a story when there is no thread yet`() {
        assertEquals(TextFlavor.BEDTIME_STORY, ShortText.flavorFor(RoutineType.BEDTIME, null))
        assertEquals(TextFlavor.BEDTIME_STORY, ShortText.flavorFor(RoutineType.BEDTIME, "   "))
    }

    @Test
    fun `the request line asks for only the active flavour`() {
        val daytime = ShortText.requestLine(0, slot, ctx(RoutineType.DAYTIME))!!
        assertTrue("daytime should be second-person", daytime.contains("second-person"))
        assertTrue("daytime should rule out prose it isn't", daytime.contains("Not a story"))
        assertFalse("daytime must not ask for a chapter", daytime.contains("chapter"))

        val chapter = ShortText.requestLine(0, slot, ctx(RoutineType.BEDTIME, "so far: the keeper climbed"))!!
        assertTrue("chapter should carry the recap", chapter.contains("the keeper climbed"))
        assertTrue(chapter.contains("chapter"))

        val story = ShortText.requestLine(0, slot, ctx(RoutineType.BEDTIME))!!
        assertTrue(story.contains("bedtime story"))
        assertFalse("a fresh story has no recap to continue", story.contains("continue"))
    }

    @Test
    fun `every flavour keeps the slug the prompt contract expects`() {
        for (routineType in RoutineType.entries) {
            for (storyContext in listOf(null, "a recap")) {
                val line = ShortText.requestLine(3, slot, ctx(routineType, storyContext))!!
                assertTrue("request line must name its slot and slug: $line", line.startsWith("- id 3: short_text,"))
            }
        }
    }

    /**
     * `buildContent` isn't covered here: [ActivityBuildContext] carries a [RoutineContentValidator],
     * which needs a Context-bound LocalDictionary and so can't be built on the JVM — no descriptor's
     * content assembly is unit-tested for the same reason.
     */
    @Test
    fun `the definition fits the drift budget it has to be planned into`() {
        // Drift is 30% of a bedtime routine and 20% of a daytime one; at the shortest offered daytime
        // duration Drift is dropped entirely, so 10 minutes is the tightest budget it must fit.
        assertTrue(
            "short text at ${ShortText.definition.estMinutes}m cannot fit a 10m daytime Drift budget",
            ShortText.definition.estMinutes <= 10 * 0.20,
        )
    }
}
