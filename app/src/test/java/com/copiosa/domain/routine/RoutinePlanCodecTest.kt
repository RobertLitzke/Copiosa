package com.copiosa.domain.routine

import com.copiosa.domain.listening.RebuildItem
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
        ShortTextContent(TextFlavor.SERIAL_CHAPTER, "El faro", listOf("El guardián subió la escalera."), 3),
        RecognitionCardsContent(cardLimit = 10),
        MultipleChoiceClozeContent(listOf(ClozeItem("Bebo ___ por la mañana.", listOf("café", "mesa", "zapato"), 0, null))),
        SwipeSortContent(SortDimension.GENDER, listOf(SwipeItem("luna", 1), SwipeItem("sol", 0))),
        CognateHuntContent(listOf(CognateWord("animal"), CognateWord("embarazada", falseFriend = true, gloss = "pregnant"))),
        RapidFireContent("rf_numbers", listOf(RapidFireItem(1L, "/x/a.m4a", "73", listOf("73", "37"), 0)), 0.9f),
        ConjugationDrillContent(
            listOf(
                ConjugationDrillItem("produce", "tener", "pres.1s"),
                ConjugationDrillItem("transform", "saber", "sub_imp.1s", sourceCell = "pret.3p"),
                ConjugationDrillItem("generalize", "strong_pret_u", "pret.1s", shownInfinitive = "andar"),
            ),
        ),
        FrequentWordDrillContent(cardLimit = 12, maxRank = 500),
        ClozeBankContent(
            listOf(
                BankedClozeItem(
                    sentenceId = 42L,
                    item = ClozeItem("El ___ duerme en la casa.", listOf("gato", "perro", "coche"), 0, null),
                ),
            ),
        ),
        ListenRebuildContent(
            listOf(
                RebuildItem(
                    sentenceId = 7L,
                    sentence = "El gato duerme en la casa.",
                    tiles = listOf("el", "gato", "duerme", "en", "la", "casa"),
                    order = listOf(3, 0, 5, 1, 4, 2),
                    translation = "The cat sleeps in the house.",
                    audioPath = "/x/r.m4a",
                ),
            ),
        ),
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
            "short_text" to ShortTextContent::class,
            "recognition_cards" to RecognitionCardsContent::class,
            "multiple_choice_cloze" to MultipleChoiceClozeContent::class,
            "swipe_sort" to SwipeSortContent::class,
            "cognate_hunt" to CognateHuntContent::class,
            "rapid_fire_listening" to RapidFireContent::class,
            "conjugation_drill" to ConjugationDrillContent::class,
            "frequent_word_drill" to FrequentWordDrillContent::class,
            "cloze_bank" to ClozeBankContent::class,
            "listen_rebuild" to ListenRebuildContent::class,
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

    /**
     * An unknown "kind" fails the *whole* plan, not just the one activity — which is why superseded
     * content classes have to stay registered rather than being deleted. Documented here so the cost
     * of retiring a kind is visible at the point someone tries it.
     */
    @Test
    fun `decode returns null when a kind is unknown`() {
        val stored = """
            {"routineType":"BEDTIME","durationMinutes":5,"targetLanguage":"es","topic":null,
             "activities":[{"type":"NARROW_READING","band":"DRIFT","difficultyLevel":0,"category":null,
               "content":{"kind":"a_kind_that_never_existed","title":"x","paragraphs":["y"]}}]}
        """.trimIndent()
        assertNull(RoutinePlanCodec.decode(stored))
    }

    /**
     * Routines planned before the prose activities merged into [ActivityType.SHORT_TEXT] must still
     * decode, or every run left unfinished across the upgrade strands on the "preparing" spinner.
     */
    @Test
    fun `plans saved before the short-text merge still decode`() {
        val stored = """
            {"routineType":"BEDTIME","durationMinutes":10,"targetLanguage":"es","topic":"el mar",
             "activities":[
               {"type":"NARROW_READING","band":"SETTLE","difficultyLevel":0,"category":null,
                "content":{"kind":"serial_story","chapter":3,"title":"El faro",
                           "paragraphs":["El guardián subió la escalera."]}},
               {"type":"NARROW_READING","band":"DRIFT","difficultyLevel":0,"category":null,
                "content":{"kind":"bedtime_story","title":"Buenas noches",
                           "sentences":["El pueblo duerme.","La luna brilla."]}}]}
        """.trimIndent()
        val plan = RoutinePlanCodec.decode(stored)
        assertEquals(2, plan?.activities?.size)
        @Suppress("DEPRECATION")
        assertTrue(plan!!.activities[0].content is SerialStoryContent)
        @Suppress("DEPRECATION")
        assertTrue(plan.activities[1].content is BedtimeStoryContent)
    }

    private fun typeFor(content: ActivityContent): ActivityType = when (content) {
        is GuessThenFlipContent -> ActivityType.GUESS_THEN_FLIP
        is ReferentialPiContent -> ActivityType.REFERENTIAL_PI
        is ContrastClozeContent -> ActivityType.CONTRAST_CLOZE
        is NarrowReadingContent -> ActivityType.NARROW_READING
        is AffectivePiContent -> ActivityType.AFFECTIVE_PI
        is ShortTextContent -> ActivityType.SHORT_TEXT
        is RecognitionCardsContent -> ActivityType.RECOGNITION_CARDS
        is MultipleChoiceClozeContent -> ActivityType.MULTIPLE_CHOICE_CLOZE
        is SwipeSortContent -> ActivityType.SWIPE_SORT
        is CognateHuntContent -> ActivityType.COGNATE_HUNT
        is RapidFireContent -> ActivityType.RAPID_FIRE_LISTENING
        is ConjugationDrillContent -> ActivityType.CONJUGATION_DRILL
        is FrequentWordDrillContent -> ActivityType.FREQUENT_WORD_DRILL
        is ClozeBankContent -> ActivityType.CLOZE_BANK
        is ListenRebuildContent -> ActivityType.LISTEN_REBUILD
        // Legacy kinds have no live ActivityType; they are exercised by the decode tests below.
        else -> error("unmapped content ${content::class.simpleName}")
    }

    private fun categoryFor(content: ActivityContent): String? = when (content) {
        is ContrastClozeContent -> content.category
        is RapidFireContent -> content.topic
        else -> null
    }
}
