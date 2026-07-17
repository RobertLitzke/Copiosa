package com.copiosa.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Serialization stability for stored plans: every [ActivityContent] kind must round-trip through
 * [RoutinePlanCodec], and the JSON "kind" discriminators must stay exactly as first shipped —
 * changing one silently orphans every routine already saved in `planJson`.
 */
class RoutinePlanCodecTest {

    private val allContents: List<ActivityContent> = listOf(
        GuessThenFlipContent(cardLimit = 12),
        ReferentialPiContent(listOf(PiItem("Duerme temprano.", listOf("él", "yo"), 0, "3rd person"))),
        ContrastClozeContent("ser_estar", listOf(ClozeItem("La casa ___ grande.", listOf("es", "está"), 0, null))),
        NarrowReadingContent("El mar", listOf("Primer párrafo.", "Segundo párrafo.")),
        AffectivePiContent("¿Qué haces de noche?", listOf("Leo un libro.", "Escucho música.", "Miro las estrellas.")),
        BedtimeStoryContent("Buenas noches", listOf("El pueblo duerme.", "La luna brilla.")),
        RecognitionCardsContent(cardLimit = 10),
        MultipleChoiceClozeContent(listOf(ClozeItem("Bebo ___ por la mañana.", listOf("café", "mesa", "zapato"), 0, null))),
        SwipeSortContent(SortDimension.GENDER, listOf(SwipeItem("luna", 1), SwipeItem("sol", 0))),
        CognateHuntContent(listOf(CognateWord("animal"), CognateWord("embarazada", falseFriend = true, gloss = "pregnant"))),
        SerialStoryContent(3, "El faro", listOf("El guardián subió la escalera.")),
        RapidFireContent("rf_numbers", listOf(RapidFireItem(1L, "/x/a.m4a", "73", listOf("73", "37"), 0)), 0.9f),
    )

    @Test
    fun `every content kind round-trips through the codec`() {
        val plan = RoutinePlan(
            routineType = RoutineType.BEDTIME,
            durationMinutes = 15,
            targetLanguage = "es",
            topic = "the sea",
            activities = allContents.map { content ->
                ActivityInstance(typeFor(content), ArousalBand.SETTLE, 1, categoryFor(content), content)
            },
        )
        assertEquals(plan, RoutinePlanCodec.decode(RoutinePlanCodec.encode(plan)))
    }

    @Test
    fun `test covers every activity type`() {
        assertEquals(ActivityType.entries.toSet(), allContents.map { typeFor(it) }.toSet())
    }

    @Test
    fun `kind discriminators are stable`() {
        // These strings are persisted in every saved plan; additions are fine, changes are not.
        val expected = mapOf(
            "guess_then_flip" to GuessThenFlipContent::class,
            "referential_pi" to ReferentialPiContent::class,
            "contrast_cloze" to ContrastClozeContent::class,
            "narrow_reading" to NarrowReadingContent::class,
            "affective_pi" to AffectivePiContent::class,
            "bedtime_story" to BedtimeStoryContent::class,
            "recognition_cards" to RecognitionCardsContent::class,
            "multiple_choice_cloze" to MultipleChoiceClozeContent::class,
            "swipe_sort" to SwipeSortContent::class,
            "cognate_hunt" to CognateHuntContent::class,
            "serial_story" to SerialStoryContent::class,
            "rapid_fire_listening" to RapidFireContent::class,
        )
        for ((kind, klass) in expected) {
            val content = allContents.first { it::class == klass }
            val plan = RoutinePlan(RoutineType.BEDTIME, 5, "es", null,
                listOf(ActivityInstance(typeFor(content), ArousalBand.DRIFT, 0, null, content)))
            assertTrue(
                "encoded plan for $klass must carry kind=$kind",
                RoutinePlanCodec.encode(plan).contains("\"kind\":\"$kind\""),
            )
        }
    }

    @Test
    fun `decode is null-safe on garbage`() {
        assertNull(RoutinePlanCodec.decode(null))
        assertNull(RoutinePlanCodec.decode(""))
        assertNull(RoutinePlanCodec.decode("{not json"))
    }

    private fun typeFor(content: ActivityContent): ActivityType = when (content) {
        is GuessThenFlipContent -> ActivityType.GUESS_THEN_FLIP
        is ReferentialPiContent -> ActivityType.REFERENTIAL_PI
        is ContrastClozeContent -> ActivityType.CONTRAST_CLOZE
        is NarrowReadingContent -> ActivityType.NARROW_READING
        is AffectivePiContent -> ActivityType.AFFECTIVE_PI
        is BedtimeStoryContent -> ActivityType.BEDTIME_STORY
        is RecognitionCardsContent -> ActivityType.RECOGNITION_CARDS
        is MultipleChoiceClozeContent -> ActivityType.MULTIPLE_CHOICE_CLOZE
        is SwipeSortContent -> ActivityType.SWIPE_SORT
        is CognateHuntContent -> ActivityType.COGNATE_HUNT
        is SerialStoryContent -> ActivityType.SERIAL_STORY
        is RapidFireContent -> ActivityType.RAPID_FIRE_LISTENING
    }

    private fun categoryFor(content: ActivityContent): String? = when (content) {
        is ContrastClozeContent -> content.category
        is RapidFireContent -> content.topic
        else -> null
    }
}
