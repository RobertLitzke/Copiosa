package com.copiosa.domain.course

import com.copiosa.domain.routine.ClozeItem
import com.copiosa.domain.routine.ConjugationDrillItem
import com.copiosa.domain.routine.PiItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonPlanCodecTest {

    private fun fullPlan() = LessonPlan(
        courseId = "es_preterite",
        lessonId = "regular_ar",
        targetLanguage = "es",
        sessionKind = SessionKind.LESSON,
        segments = listOf(
            DidacticSegment(
                title = "The -ar endings",
                blocks = listOf(
                    ProseBlock("Regular -ar verbs take -é, -aste, -ó..."),
                    TableBlock(tense = "pret", verbs = listOf("hablar", "trabajar")),
                    ExamplesBlock(
                        listOf(
                            ExampleItem("hablar", "pret.1s", "Ayer **hablé** con mi madre.", "Yesterday I spoke..."),
                        ),
                    ),
                ),
            ),
            ConjDrillSegment(
                items = listOf(ConjugationDrillItem("produce", "hablar", "pret.1s")),
                language = "es",
                warmUp = true,
            ),
            ClozeSegment(
                items = listOf(ClozeItem("Ayer ___ tarde.", listOf("llegué", "llego"), 0)),
                category = "preterite_imperfect",
            ),
            PiSegment(items = listOf(PiItem("Habló despacio.", listOf("yo", "él"), 1))),
            WrapUpSegment(summary = "Nice work."),
        ),
    )

    @Test
    fun `every segment kind round-trips`() {
        val plan = fullPlan()
        val decoded = LessonPlanCodec.decode(LessonPlanCodec.encode(plan))
        assertEquals(plan, decoded)
    }

    @Test
    fun `discriminators are the frozen names`() {
        val encoded = LessonPlanCodec.encode(fullPlan())
        for (kind in listOf("didactic", "conj_drill", "cloze", "pi", "wrap_up")) {
            assertTrue("missing \"$kind\"", encoded.contains("\"kind\":\"$kind\""))
        }
        for (block in listOf("prose", "table", "examples")) {
            assertTrue("missing block \"$block\"", encoded.contains("\"kind\":\"$block\""))
        }
    }

    @Test
    fun `an unknown segment kind is dropped, not fatal`() {
        val encoded = LessonPlanCodec.encode(fullPlan())
        // Splice a segment kind from a future build into the array.
        val withUnknown = encoded.replaceFirst(
            "{\"kind\":\"didactic\"",
            "{\"kind\":\"hologram_tutor\",\"minutes\":3},{\"kind\":\"didactic\"",
        )
        val decoded = LessonPlanCodec.decode(withUnknown)
        assertEquals(fullPlan().segments, decoded?.segments)
    }

    @Test
    fun `a malformed segment is dropped while its neighbours survive`() {
        val encoded = LessonPlanCodec.encode(fullPlan())
        // A cloze segment whose items are the wrong shape decodes to nothing; the rest still plays.
        val withBroken = encoded.replaceFirst(
            "{\"kind\":\"didactic\"",
            "{\"kind\":\"cloze\",\"items\":[{\"bogus\":true}]},{\"kind\":\"didactic\"",
        )
        val decoded = LessonPlanCodec.decode(withBroken)
        assertEquals(fullPlan().segments, decoded?.segments)
    }

    @Test
    fun `a plan with nothing playable decodes to null`() {
        assertNull(LessonPlanCodec.decode(null))
        assertNull(LessonPlanCodec.decode(""))
        assertNull(LessonPlanCodec.decode("not json"))
        assertNull(LessonPlanCodec.decode("""{"courseId":"x","targetLanguage":"es"}"""))
        assertNull(
            LessonPlanCodec.decode(
                """{"courseId":"x","targetLanguage":"es","sessionKind":"LESSON",""" +
                    """"segments":[{"kind":"hologram_tutor"}]}""",
            ),
        )
    }

    @Test
    fun `a checkup plan keeps its null lessonId`() {
        val plan = fullPlan().copy(lessonId = null, sessionKind = SessionKind.CHECKUP)
        val decoded = LessonPlanCodec.decode(LessonPlanCodec.encode(plan))
        assertNull(decoded?.lessonId)
        assertEquals(SessionKind.CHECKUP, decoded?.sessionKind)
    }

    @Test
    fun `an unknown session kind falls back to LESSON rather than failing the plan`() {
        val encoded = LessonPlanCodec.encode(fullPlan())
            .replace("\"sessionKind\":\"LESSON\"", "\"sessionKind\":\"POP_QUIZ\"")
        assertEquals(SessionKind.LESSON, LessonPlanCodec.decode(encoded)?.sessionKind)
    }
}
