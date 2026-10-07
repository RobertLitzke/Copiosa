package com.copiosa.domain.course.data

import com.copiosa.domain.conjugation.Cell
import com.copiosa.domain.conjugation.DrillDirection
import com.copiosa.domain.conjugation.IrregularityClass
import com.copiosa.domain.conjugation.Person
import com.copiosa.domain.conjugation.Tense
import com.copiosa.domain.conjugation.VerbClass
import com.copiosa.domain.course.AuthoredExample
import com.copiosa.domain.course.AuthoredSegment
import com.copiosa.domain.course.CourseDefinition
import com.copiosa.domain.course.CoursePart
import com.copiosa.domain.course.DidacticBlock
import com.copiosa.domain.course.GraduationCardSpec
import com.copiosa.domain.course.LessonDefinition
import com.copiosa.domain.course.LessonScope
import com.copiosa.domain.course.LlmSlotKind

/**
 * The three Spanish past-tense courses: preterite from zero, imperfect from zero, then the contrast
 * between them. Ids and lesson ids are frozen (see [CourseDefinition]); prose is authored here and
 * only here, while every table and drill is generated from the spec so it cannot drift from what a
 * session grades. `CourseCatalogTest` replays every [AuthoredExample] with a cell through the
 * engine, so a typo in a verb form fails the build rather than teaching it.
 *
 * **The shape of a lesson.** A paradigm is six endings, and a learner meeting a tense for the first
 * time can hold two. So the form lessons advance a couple of persons at a time — *yo* and *tú*, then
 * the third person, then *nosotros* — with [LessonScope.persons] holding each drill to what has
 * actually been taught, and a consolidation lesson putting the whole paradigm back together before
 * the irregulars start. Teaching and practice interleave inside a sitting rather than stacking a
 * wall of prose in front of a wall of cards: read a little, use it, read the next wrinkle.
 */
internal fun spanishPastCourses(): List<CourseDefinition> = listOf(
    spanishPreterite(),
    spanishImperfect(),
    spanishPastContrast(),
)

// Shorthands: this file is content, and ceremony would bury it.
private fun prose(text: String) = DidacticBlock.Prose(text.trimIndent())
private fun table(tense: Tense, vararg verbs: String, persons: List<Person>? = null) =
    DidacticBlock.TableRef(tense, verbs.toList(), persons)
private fun examples(vararg items: AuthoredExample) = DidacticBlock.Examples(items.toList())
private fun ex(infinitive: String, cell: Cell?, sentence: String, gloss: String, note: String? = null) =
    AuthoredExample(infinitive, cell, sentence, gloss, note)
private fun part(title: String, from: String) = CoursePart(title, from)
private fun teach(title: String? = null, vararg blocks: DidacticBlock) =
    AuthoredSegment.Didactic(title, blocks.toList())
private fun drill(count: Int, emphasis: Double = 0.6, scope: LessonScope? = null) =
    AuthoredSegment.EngineDrill(count, emphasis, scope)
private fun cloze(count: Int, brief: String) =
    AuthoredSegment.LlmSlot(LlmSlotKind.CLOZE, count, brief.trimIndent())
private fun pi(count: Int, brief: String) =
    AuthoredSegment.LlmSlot(LlmSlotKind.PI, count, brief.trimIndent())

private fun pres(person: Person) = Cell(Tense.PRESENT, person)
private fun pret(person: Person) = Cell(Tense.PRETERITE, person)
private fun imp(person: Person) = Cell(Tense.IMPERFECT, person)

/** The person groupings the form lessons advance through, in the order they are introduced. */
private val YO_TU = setOf(Person.YO, Person.TU)
private val THIRD = setOf(Person.EL, Person.ELLOS)
private val SINGULAR = setOf(Person.YO, Person.TU, Person.EL)
private val PLURAL = setOf(Person.NOSOTROS, Person.ELLOS)
private val WE = setOf(Person.NOSOTROS)

/**
 * The two cells where the preterite of a regular verb writes its accent — *hablé*, *habló* — and so
 * the only two the opening recognition lesson can honestly ask about: they are the cells the
 * stress rule it teaches actually marks. *Hablaste*, *hablamos* and *hablaron* carry no accent, and
 * *hablamos* is not even distinct from the present.
 */
private val ACCENTED = setOf(Person.YO, Person.EL)

private val RECOGNISE = setOf(DrillDirection.DECODE)
private val PATTERN_DIRECTIONS = setOf(
    DrillDirection.PRODUCE, DrillDirection.DECODE, DrillDirection.GENERALIZE,
)

// ---------------------------------------------------------------------------------------------
// Preterite
// ---------------------------------------------------------------------------------------------

private fun spanishPreterite() = CourseDefinition(
    id = "es_preterite",
    language = "es",
    title = "The preterite",
    blurb = "Use this tense for completed events and sequential actions.",
    parts = listOf(
        part("Verbs that keep the pattern", from = "meaning"),
        part("Small changes that keep the sound", from = "orthographic"),
        part("Verbs that rebuild themselves", from = "strong_u"),
        part("Putting it to work", from = "usage_triggers"),
    ),
    lessons = listOf(

        // -- 1. meaning ------------------------------------------------------------------------
        LessonDefinition(
            id = "meaning",
            title = "Meaning of the preterite",
            tagline = "Understand its purpose and learn how the written accent marks the past tense.",
            estimatedMinutes = 10,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                directions = RECOGNISE,
                // Recognition of ordinary past forms: what this lesson claims, and what the next
                // lesson's warm-up may therefore ask about. Held to the accented cells, because
                // those are the only ones the stress rule taught here actually identifies — nothing
                // in this sitting equips a learner to place *comieron*.
                regularOnly = true,
                persons = ACCENTED,
            ),
            segments = listOf(
                teach(
                    "A tense for finished events",
                    prose(
                        """
                        The preterite (el pretérito indefinido) describes a **completed** event.
                        English typically expresses this with the simple past, such as *I spoke*,
                        *she arrived*, or *we ate*.

                        This tense drives the plot of a story by detailing sequential actions. It
                        answers the question *¿qué pasó?* (what happened?) and pairs naturally
                        with specific time frames, including *ayer*, *anoche*, *el lunes*, *en
                        2019*, *de repente*, and *durante dos horas*.
                        """,
                    ),
                    examples(
                        ex(
                            "hablar", pret(Person.YO),
                            "Ayer **hablé** con mi madre por teléfono.",
                            "Yesterday I spoke with my mother on the phone.",
                        ),
                        ex(
                            "llegar", pret(Person.EL),
                            "El tren **llegó** a las nueve en punto.",
                            "The train arrived at nine sharp.",
                        ),
                        ex(
                            "vivir", pret(Person.NOSOTROS),
                            "**Vivimos** en Sevilla durante tres años.",
                            "We lived in Seville for three years.",
                            note = "A long period — but a *closed* one, so it is still preterite.",
                        ),
                    ),
                ),
                teach(
                    "Spotting one in the wild",
                    prose(
                        """
                        You should learn to **recognise** these forms before producing them.
                        Spanish shifts the spoken stress to the ending to mark this tense and
                        indicates this shift with a written accent.

                        Notice the difference between *hablo* (I speak) and **habló** (he spoke).
                        The written accent changes both the subject and the timeline. Practicing
                        the pronunciation out loud, HA-blo versus ha-BLÓ, helps cement the
                        distinction.

                        The following exercises mix present and preterite forms for *yo* and
                        *él/ella*, which both carry a written accent in the past tense. Focus on
                        reading the four variations (*hablo · habla · hablé · habló*) to identify
                        the subject and determine if the action is ongoing or finished.
                        """,
                    ),
                    examples(
                        ex(
                            "hablar", pres(Person.YO),
                            "Normalmente **hablo** despacio.",
                            "I normally speak slowly.",
                            note = "Present: stress on the stem.",
                        ),
                        ex(
                            "hablar", pret(Person.EL),
                            "Anoche **habló** durante dos horas.",
                            "Last night he spoke for two hours.",
                            note = "Preterite: stress — and the accent — on the ending.",
                        ),
                        ex(
                            "comer", pret(Person.EL),
                            "**Comió** en el restaurante de la esquina.",
                            "She ate at the restaurant on the corner.",
                            note = "An -er verb, same rule: the accent is the tense.",
                        ),
                    ),
                ),
                drill(
                    12,
                    emphasis = 0.1,
                    scope = LessonScope(
                        // Present *and* preterite, so "which tense?" is a real question rather than
                        // a foregone conclusion — the whole point of a recognition warm-up.
                        tenses = setOf(Tense.PRESENT, Tense.PRETERITE),
                        directions = RECOGNISE,
                        regularOnly = true,
                        // Four cells, one rule: hablo · habla · hablé · habló. Every card is the
                        // contrast the lesson just taught, and none of them needs an ending the
                        // learner has not been shown.
                        persons = ACCENTED,
                    ),
                ),
            ),
        ),

        // -- 2. -ar, yo and tú -----------------------------------------------------------------
        LessonDefinition(
            id = "regular_ar",
            title = "-ar verbs: I and you",
            tagline = "Master the -é and -aste endings for the largest verb class in Spanish.",
            estimatedMinutes = 12,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                verbClasses = setOf(VerbClass.AR),
                regularOnly = true,
                persons = YO_TU,
            ),
            segments = listOf(
                teach(
                    "Two endings to start with",
                    prose(
                        """
                        Nearly half of all Spanish verbs end in **-ar**. You form their preterite
                        by dropping the -ar and appending a new ending. We will start with the
                        first and second person singular:

                        **yo → -é** · *hablar → hablé* (I spoke)

                        **tú → -aste** · *hablar → hablaste* (you spoke)

                        The accent on **-é** places the vocal stress on the final syllable to
                        signify the past tense. For example, *hablo* translates to "I speak",
                        while **hablé** means "I spoke". Omitting the accent changes the meaning
                        of the word entirely.
                        """,
                    ),
                    table(Tense.PRETERITE, "hablar", "trabajar", "comprar", persons = listOf(Person.YO, Person.TU)),
                    examples(
                        ex(
                            "trabajar", pret(Person.YO),
                            "**Trabajé** todo el fin de semana.",
                            "I worked all weekend.",
                        ),
                        ex(
                            "comprar", pret(Person.TU),
                            "¿**Compraste** el pan esta mañana?",
                            "Did you buy the bread this morning?",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                teach(
                    "-aste never takes an accent",
                    prose(
                        """
                        The form **hablaste** takes no written accent because the natural stress
                        falls on the expected syllable. You might occasionally hear *hablastes* in
                        casual speech, but adding that final -s is grammatically incorrect.

                        Keep the written accent on the *yo* form and leave the *tú* form
                        unaccented. These two variations allow you to discuss your own recent
                        actions and those of the person you are addressing.
                        """,
                    ),
                    examples(
                        ex(
                            "caminar", pret(Person.TU),
                            "**Caminaste** hasta la estación bajo la lluvia.",
                            "You walked to the station in the rain.",
                        ),
                        ex(
                            "cenar", pret(Person.YO),
                            "Anoche **cené** con mis padres.",
                            "Last night I had dinner with my parents.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                pi(
                    4,
                    """
                    Referential items on regular -ar preterite forms, first and second person
                    singular only (yo and tú — no other subject may appear). A pro-dropped sentence
                    whose ending alone identifies the subject; options are the candidate subjects.
                    Focus on the -é / -aste contrast.
                    """,
                ),
            ),
        ),

        // -- 3. -ar, third person --------------------------------------------------------------
        LessonDefinition(
            id = "ar_third",
            title = "-ar verbs: he, she, they",
            tagline = "Learn the -ó and -aron endings to complete the primary third-person forms.",
            estimatedMinutes = 12,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                verbClasses = setOf(VerbClass.AR),
                regularOnly = true,
                persons = THIRD,
            ),
            segments = listOf(
                teach(
                    "The other two you need every day",
                    prose(
                        """
                        Describing other people requires two different endings:

                        **él / ella / usted → -ó** · *hablar → habló*

                        **ellos / ellas / ustedes → -aron** · *hablar → hablaron*

                        The accented **-ó** clearly distinguishes the third-person past from the
                        first-person present. The only visual difference between *hablo* (I speak)
                        and **habló** (he spoke) is that final accent mark.
                        """,
                    ),
                    table(Tense.PRETERITE, "hablar", "trabajar", "llamar", persons = listOf(Person.EL, Person.ELLOS)),
                    examples(
                        ex(
                            "llamar", pret(Person.EL),
                            "Tu hermana te **llamó** dos veces.",
                            "Your sister called you twice.",
                        ),
                        ex(
                            "trabajar", pret(Person.ELLOS),
                            "Mis padres **trabajaron** en el campo toda su vida.",
                            "My parents worked in the fields all their lives.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                teach(
                    "Now four of the six",
                    prose(
                        """
                        Reviewing the full sequence reveals a consistent structure:

                        *hablé · hablaste · habló · … · … · hablaron*

                        The **-é** and **-ó** endings require accents to mark their stress. The **-aste**
                        and **-aron** endings carry their stress naturally and remain
                        unaccented. The upcoming drill mixes all four variations to test your
                        recognition of the correct grammatical person.
                        """,
                    ),
                    examples(
                        ex(
                            "esperar", pret(Person.ELLOS),
                            "**Esperaron** una hora y se fueron.",
                            "They waited an hour and left.",
                        ),
                        ex(
                            "mirar", pret(Person.EL),
                            "Ella me **miró** sin decir nada.",
                            "She looked at me without saying anything.",
                        ),
                    ),
                ),
                drill(
                    8,
                    emphasis = 0.1,
                    scope = LessonScope(
                        tenses = setOf(Tense.PRETERITE),
                        verbClasses = setOf(VerbClass.AR),
                        regularOnly = true,
                        persons = YO_TU + THIRD,
                    ),
                ),
                pi(
                    4,
                    """
                    Referential items on regular -ar preterite forms in the third person only —
                    the subject is either one person (él/ella) or several (ellos/ellas), and the
                    -ó / -aron ending is the only thing that says which.
                    """,
                ),
            ),
        ),

        // -- 4. -ar, nosotros + whole paradigm ---------------------------------------------------
        LessonDefinition(
            id = "ar_nosotros",
            title = "-ar verbs: the first-person plural",
            tagline = "Identify and use the -amos ending, which shares its spelling with the present tense.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                verbClasses = setOf(VerbClass.AR),
                regularOnly = true,
            ),
            segments = listOf(
                teach(
                    "-amos, which you already know",
                    prose(
                        """
                        The *nosotros* preterite ending for an -ar verb is **-amos**: *hablamos*,
                        *trabajamos*, *compramos*.

                        These conjugations are identical to their **present** tense counterparts.
                        Sentences like *hablamos todos los días* (we speak every day) and
                        *hablamos ayer* (we spoke yesterday) rely entirely on context. A time
                        expression usually clarifies when the action took place.

                        Whenever you encounter an *-amos* ending, look for context clues in the
                        rest of the sentence to determine if it refers to the present or the past.
                        """,
                    ),
                    table(Tense.PRETERITE, "hablar", "trabajar", "comprar"),
                    examples(
                        ex(
                            "hablar", pret(Person.NOSOTROS),
                            "Ayer **hablamos** dos horas por teléfono.",
                            "Yesterday we talked for two hours on the phone.",
                            note = "*Ayer* is doing all the work — the form itself is ambiguous.",
                        ),
                        ex(
                            "comprar", pret(Person.NOSOTROS),
                            "**Compramos** la casa en 2019.",
                            "We bought the house in 2019.",
                        ),
                    ),
                ),
                drill(
                    6,
                    emphasis = 0.1,
                    scope = LessonScope(
                        tenses = setOf(Tense.PRETERITE),
                        verbClasses = setOf(VerbClass.AR),
                        regularOnly = true,
                        persons = WE,
                    ),
                ),
                teach(
                    "The complete -ar conjugation pattern",
                    prose(
                        """
                        These are all the preterite endings for the -ar verb class:

                        **-é · -aste · -ó · -amos · -asteis · -aron**

                        The *-asteis* ending corresponds to *vosotros*, which is used in Spain.
                        Latin American Spanish relies on *ustedes* instead.

                        The remaining exercises cover the entire group, requiring you to identify
                        or produce the correct form for any subject.
                        """,
                    ),
                ),
                drill(14, emphasis = 0.1),
                cloze(
                    4,
                    """
                    Cloze sentences on regular -ar preterite verbs across all persons. Each sentence
                    carries a past-time marker (ayer, anoche, la semana pasada, el lunes). Options:
                    the correct preterite form and the same verb's present-tense form for that
                    person — so the learner must read the time marker and the ending. Include one
                    nosotros item, where only the time marker can decide.
                    """,
                ),
            ),
        ),

        // -- 5. -er/-ir, yo and tú ---------------------------------------------------------------
        LessonDefinition(
            id = "regular_er_ir",
            title = "First and second person for -er and -ir verbs",
            tagline = "Learn the -í and -iste endings shared by regular -er and -ir verbs.",
            estimatedMinutes = 12,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                verbClasses = setOf(VerbClass.ER, VerbClass.IR),
                regularOnly = true,
                persons = YO_TU,
            ),
            segments = listOf(
                teach(
                    "One set of endings for two classes",
                    prose(
                        """
                        Regular **-er and -ir verbs share identical preterite endings**. Mastering
                        one set allows you to conjugate both verb classes, starting with the first
                        and second person singular:

                        **yo → -í** · *comer → comí*, *vivir → viví*

                        **tú → -iste** · *comer → comiste*, *vivir → viviste*

                        Similar to the -ar class, the *yo* form requires a written accent to mark
                        the stressed final syllable. The *tú* form follows natural stress rules
                        and remains unaccented.
                        """,
                    ),
                    table(Tense.PRETERITE, "comer", "vivir", "escribir", persons = listOf(Person.YO, Person.TU)),
                    examples(
                        ex(
                            "comer", pret(Person.YO),
                            "Anoche **comí** demasiado.",
                            "Last night I ate too much.",
                        ),
                        ex(
                            "escribir", pret(Person.TU),
                            "¿Le **escribiste** al profesor?",
                            "Did you write to the teacher?",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                teach(
                    "Three classes, two patterns",
                    prose(
                        """
                        Comparing the conjugation systems reveals a straightforward underlying
                        structure:

                        **-ar**: hablé, hablaste · **-er/-ir**: comí, comiste

                        The endings primarily differ by their vowel. The first class uses *a*,
                        while the other two rely on *i*. Knowing the infinitive's final vowel
                        provides the blueprint for its preterite form.
                        """,
                    ),
                    examples(
                        ex(
                            "aprender", pret(Person.YO),
                            "**Aprendí** a nadar de niño.",
                            "I learned to swim as a child.",
                        ),
                        ex(
                            "subir", pret(Person.TU),
                            "**Subiste** las escaleras muy rápido.",
                            "You went up the stairs very fast.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                pi(
                    4,
                    """
                    Referential items on regular -er/-ir preterite forms, first and second person
                    singular only (yo and tú — no other subject may appear). Focus on the -í /
                    -iste contrast.
                    """,
                ),
            ),
        ),

        // -- 6. -er/-ir, third person ------------------------------------------------------------
        LessonDefinition(
            id = "er_ir_third",
            title = "Third person for -er and -ir verbs",
            tagline = "Conjugate third-person subjects using the -ió and -ieron endings.",
            estimatedMinutes = 12,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                verbClasses = setOf(VerbClass.ER, VerbClass.IR),
                regularOnly = true,
                persons = THIRD,
            ),
            segments = listOf(
                teach(
                    "-ió and -ieron",
                    prose(
                        """
                        The third-person endings for regular -er and -ir verbs are as follows:

                        **él / ella / usted → -ió** · *comer → comió*, *escribir → escribió*

                        **ellos / ellas / ustedes → -ieron** · *comieron*, *escribieron*

                        The singular form requires an accent mark. Comparing *como* (I eat) with **comió**
                        (he ate) shows how the accent alters both the subject and the
                        tense.
                        """,
                    ),
                    table(Tense.PRETERITE, "comer", "escribir", "recibir", persons = listOf(Person.EL, Person.ELLOS)),
                    examples(
                        ex(
                            "escribir", pret(Person.EL),
                            "Ella me **escribió** un mensaje muy largo.",
                            "She wrote me a very long message.",
                        ),
                        ex(
                            "recibir", pret(Person.ELLOS),
                            "**Recibieron** la carta la semana pasada.",
                            "They received the letter last week.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                teach(
                    "Reviewing the pattern",
                    prose(
                        """
                        *comí · comiste · comió · … · … · comieron*

                        This structure mirrors the -ar pattern but swaps the *a* for an *i*. To
                        find the correct conjugation, identify the infinitive's verb class and
                        then determine the subject.
                        """,
                    ),
                ),
                drill(
                    8,
                    emphasis = 0.1,
                    scope = LessonScope(
                        tenses = setOf(Tense.PRETERITE),
                        verbClasses = setOf(VerbClass.ER, VerbClass.IR),
                        regularOnly = true,
                        persons = YO_TU + THIRD,
                    ),
                ),
                cloze(
                    4,
                    """
                    Cloze sentences with a past-time marker (ayer, anoche, la semana pasada) and a
                    blanked regular -er or -ir preterite verb, in the third person singular or
                    plural only. Options: the correct preterite form and the same verb's
                    present-tense form for that person.
                    """,
                ),
            ),
        ),

        // -- 7. -er/-ir, nosotros ----------------------------------------------------------------
        LessonDefinition(
            id = "er_ir_nosotros",
            title = "-er and -ir verbs: the first-person plural",
            tagline = "Apply the -imos ending and distinguish between ambiguous and clear forms.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                verbClasses = setOf(VerbClass.ER, VerbClass.IR),
                regularOnly = true,
            ),
            segments = listOf(
                teach(
                    "Using the -imos ending",
                    prose(
                        """
                        Both verb classes use **-imos** for the *nosotros* form, as seen in
                        *comimos* and *vivimos*. However, this shared ending interacts differently
                        with the present tense for each class:

                        The **-er verbs remain distinct.** The present tense *comemos* uses a
                        different vowel than the preterite **comimos**.

                        The **-ir verbs overlap with the present.** The form **vivimos** serves as
                        both the present and the preterite, similar to the -ar *nosotros*
                        conjugations. You must rely on sentence context to tell them apart.

                        Consequently, only the -er class provides a unique first-person plural
                        form for the past tense.
                        """,
                    ),
                    table(Tense.PRETERITE, "comer", "vivir", "salir"),
                    examples(
                        ex(
                            "comer", pret(Person.NOSOTROS),
                            "Ayer **comimos** en casa de mi tía.",
                            "Yesterday we ate at my aunt's house.",
                            note = "Present would be *comemos* — this one can only be past.",
                        ),
                        ex(
                            "salir", pret(Person.NOSOTROS),
                            "**Salimos** del cine a medianoche.",
                            "We left the cinema at midnight.",
                            note = "An -ir verb: identical to the present, so the sentence has to tell you.",
                        ),
                    ),
                ),
                drill(
                    6,
                    emphasis = 0.1,
                    scope = LessonScope(
                        tenses = setOf(Tense.PRETERITE),
                        verbClasses = setOf(VerbClass.ER, VerbClass.IR),
                        regularOnly = true,
                        persons = WE,
                    ),
                ),
                teach(
                    "The full second pattern",
                    prose(
                        """
                        **-í · -iste · -ió · -imos · -isteis · -ieron**

                        These conjugations cover the entire regular preterite across all three
                        verb classes. The remaining lessons focus on specific deviations from
                        these standard patterns.
                        """,
                    ),
                ),
                drill(14, emphasis = 0.1),
                cloze(
                    4,
                    """
                    Cloze sentences on regular -er and -ir preterite verbs across all persons.
                    Options: the correct preterite form and the same verb's present-tense form for
                    that person. Include one -ir nosotros item (where the two are identical and only
                    the time marker decides) and one -er nosotros item (where the vowel decides).
                    """,
                ),
            ),
        ),

        // -- 8. all regular together --------------------------------------------------------------
        LessonDefinition(
            id = "regular_review",
            title = "All three classes at once",
            tagline = "Practice all three regular verb classes together to reinforce your recognition.",
            estimatedMinutes = 15,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                regularOnly = true,
            ),
            segments = listOf(
                teach(
                    "The regular preterite, whole",
                    prose(
                        """
                        This review section tests all the regular endings you have learned without
                        grouping them by verb class. Identifying the correct pattern from the
                        infinitive alone mimics natural reading and speaking.

                        Keep these two rules in mind during the exercises:

                        The **accent** on the *yo* and *él* forms is a mandatory spelling
                        requirement that indicates the tense.

                        The *nosotros*

                        **form** for -ar and -ir verbs overlaps completely with the present tense.
                        Look for context clues in the surrounding sentence.
                        """,
                    ),
                    table(Tense.PRETERITE, "hablar", "comer", "vivir"),
                ),
                drill(20, emphasis = 0.05),
                cloze(
                    4,
                    """
                    Mixed cloze across all three regular classes and all persons, each sentence with
                    a clear past-time marker. Options: the correct preterite form and a plausible
                    wrong one — the present tense of the same person, or the preterite of a
                    neighbouring person (hablé / habló).
                    """,
                ),
            ),
        ),

        // -- 9. -car/-gar/-zar ---------------------------------------------------------------------
        LessonDefinition(
            id = "orthographic",
            title = "Spelling changes: busqué, llegué, empecé",
            tagline = "Learn how verbs like buscar adjust their spelling to maintain pronunciation.",
            estimatedMinutes = 13,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                directions = PATTERN_DIRECTIONS,
                irregularityClasses = setOf(IrregularityClass.ORTH_CAR_GAR_ZAR),
            ),
            segments = listOf(
                teach(
                    "Adjusting spelling for pronunciation",
                    prose(
                        """
                        Verbs ending in **-car, -gar, and -zar** maintain regular pronunciation in
                        the past tense. They require a minor spelling adjustment in the *yo* form
                        to preserve their consonant sounds before the -é ending.

                        *buscar* → **busqué** · *llegar* → **llegué** · *empezar* → **empecé**

                        Standard Spanish orthography uses **qu**, **gu**, and **c** to accurately
                        represent the /k/, /g/, and /s/ sounds in these contexts. The rest of the
                        paradigm follows the standard regular spelling.
                        """,
                    ),
                    table(Tense.PRETERITE, "buscar", "llegar", "empezar"),
                    examples(
                        ex(
                            "buscar", pret(Person.YO),
                            "Te **busqué** por todas partes.",
                            "I looked for you everywhere.",
                        ),
                        ex(
                            "llegar", pret(Person.YO),
                            "**Llegué** tarde a la reunión.",
                            "I arrived late to the meeting.",
                        ),
                        ex(
                            "buscar", pret(Person.TU),
                            "¿Dónde me **buscaste**?",
                            "Where did you look for me?",
                            note = "Outside the *yo* form nothing changes at all.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.85),
                teach(
                    "Why it is worth the trouble",
                    prose(
                        """
                        This spelling rule applies to high-frequency verbs, including *buscar,
                        llegar, empezar, pagar, tocar, sacar, jugar, explicar,* and *comenzar*.
                        You will use the *-qué*, *-gué*, or *-cé* endings frequently when speaking
                        in the first person.

                        You only need to shift the spelling when conjugating **-car**, **-gar**,
                        or **-zar** verbs for the *yo* subject.
                        """,
                    ),
                    examples(
                        ex(
                            "pagar", pret(Person.YO),
                            "**Pagué** la cuenta con tarjeta.",
                            "I paid the bill by card.",
                        ),
                        ex(
                            "tocar", pret(Person.YO),
                            "**Toqué** el piano en la fiesta.",
                            "I played the piano at the party.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.9),
                cloze(
                    4,
                    """
                    Cloze sentences in the first person singular on -car/-gar/-zar verbs (buscar,
                    llegar, empezar, pagar, tocar, sacar, jugar). Options: the correct spelling and
                    the naive one a learner would write (*buscé*, *llegé*, *empezé*).
                    """,
                ),
            ),
        ),

        // -- 10. i -> y -----------------------------------------------------------------------------
        LessonDefinition(
            id = "orth_y_hiatus",
            title = "Spelling shifts from i to y",
            tagline = "Conjugate verbs with vowel stems by changing the unstressed i to a y.",
            estimatedMinutes = 13,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                directions = PATTERN_DIRECTIONS,
                irregularityClasses = setOf(IrregularityClass.ORTH_Y_HIATUS),
            ),
            segments = listOf(
                teach(
                    "Avoiding consecutive vowels",
                    prose(
                        """
                        Another spelling adjustment occurs when a verb's stem ends in a **vowel**,
                        as seen in *leer*, *creer*, *oír*, and *caer*. To prevent the third-person
                        endings *-ió* and *-ieron* from stacking three vowels consecutively
                        (*le-i-ó*), the *i* changes to a **y**:

                        *leer* → **leyó**, **leyeron** · *creer* → **creyó**, **creyeron** · *oír*
                        → **oyó**, **oyeron**

                        This shift only applies to the third person. The remaining forms retain
                        their *i* and add a written accent to separate the vowels, resulting in
                        conjugations like *leí, leíste,* and *leímos*.
                        """,
                    ),
                    table(Tense.PRETERITE, "leer", "creer", "oír"),
                    examples(
                        ex(
                            "leer", pret(Person.ELLOS),
                            "Los niños **leyeron** el cartel en voz alta.",
                            "The children read the sign out loud.",
                        ),
                        ex(
                            "creer", pret(Person.EL),
                            "Nadie le **creyó**.",
                            "Nobody believed him.",
                        ),
                        ex(
                            "leer", pret(Person.YO),
                            "Anoche **leí** el informe entero.",
                            "Last night I read the whole report.",
                            note = "First person: the *i* stays, and takes an accent.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.85),
                teach(
                    "The same shape, a wider family",
                    prose(
                        """
                        This orthographic rule also applies to any verb ending in *-uir*, yielding
                        forms like *construir* → **construyó**, *destruir* → **destruyó**, and
                        *huir* → **huyó**. The verb *caer* follows the same pattern to become **cayó**.

                        These two spelling rules—the **-qué/-gué/-cé** endings for the first
                        person and the **-y-** substitution for the third person—maintain the
                        verb's pronunciation. Subsequent lessons will introduce irregular
                        conjugations that alter the root sound.
                        """,
                    ),
                    examples(
                        ex(
                            "caer", pret(Person.EL),
                            "El vaso se **cayó** de la mesa.",
                            "The glass fell off the table.",
                        ),
                        ex(
                            "construir", pret(Person.ELLOS),
                            "**Construyeron** la casa en seis meses.",
                            "They built the house in six months.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.9),
                cloze(
                    4,
                    """
                    Cloze sentences in the third person on vowel-stem verbs (leer, creer, oír, caer,
                    construir, destruir, huir). Options: the correct -y- form and the naive one
                    (*leió*, *creyeron* vs *creieron*, *oió*).
                    """,
                ),
            ),
        ),

        // -- 11. stem raise ---------------------------------------------------------------------------
        LessonDefinition(
            id = "stem_raise",
            title = "Stem-changing -ir verbs",
            tagline = "Identify the -ir verbs that shift their stem vowel in the third person.",
            estimatedMinutes = 13,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                directions = PATTERN_DIRECTIONS,
                irregularityClasses = setOf(IrregularityClass.STEM_RAISE_PRET),
            ),
            segments = listOf(
                teach(
                    "Vowel shifts in the third person",
                    prose(
                        """
                        Certain **-ir** verbs that have a stem change in the present tense also
                        shift their vowels in the preterite. For these verbs, the stem vowel **changes
                        only in the third person**, shifting *e* to *i* and *o* to *u*.

                        *pedir* → pedí, pediste, **pidió**, pedimos, …, **pidieron**

                        *dormir* → dormí, dormiste, **durmió**, dormimos, …, **durmieron**

                        The rest of the paradigm follows standard regular -ir conjugation rules.
                        The modification strictly affects the verb's stem.
                        """,
                    ),
                    table(
                        Tense.PRETERITE, "pedir", "dormir",
                        persons = listOf(Person.YO, Person.EL, Person.ELLOS),
                    ),
                    examples(
                        ex(
                            "pedir", pret(Person.EL),
                            "Él **pidió** la cuenta sin mirarme.",
                            "He asked for the bill without looking at me.",
                        ),
                        ex(
                            "dormir", pret(Person.ELLOS),
                            "Los invitados **durmieron** hasta el mediodía.",
                            "The guests slept until noon.",
                        ),
                        ex(
                            "pedir", pret(Person.YO),
                            "Yo **pedí** lo mismo de siempre.",
                            "I ordered the same as always.",
                            note = "First person: no raise — *pedí*, not *pidí*.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.85),
                teach(
                    "Identifying stem-changing verbs",
                    prose(
                        """
                        This rule applies exclusively to **-ir** verbs with present-tense stem
                        changes. Common examples include *pedir, servir, vestir, seguir,
                        conseguir, elegir, sentir, mentir, preferir,* and *convertir* (which shift
                        *e → i*), along with *dormir* and *morir* (which shift *o → u*).

                        Verbs ending in **-er** do not undergo this change in the preterite,
                        regardless of their present-tense behavior. For instance, *volver* becomes **volvió**
                        and *perder* becomes **perdió**. You should only apply this
                        vowel shift to -ir verbs.
                        """,
                    ),
                    examples(
                        ex(
                            "seguir", pret(Person.EL),
                            "**Siguió** hablando como si nada.",
                            "He carried on talking as if nothing had happened.",
                        ),
                        ex(
                            "volver", pret(Person.EL),
                            "**Volvió** a casa muy tarde.",
                            "He came back home very late.",
                            note = "An -er verb: no raise, despite *vuelve* in the present.",
                        ),
                    ),
                ),
                drill(10, emphasis = 0.9),
                pi(
                    4,
                    """
                    Referential items on stem-raising -ir verbs in the preterite (pedir, dormir,
                    servir, seguir, sentir, morir). A pro-dropped sentence whose ending identifies
                    the subject; contrast third-person raised forms (pidió, durmieron) with
                    first/second-person unraised ones (pedí, dormiste) so the raise itself is the
                    clue.
                    """,
                ),
            ),
        ),

        // -- 12. strong preterites, u-stems ------------------------------------------------------------
        LessonDefinition(
            id = "strong_u",
            title = "Irregular preterites: the u-stems",
            tagline = "Conjugate irregular verbs like tener and poder using modified stems and unaccented endings.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                directions = PATTERN_DIRECTIONS,
                irregularityClasses = setOf(IrregularityClass.STRONG_PRET_U),
            ),
            segments = listOf(
                teach(
                    "A new stem, and endings that lose their stress",
                    prose(
                        """
                        A specific group of high-frequency verbs features highly irregular
                        preterite conjugations.

                        These **irregular preterites** use a specialized stem combined with a
                        unique set of endings: **-e, -iste, -o, -imos, -isteis, -ieron**. The
                        first and third person singular endings (**-e** and **-o**) remain
                        unaccented and unstressed.

                        While regular verbs like *hablé* and *habló* place the stress on the final
                        vowel, irregular verbs like **tuve** and **tuvo** place the vocal stress
                        on the stem.

                        Irregular preterite forms never use written accents. Be careful with words
                        like *tuvo*, which translates to **he had** despite ending in an -o.
                        """,
                    ),
                    table(Tense.PRETERITE, "tener", "estar", "poder"),
                    examples(
                        ex(
                            "tener", pret(Person.YO),
                            "**Tuve** que salir corriendo.",
                            "I had to leave in a hurry.",
                        ),
                        ex(
                            "tener", pret(Person.EL),
                            "**Tuvo** mucha suerte aquel día.",
                            "He was very lucky that day.",
                            note = "Same verb, one letter apart, and no accent on either.",
                        ),
                        ex(
                            "estar", pret(Person.NOSOTROS),
                            "**Estuvimos** allí toda la tarde.",
                            "We were there all afternoon.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.9),
                teach(
                    "The u-stem family",
                    prose(
                        """
                        The first subgroup of irregular verbs replaces its core vowel with a **u**:

                        *tener* → **tuv-** · *estar* → **estuv-** · *poder* → **pud-** · *poner* → **pus-**
                        · *saber* → **sup-** · *haber* → **hub-**

                        Once you identify the modified stem, the unaccented endings apply
                        consistently. Any compound verb based on roots like *tener* or *poner*
                        inherits the same stem change, producing forms like *mantuvo*, *detuvo*,
                        *supuso*, and *obtuvo*.
                        """,
                    ),
                    examples(
                        ex(
                            "poder", pret(Person.YO),
                            "Ayer no **pude** dormir bien.",
                            "Yesterday I couldn't sleep well.",
                        ),
                        ex(
                            "poner", pret(Person.ELLOS),
                            "**Pusieron** la mesa entre los dos.",
                            "The two of them set the table.",
                        ),
                        ex(
                            "saber", pret(Person.YO),
                            "Lo **supe** ayer por la tarde.",
                            "I found out yesterday afternoon.",
                            note = "*Supe* = found out. The preterite of a state verb is the moment you entered it.",
                        ),
                    ),
                ),
                drill(10, emphasis = 0.95),
                cloze(
                    4,
                    """
                    Cloze sentences blanking a u-stem strong preterite (tener, estar, poder, poner,
                    saber). Options: the correct strong form and the over-regularised form a learner
                    would invent (*tenió* for *tuvo*, *podió* for *pudo*, *ponió* for *puso*).
                    """,
                ),
            ),
        ),

        // -- 13. strong preterites, i- and j-stems ------------------------------------------------------
        LessonDefinition(
            id = "strong_i_j",
            title = "Irregular preterites: i-stems and j-stems",
            tagline = "Master the irregular stems containing i or j and their corresponding unaccented endings.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                directions = PATTERN_DIRECTIONS,
                irregularityClasses = setOf(
                    IrregularityClass.STRONG_PRET_I,
                    IrregularityClass.STRONG_PRET_J,
                ),
            ),
            segments = listOf(
                teach(
                    "The i-stems",
                    prose(
                        """
                        This second group of irregular verbs uses the same unaccented endings
                        (**-e, -iste, -o, -imos, -isteis, -ieron**) attached to stems containing
                        an **i**:

                        *hacer* → **hic-** · *querer* → **quis-** · *venir* → **vin-**

                        The verb *hacer* requires a minor spelling adjustment in the third person.
                        To maintain the soft consonant sound, it is written as **hizo** instead of
                        *hico*.
                        """,
                    ),
                    table(Tense.PRETERITE, "hacer", "venir", "querer"),
                    examples(
                        ex(
                            "hacer", pret(Person.EL),
                            "¿Quién **hizo** la cena anoche?",
                            "Who made dinner last night?",
                        ),
                        ex(
                            "venir", pret(Person.TU),
                            "¿Por qué no **viniste** a la fiesta?",
                            "Why didn't you come to the party?",
                        ),
                        ex(
                            "hacer", pret(Person.YO),
                            "**Hice** todo lo que pude.",
                            "I did everything I could.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.9),
                teach(
                    "The j-stems drop an i",
                    prose(
                        """
                        The final group of irregular verbs utilizes a stem ending in **j**, which
                        triggers a slight change in the third-person plural ending:

                        *decir* → **dij-** · *traer* → **traj-** · *conducir* → **conduj-**

                        Following the *j*, the third-person plural drops the *i* from its ending
                        to become **-eron**. This creates forms like **dijeron** and **trajeron**.
                        Verbs ending in *-ducir* also belong to this group, yielding conjugations
                        like *produjo*, *tradujo*, and *condujeron*.

                        These three stem categories cover the entire set of highly irregular
                        preterites, all sharing the unaccented ending pattern.
                        """,
                    ),
                    examples(
                        ex(
                            "decir", pret(Person.ELLOS),
                            "No me **dijeron** la verdad.",
                            "They didn't tell me the truth.",
                            note = "j-stem: no *i* in the ending — *dijeron*, not *dijieron*.",
                        ),
                        ex(
                            "traer", pret(Person.YO),
                            "**Traje** algo de comer.",
                            "I brought something to eat.",
                        ),
                        ex(
                            "conducir", pret(Person.EL),
                            "**Condujo** toda la noche sin parar.",
                            "He drove all night without stopping.",
                        ),
                    ),
                ),
                drill(10, emphasis = 0.95),
                cloze(
                    4,
                    """
                    Cloze sentences blanking an i-stem or j-stem strong preterite (hacer, querer,
                    venir, decir, traer, conducir). Options: the correct form and the mistake a
                    learner makes — *hació* for *hizo*, *dijieron* for *dijeron*, *venió* for
                    *vino*.
                    """,
                ),
            ),
        ),

        // -- 14. strong preterites together --------------------------------------------------------------
        LessonDefinition(
            id = "strong_preterites",
            title = "Reviewing the irregular preterites",
            tagline = "Practice the sixteen highly irregular preterite verbs grouped by their stem patterns.",
            estimatedMinutes = 15,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                directions = setOf(
                    DrillDirection.PRODUCE,
                    DrillDirection.DECODE,
                    DrillDirection.TRANSFORM,
                    DrillDirection.GENERALIZE,
                ),
                irregularityClasses = setOf(
                    IrregularityClass.STRONG_PRET_U,
                    IrregularityClass.STRONG_PRET_I,
                    IrregularityClass.STRONG_PRET_J,
                    IrregularityClass.STRONG_PRET_ENDING,
                ),
            ),
            segments = listOf(
                teach(
                    "Sixteen verbs, three stems",
                    prose(
                        """
                        These irregular preterites appear constantly in daily conversation.
                        Grouping them by stem helps solidify the patterns:

                        **u-stems** — tuv-, estuv-, pud-, pus-, sup-, hub-

                        **i-stems** — hic- (hizo), quis-, vin-

                        **j-stems** — dij-, traj-, conduj- (and -jeron, never -jieron)

                        **Endings, all three groups**: -e, -iste, -o, -imos, -isteis, -ieron. No
                        accents anywhere.

                        This review section mixes the three groups and tests your ability to apply
                        the patterns to compound verbs you might not have explicitly drilled.
                        """,
                    ),
                    table(Tense.PRETERITE, "tener", "hacer", "decir"),
                ),
                drill(20, emphasis = 0.95),
                cloze(
                    4,
                    """
                    Mixed cloze across all the strong preterites, all persons. Options: the correct
                    strong form and the over-regularised form a learner would invent (*tenió*,
                    *hació*, *dijieron*, *venió*). Vary which group each item draws on.
                    """,
                ),
            ),
        ),

        // -- 15. ser / ir / dar / ver ----------------------------------------------------------------------
        LessonDefinition(
            id = "suppletive_usage",
            title = "Highly irregular verbs: ser, ir, dar, and ver",
            tagline = "Memorize the unique conjugations for ser, ir, dar, and ver.",
            estimatedMinutes = 12,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                verbs = setOf("ser", "ir", "dar", "ver"),
            ),
            segments = listOf(
                teach(
                    "One paradigm, two verbs",
                    prose(
                        """
                        Four highly common verbs do not follow any of the previous irregular
                        patterns and must be memorized individually.

                        The verbs **ser and ir share an identical preterite conjugation**: *fui,
                        fuiste, fue, fuimos, fuisteis, fueron*. Sentences like *fui médico* (I was
                        a doctor) and *fui al médico* (I went to the doctor) rely on the
                        subsequent words for context. The verb *ir* typically precedes the
                        preposition **a**.
                        """,
                    ),
                    table(Tense.PRETERITE, "ser", "ir"),
                    examples(
                        ex(
                            "ir", pret(Person.NOSOTROS),
                            "El sábado **fuimos** a la playa.",
                            "On Saturday we went to the beach.",
                            note = "*Fuimos* + **a** = went.",
                        ),
                        ex(
                            "ser", pret(Person.EL),
                            "**Fue** una noche inolvidable.",
                            "It was an unforgettable night.",
                        ),
                    ),
                ),
                drill(
                    6,
                    emphasis = 0.7,
                    scope = LessonScope(
                        tenses = setOf(Tense.PRETERITE),
                        verbs = setOf("ser", "ir"),
                    ),
                ),
                teach(
                    "Dar and ver: too short for accents",
                    prose(
                        """
                        The -ar verb **dar** adopts -er/-ir endings to form *di, diste, dio,
                        dimos, disteis, dieron*. The verb **ver** uses regular endings but retains
                        its *e* to form *vi, viste, vio, vimos, visteis, vieron*.

                        Neither verb requires a written accent on the first and third person
                        singular forms. Standard Spanish orthography dictates that one-syllable
                        words like **di**, **dio**, **vi**, and **vio** remain unaccented unless
                        the accent distinguishes meaning.
                        """,
                    ),
                    table(Tense.PRETERITE, "dar", "ver"),
                    examples(
                        ex(
                            "dar", pret(Person.ELLOS),
                            "Me **dieron** una segunda oportunidad.",
                            "They gave me a second chance.",
                        ),
                        ex(
                            "ver", pret(Person.YO),
                            "**Vi** a tu hermano en el mercado.",
                            "I saw your brother at the market.",
                        ),
                        ex(
                            "ver", pret(Person.EL),
                            "Nadie lo **vio** salir.",
                            "Nobody saw him leave.",
                            note = "No accent: *vio*, not *vió*.",
                        ),
                    ),
                ),
                drill(10, emphasis = 0.7),
                cloze(
                    4,
                    """
                    Cloze sentences on ser, ir, dar and ver in the preterite. Options: the correct
                    form and a plausible wrong one — the accented misspelling (*dió*, *vió*), or the
                    form for a neighbouring person (*fue* / *fui*). Include one ser/ir item where
                    the sentence's own context (a following *a* or a predicate noun) decides which
                    verb is meant.
                    """,
                ),
            ),
        ),

        // -- 16. usage --------------------------------------------------------------------------------------
        LessonDefinition(
            id = "usage_triggers",
            title = "When to use the preterite",
            tagline = "Identify the four primary functions of the past tense and their associated time markers.",
            estimatedMinutes = 14,
            scope = LessonScope(tenses = setOf(Tense.PRETERITE)),
            segments = listOf(
                teach(
                    "Primary functions of the preterite",
                    prose(
                        """
                        With the conjugations covered, you must recognize the four specific
                        contexts that require the preterite tense.

                        **Sequenced events.** Use the preterite for narrative steps occurring one
                        after another, such as *llegué, abrí la puerta y encendí la luz*.

                        **Delimited periods.** Apply it to actions contained within a defined
                        timeframe, like *durante dos años*, *toda la tarde*, or *de 2015 a 2019*.

                        **Single completed facts.** Use it to state finished occurrences or
                        historical facts, such as *fue una buena idea* or *la fiesta terminó
                        tarde*.

                        **Interruptions.** When a new action interrupts an ongoing one, the
                        interrupting event takes the preterite: *dormía cuando **sonó** el
                        teléfono*.
                        """,
                    ),
                    examples(
                        ex(
                            "terminar", pret(Person.EL),
                            "La reunión **terminó** a las seis.",
                            "The meeting ended at six.",
                        ),
                        ex(
                            "trabajar", pret(Person.YO),
                            "**Trabajé** allí durante cinco años.",
                            "I worked there for five years.",
                            note = "*Durante* + a closed period: preterite, however long the period.",
                        ),
                        ex(
                            "sonar", pret(Person.EL),
                            "De repente **sonó** el teléfono.",
                            "Suddenly the phone rang.",
                        ),
                    ),
                ),
                drill(12, emphasis = 0.5),
                teach(
                    "Words that lean this way",
                    prose(
                        """
                        Specific time phrases generally indicate the need for the preterite.
                        Examples include *ayer, anoche, el lunes, la semana pasada, el año pasado,
                        en 2019, de repente, una vez, entonces,* and *por fin*.

                        While the grammatical context ultimately dictates the tense, these phrases
                        serve as highly reliable indicators. A sentence limiting an event to a
                        defined moment or timeframe requires the preterite.
                        """,
                    ),
                ),
                cloze(
                    6,
                    """
                    Cloze sentences exercising the preterite's core uses: sequenced narrative steps,
                    delimited periods, sudden events, single completed facts. Blank the verb;
                    options are the correct preterite form and a plausible wrong form (wrong person,
                    or the present tense). Prefer ser/ir/dar/ver and the strong preterites.
                    """,
                ),
                pi(
                    4,
                    """
                    Referential items across the whole preterite, any person: a pro-dropped sentence
                    with a past-time marker whose verb ending alone identifies the subject. Draw on
                    irregular verbs (tuve/tuvo, hice/hizo, fui/fue) where the ending is easiest to
                    misread.
                    """,
                ),
            ),
        ),

        // -- 17. consolidation ----------------------------------------------------------------------------------
        LessonDefinition(
            id = "consolidation",
            title = "Comprehensive review",
            tagline = "Practice the entire preterite tense across all verb classes and irregularities.",
            estimatedMinutes = 15,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE),
                directions = setOf(
                    DrillDirection.PRODUCE,
                    DrillDirection.DECODE,
                    DrillDirection.TRANSFORM,
                    DrillDirection.GENERALIZE,
                ),
            ),
            segments = listOf(
                teach(
                    "Testing your knowledge",
                    prose(
                        """
                        This final review combines regular verbs, irregular verbs, spelling
                        changes, and all subject pronouns. The exercises will test your ability to
                        conjugate and recognize the preterite in a mixed format.

                        Completing this section graduates the course. A selection of flashcards
                        will merge with your broader review schedule to reinforce these
                        conjugations over time.
                        """,
                    ),
                    table(Tense.PRETERITE, "hablar", "comer", "vivir", "tener", "ir"),
                ),
                drill(20, emphasis = 0.6),
                cloze(
                    6,
                    """
                    Mixed cloze across the entire preterite — regular verbs, spelling changes, stem
                    raises, strong preterites, and ser/ir/dar/ver — in natural sentences with past
                    time markers. Options: the correct form and the most tempting wrong one for that
                    verb (over-regularisation, missing accent, or neighbouring person).
                    """,
                ),
            ),
        ),
    ),
    graduationCards = listOf(
        GraduationCardSpec(
            "Anoche {{c1::vi}} una película en casa. (ver)",
            "Last night I watched a film at home.",
        ),
        GraduationCardSpec(
            "El verano pasado {{c1::fuimos}} a Portugal. (ir)",
            "Last summer we went to Portugal.",
        ),
        GraduationCardSpec(
            "¿Quién te {{c1::dijo}} eso? (decir)",
            "Who told you that?",
        ),
        GraduationCardSpec(
            "Ayer no {{c1::pude}} dormir bien. (poder)",
            "Yesterday I couldn't sleep well.",
        ),
        GraduationCardSpec(
            "Te {{c1::busqué}} por todas partes y no te encontré. (buscar)",
            "I looked for you everywhere and didn't find you.",
        ),
        GraduationCardSpec(
            "Ella {{c1::pidió}} un café con leche. (pedir)",
            "She ordered a coffee with milk.",
        ),
        GraduationCardSpec(
            "De repente {{c1::empezó}} a llover. (empezar)",
            "Suddenly it started to rain.",
        ),
        GraduationCardSpec(
            "Mis abuelos {{c1::vivieron}} veinte años en Buenos Aires. (vivir)",
            "My grandparents lived in Buenos Aires for twenty years.",
        ),
        GraduationCardSpec(
            "Los niños {{c1::leyeron}} el cuento en voz alta. (leer)",
            "The children read the story out loud.",
        ),
        GraduationCardSpec(
            "Nosotros {{c1::comimos}} en casa de mi tía. (comer)",
            "We ate at my aunt's house.",
        ),
        GraduationCardSpec(
            "¿Por qué no {{c1::viniste}} a la fiesta? (venir)",
            "Why didn't you come to the party?",
        ),
        GraduationCardSpec(
            "Me {{c1::dieron}} una segunda oportunidad. (dar)",
            "They gave me a second chance.",
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Imperfect
// ---------------------------------------------------------------------------------------------

private fun spanishImperfect() = CourseDefinition(
    id = "es_imperfect",
    language = "es",
    title = "The imperfect",
    blurb = "Use this tense to describe background events, ongoing past states, and habitual actions.",
    parts = listOf(
        part("Two patterns to learn", from = "meaning"),
        part("The three exceptions", from = "irregulars"),
        part("Knowing when to use it", from = "usage"),
    ),
    lessons = listOf(

        // -- 1. meaning ------------------------------------------------------------------------
        LessonDefinition(
            id = "meaning",
            title = "What the imperfect means",
            tagline = "Understand the purpose of the imperfect and learn to identify its distinct -aba and -ía endings.",
            estimatedMinutes = 10,
            scope = LessonScope(
                tenses = setOf(Tense.IMPERFECT),
                directions = RECOGNISE,
                regularOnly = true,
            ),
            segments = listOf(
                teach(
                    "A tense for how things were",
                    prose(
                        """
                        The imperfect (el pretérito imperfecto) describes ongoing past actions
                        without focusing on their beginning or end. English translates this tense
                        in three distinct ways: continuous (*I was eating*), habitual (*I used to
                        eat* or *I would eat*), and descriptive states (*it was cold*).

                        The imperfect sets the scene. You use it to describe past environments,
                        habitual actions, and background events that overlap with other actions.
                        It answers questions like *¿cómo era?* (what was it like?) and *¿qué
                        hacías?* (what were you doing?).
                        """,
                    ),
                    examples(
                        ex(
                            "vivir", imp(Person.NOSOTROS),
                            "Cuando era niño, **vivíamos** cerca del mar.",
                            "When I was a child, we lived near the sea.",
                        ),
                        ex(
                            "jugar", imp(Person.ELLOS),
                            "Los domingos **jugaban** al fútbol en la plaza.",
                            "On Sundays they used to play football in the square.",
                        ),
                        ex(
                            "hacer", imp(Person.EL),
                            "**Hacía** frío y no quería salir.",
                            "It was cold and I didn't want to go out.",
                        ),
                    ),
                ),
                teach(
                    "It sounds different, too",
                    prose(
                        """
                        The imperfect tense features highly distinct phonetic markers. Almost
                        every conjugation relies on one of two syllables:

                        **-aba-** for -ar verbs · *hablaba, trabajábamos, jugaban*

                        **-ía-** for -er and -ir verbs · *comía, vivíamos, tenían*

                        The following exercises mix present and imperfect conjugations. Read each
                        form to identify the subject and determine if the action is happening now
                        or in the past.
                        """,
                    ),
                    examples(
                        ex(
                            "trabajar", pres(Person.YO),
                            "Ahora **trabajo** en casa.",
                            "Now I work from home.",
                        ),
                        ex(
                            "trabajar", imp(Person.YO),
                            "Antes **trabajaba** en un banco.",
                            "I used to work in a bank.",
                            note = "The *-aba-* is the whole signal.",
                        ),
                    ),
                ),
                drill(
                    12,
                    emphasis = 0.1,
                    scope = LessonScope(
                        tenses = setOf(Tense.PRESENT, Tense.IMPERFECT),
                        directions = RECOGNISE,
                        regularOnly = true,
                    ),
                ),
            ),
        ),

        // -- 2. -ar singular ---------------------------------------------------------------------
        LessonDefinition(
            id = "ar_singular",
            title = "-ar verbs: I, you, he",
            tagline = "Conjugate singular subjects using the -aba, -abas, and -aba endings.",
            estimatedMinutes = 12,
            scope = LessonScope(
                tenses = setOf(Tense.IMPERFECT),
                verbClasses = setOf(VerbClass.AR),
                persons = SINGULAR,
            ),
            segments = listOf(
                teach(
                    "-aba, -abas, -aba",
                    prose(
                        """
                        The imperfect forms for -ar verbs build upon the **-aba-** stem. You
                        append the standard person markers to create the singular forms:

                        **yo → -aba** · *hablaba*

                        **tú → -abas** · *hablabas*

                        **él / ella / usted → -aba** · *hablaba*

                        The first and third person singular forms are **identical**. Sentences
                        relying on these conjugations require a subject pronoun or clear context
                        to clarify whether the speaker means *I was speaking* or *he was
                        speaking*.
                        """,
                    ),
                    table(
                        Tense.IMPERFECT, "hablar", "trabajar", "jugar",
                        persons = listOf(Person.YO, Person.TU, Person.EL),
                    ),
                    examples(
                        ex(
                            "trabajar", imp(Person.EL),
                            "Mi padre **trabajaba** los sábados.",
                            "My father used to work on Saturdays.",
                        ),
                        ex(
                            "trabajar", imp(Person.YO),
                            "Yo **trabajaba** los sábados.",
                            "I used to work on Saturdays.",
                            note = "Same word. Only *yo* tells you who.",
                        ),
                        ex(
                            "jugar", imp(Person.TU),
                            "¿**Jugabas** al fútbol en el colegio?",
                            "Did you play football at school?",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                teach(
                    "No accents to worry about",
                    prose(
                        """
                        Unlike the preterite, **these singular forms do not require written
                        accents**. Conjugations like *hablaba* and *hablabas* follow standard
                        Spanish pronunciation rules, so the spoken stress naturally falls on the
                        penultimate syllable.

                        The only -ar imperfect form that carries an accent mark is the
                        first-person plural (*nosotros*), which you will learn next.
                        """,
                    ),
                    examples(
                        ex(
                            "esperar", imp(Person.EL),
                            "**Esperaba** el autobús todas las mañanas.",
                            "He used to wait for the bus every morning.",
                        ),
                        ex(
                            "llamar", imp(Person.TU),
                            "Antes me **llamabas** todos los días.",
                            "You used to call me every day.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                cloze(
                    4,
                    """
                    Cloze sentences on regular -ar imperfect verbs, first/second/third person
                    singular only. Each sentence carries a habitual or descriptive marker (antes,
                    siempre, todos los días, de niño). Options: the correct imperfect form and the
                    same verb's present-tense form for that person. Where the answer is *-aba*,
                    make the subject explicit so the item has one defensible answer.
                    """,
                ),
            ),
        ),

        // -- 3. -ar plural + whole paradigm ---------------------------------------------------------
        LessonDefinition(
            id = "ar_plural",
            title = "-ar verbs: we and they",
            tagline = "Complete the -ar verb group with the plural -ábamos and -aban endings.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.IMPERFECT),
                verbClasses = setOf(VerbClass.AR),
            ),
            segments = listOf(
                teach(
                    "-ábamos and -aban",
                    prose(
                        """
                        The plural forms for -ar verbs use these endings:

                        **nosotros → -ábamos** · *hablábamos*

                        **ellos / ellas / ustedes → -aban** · *hablaban*

                        The **-ábamos** ending requires a written accent because the vocal stress
                        falls on the antepenultimate syllable. Pronounce it as *ha-BLÁ-ba-mos*.

                        These imperfect conjugations remain visually distinct from their
                        present-tense equivalents. The present *hablamos* and the imperfect
                        *hablábamos* cannot be confused for one another.
                        """,
                    ),
                    table(Tense.IMPERFECT, "hablar", "trabajar", "jugar"),
                    examples(
                        ex(
                            "caminar", imp(Person.NOSOTROS),
                            "**Caminábamos** por el parque cada mañana.",
                            "We would walk through the park every morning.",
                        ),
                        ex(
                            "jugar", imp(Person.ELLOS),
                            "**Jugaban** al fútbol hasta que anochecía.",
                            "They used to play football until it got dark.",
                        ),
                    ),
                ),
                drill(
                    6,
                    emphasis = 0.1,
                    scope = LessonScope(
                        tenses = setOf(Tense.IMPERFECT),
                        verbClasses = setOf(VerbClass.AR),
                        persons = PLURAL,
                    ),
                ),
                teach(
                    "The whole -ar table",
                    prose(
                        """
                        **-aba · -abas · -aba · -ábamos · -abais · -aban**

                        This sequence covers the imperfect conjugations for all regular -ar verbs.
                        The upcoming exercises mix these subjects together to test your
                        recognition of the complete pattern.
                        """,
                    ),
                ),
                drill(14, emphasis = 0.1),
                cloze(
                    4,
                    """
                    Cloze sentences on regular -ar imperfect verbs across all persons, with habitual
                    or descriptive markers. Options: the correct imperfect form and the same verb's
                    present form, or a neighbouring person's imperfect (*hablaba* / *hablaban*).
                    """,
                ),
            ),
        ),

        // -- 4. -er/-ir singular -------------------------------------------------------------------
        LessonDefinition(
            id = "er_ir_singular",
            title = "-er and -ir verbs: I, you, he",
            tagline = "Apply the -ía, -ías, and -ía endings to singular subjects for both -er and -ir verbs.",
            estimatedMinutes = 12,
            scope = LessonScope(
                tenses = setOf(Tense.IMPERFECT),
                verbClasses = setOf(VerbClass.ER, VerbClass.IR),
                regularOnly = true,
                persons = SINGULAR,
            ),
            segments = listOf(
                teach(
                    "-ía, -ías, -ía",
                    prose(
                        """
                        Regular **-er and -ir verbs share a single set of imperfect endings**:

                        **yo → -ía** · *comía*, *vivía*

                        **tú → -ías** · *comías*, *vivías*

                        **él / ella / usted → -ía** · *comía*, *vivía*

                        Just like the -ar verbs, the first and third person singular forms are
                        identical. Additionally, **every ending in this group requires a written
                        accent on the í**. The accent mark ensures the vowel is pronounced
                        distinctly.
                        """,
                    ),
                    table(
                        Tense.IMPERFECT, "comer", "vivir", "tener",
                        persons = listOf(Person.YO, Person.TU, Person.EL),
                    ),
                    examples(
                        ex(
                            "tener", imp(Person.YO),
                            "**Tenía** ocho años cuando nos mudamos.",
                            "I was eight years old when we moved.",
                        ),
                        ex(
                            "querer", imp(Person.EL),
                            "No **quería** hablar con nadie.",
                            "He didn't want to talk to anyone.",
                        ),
                        ex(
                            "vivir", imp(Person.TU),
                            "¿Dónde **vivías** entonces?",
                            "Where were you living then?",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                teach(
                    "Two patterns, and that is all",
                    prose(
                        """
                        Comparing the two verb groups highlights the consistency of the imperfect
                        tense:

                        **-ar**: hablaba, hablabas, hablaba

                        **-er/-ir**: comía, comías, comía

                        Regular verbs strictly follow one of these two structures. Verbs that are
                        highly irregular in the preterite tense, such as *tener*, *poder*,
                        *hacer*, and *decir*, conjugate normally in the imperfect: **tenía**, **podía**, **hacía**,
                        and **decía**.
                        """,
                    ),
                    examples(
                        ex(
                            "hacer", imp(Person.YO),
                            "No sabía qué **hacía** allí.",
                            "I didn't know what I was doing there.",
                        ),
                        ex(
                            "decir", imp(Person.EL),
                            "Siempre **decía** lo mismo.",
                            "He always used to say the same thing.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.1),
                cloze(
                    4,
                    """
                    Cloze sentences on regular -er/-ir imperfect verbs, singular persons only, with
                    habitual or descriptive markers. Options: the correct imperfect form and the
                    same verb's present-tense form for that person. Make the subject explicit
                    wherever the answer is *-ía*, since yo and él share it.
                    """,
                ),
            ),
        ),

        // -- 5. -er/-ir plural ----------------------------------------------------------------------
        LessonDefinition(
            id = "er_ir_plural",
            title = "-er and -ir verbs: we and they",
            tagline = "Complete the -er and -ir verb group with the plural -íamos and -ían endings.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.IMPERFECT),
                verbClasses = setOf(VerbClass.ER, VerbClass.IR),
                regularOnly = true,
            ),
            segments = listOf(
                teach(
                    "-íamos and -ían",
                    prose(
                        """
                        **nosotros → -íamos** · *comíamos*, *vivíamos*

                        **ellos / ellas / ustedes → -ían** · *comían*, *vivían*

                        The written accent remains on the *í* for both plural forms. These
                        conjugations are also distinct from their present-tense equivalents,
                        completely avoiding the overlap seen in other tenses.
                        """,
                    ),
                    table(Tense.IMPERFECT, "comer", "vivir", "tener"),
                    examples(
                        ex(
                            "vivir", imp(Person.NOSOTROS),
                            "Antes **vivíamos** en un piso muy pequeño.",
                            "We used to live in a very small flat.",
                        ),
                        ex(
                            "salir", imp(Person.ELLOS),
                            "**Salían** juntos todos los viernes.",
                            "They used to go out together every Friday.",
                        ),
                    ),
                ),
                drill(
                    6,
                    emphasis = 0.1,
                    scope = LessonScope(
                        tenses = setOf(Tense.IMPERFECT),
                        verbClasses = setOf(VerbClass.ER, VerbClass.IR),
                        regularOnly = true,
                        persons = PLURAL,
                    ),
                ),
                teach(
                    "The second table, whole",
                    prose(
                        """
                        **-ía · -ías · -ía · -íamos · -íais · -ían**

                        These two ending sets cover all regular imperfect conjugations in Spanish.
                        The following exercises will combine -ar, -er, and -ir verbs to test your
                        overall recall.
                        """,
                    ),
                ),
                drill(14, emphasis = 0.1),
                cloze(
                    4,
                    """
                    Cloze sentences on regular -er/-ir imperfect verbs across all persons, with
                    habitual or descriptive markers. Options: the correct imperfect form and a
                    neighbouring person's (*vivía* / *vivían*) or the present tense.
                    """,
                ),
            ),
        ),

        // -- 6. all regular -----------------------------------------------------------------------------
        LessonDefinition(
            id = "forms",
            title = "Both patterns together",
            tagline = "Practice both regular conjugation patterns without the verb class provided upfront.",
            estimatedMinutes = 15,
            scope = LessonScope(
                tenses = setOf(Tense.IMPERFECT),
                regularOnly = true,
            ),
            segments = listOf(
                teach(
                    "Everything regular, mixed",
                    prose(
                        """
                        This review section tests both verb classes and all subject pronouns
                        simultaneously. Determining the correct ending requires you to identify
                        the verb's infinitive class on your own.

                        Pay close attention to written accents on the *-ábamos* and *-ía* forms.
                        You must also rely on sentence context to differentiate between the
                        identical first and third person singular conjugations.
                        """,
                    ),
                    table(Tense.IMPERFECT, "hablar", "comer", "vivir"),
                ),
                drill(20, emphasis = 0.05),
                pi(
                    4,
                    """
                    Referential items on regular imperfect forms: a pro-dropped sentence whose
                    ending identifies the subject. Because the first and third person singular are
                    identical, only offer options the ending can actually distinguish — never yo
                    against él in the same item.
                    """,
                ),
            ),
        ),

        // -- 7. the three irregulars ---------------------------------------------------------------------
        LessonDefinition(
            id = "irregulars",
            title = "The only three irregulars",
            tagline = "Memorize the conjugations for ser, ir, and ver—the only three irregular verbs in the imperfect tense.",
            estimatedMinutes = 13,
            scope = LessonScope(
                tenses = setOf(Tense.IMPERFECT),
                verbs = setOf("ser", "ir", "ver"),
            ),
            segments = listOf(
                teach(
                    "Three verbs in the whole language",
                    prose(
                        """
                        The imperfect tense contains exactly **three irregular verbs**:

                        **ser** → *era, eras, era, éramos, erais, eran*

                        **ir** → *iba, ibas, iba, íbamos, ibais, iban*

                        **ver** → *veía, veías, veía, veíamos, veíais, veían*

                        The verb *ver* only slightly deviates by retaining its root *e* before the
                        standard *-ía* endings. The verbs *ser* and *ir* use entirely unique
                        structures, but their high frequency in spoken Spanish makes them easy to
                        memorize.
                        """,
                    ),
                    table(Tense.IMPERFECT, "ser", "ir", "ver"),
                    examples(
                        ex(
                            "ser", imp(Person.NOSOTROS),
                            "**Éramos** cinco hermanos.",
                            "There were five of us siblings.",
                        ),
                        ex(
                            "ir", imp(Person.YO),
                            "Todos los veranos **iba** al pueblo de mis abuelos.",
                            "Every summer I went to my grandparents' village.",
                        ),
                        ex(
                            "ver", imp(Person.ELLOS),
                            "Se **veían** todos los días después de clase.",
                            "They saw each other every day after class.",
                        ),
                    ),
                ),
                drill(12, emphasis = 0.9),
                teach(
                    "Where you will actually meet them",
                    prose(
                        """
                        These three verbs appear constantly in descriptive Spanish. Speakers use
                        *era* to set the scene for a story, *iba* to describe habitual travel, and
                        *era* to describe physical or personality traits.

                        Mastering these exceptions completes your knowledge of the imperfect
                        tense. Every verb you encounter will either follow the standard -ar or
                        -er/-ir rules, or match one of these three irregularities.
                        """,
                    ),
                    examples(
                        ex(
                            "ser", imp(Person.EL),
                            "**Era** de noche y no se veía nada.",
                            "It was night and you couldn't see a thing.",
                        ),
                        ex(
                            "ir", imp(Person.NOSOTROS),
                            "**Íbamos** al colegio andando.",
                            "We used to walk to school.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.4, scope = LessonScope(tenses = setOf(Tense.IMPERFECT))),
                cloze(
                    4,
                    """
                    Cloze sentences on ser, ir and ver in the imperfect. Options: the correct form
                    and the regularised one a learner would build (*sía* for *era*, *ía* for *iba*,
                    *vía* for *veía*), or a neighbouring person's form.
                    """,
                ),
            ),
        ),

        // -- 8. usage -------------------------------------------------------------------------------------
        LessonDefinition(
            id = "usage",
            title = "When the imperfect is the right past",
            tagline = "Identify the scenarios requiring the imperfect: habitual actions, physical descriptions, and ongoing background events.",
            estimatedMinutes = 14,
            scope = LessonScope(tenses = setOf(Tense.IMPERFECT)),
            segments = listOf(
                teach(
                    "Habit, description, and the ongoing background",
                    prose(
                        """
                        The imperfect tense applies to four specific contexts:

                        **Habitual actions**: Events that repeated regularly in the past, such as
                        *de niño jugaba en la calle*.

                        **Descriptions**: Physical traits of people, places, and weather
                        conditions, like *la casa era grande y tenía un jardín*.

                        **Time, age, and mental states**: Internal conditions and measurements,
                        such as *eran las diez*, *tenía ocho años*, or *quería irme*.

                        **Ongoing background actions**: Continuous events that overlap with or are
                        interrupted by new actions. In the sentence *dormía cuando sonó el
                        teléfono*, the sleeping is the ongoing imperfect background, while the
                        phone ringing is the interrupting preterite action.
                        """,
                    ),
                    examples(
                        ex(
                            "ser", imp(Person.ELLOS),
                            "**Eran** las diez de la noche.",
                            "It was ten at night.",
                        ),
                        ex(
                            "tener", imp(Person.EL),
                            "La casa **tenía** un jardín enorme.",
                            "The house had a huge garden.",
                        ),
                        ex(
                            "dormir", imp(Person.YO),
                            "**Dormía** cuando sonó el teléfono.",
                            "I was sleeping when the phone rang.",
                        ),
                    ),
                ),
                drill(8, emphasis = 0.4),
                teach(
                    "Words that lean this way",
                    prose(
                        """
                        Certain vocabulary words frequently accompany the imperfect tense,
                        including *siempre, todos los días, cada verano, mientras, de niño, antes,
                        normalmente, a menudo,* and *mientras tanto*.

                        To verify if the imperfect is appropriate, check whether the English
                        translation implies an action you **"used to"** do or an action that **"was
                        happening"** continuously.
                        """,
                    ),
                ),
                drill(6, emphasis = 0.6),
                cloze(
                    6,
                    """
                    Cloze sentences exercising the imperfect's core uses: habits with *siempre* /
                    *todos los días*, physical description, time and age, ongoing background with
                    *mientras*. Options: the correct imperfect form and the same verb's preterite
                    form for that person.
                    """,
                ),
            ),
        ),

        // -- 9. consolidation ------------------------------------------------------------------------------
        LessonDefinition(
            id = "consolidation",
            title = "The whole tense",
            tagline = "Review all regular and irregular imperfect verbs in a single, mixed practice session.",
            estimatedMinutes = 15,
            scope = LessonScope(tenses = setOf(Tense.IMPERFECT)),
            segments = listOf(
                teach(
                    "Putting it together",
                    prose(
                        """
                        This final practice section tests all the material covered in the course.
                        It mixes both sets of regular endings, the three irregular verbs, and the
                        four primary use cases.

                        Completing these exercises graduates the lesson block. The spaced
                        repetition system will schedule these flashcards alongside your standard
                        reviews to ensure long-term retention.
                        """,
                    ),
                    table(Tense.IMPERFECT, "hablar", "comer", "ser", "ir", "ver"),
                ),
                drill(20, emphasis = 0.5),
                cloze(
                    6,
                    """
                    Mixed cloze across the imperfect's uses (habit, description, time/age,
                    background) and across regular verbs and the three irregulars. Blank the verb;
                    options are the correct imperfect form and one plausible wrong form (wrong
                    person, or the preterite of the same verb).
                    """,
                ),
            ),
        ),
    ),
    graduationCards = listOf(
        GraduationCardSpec(
            "De niño {{c1::jugaba}} en la calle con mis primos. (jugar)",
            "As a child I used to play in the street with my cousins.",
        ),
        GraduationCardSpec(
            "La casa {{c1::era}} grande y {{c2::tenía}} un jardín enorme. (ser, tener)",
            "The house was big and had a huge garden.",
        ),
        GraduationCardSpec(
            "{{c1::Eran}} las diez de la noche cuando llegó. (ser)",
            "It was ten at night when he arrived.",
        ),
        GraduationCardSpec(
            "Todos los veranos {{c1::íbamos}} a la costa. (ir)",
            "Every summer we went to the coast.",
        ),
        GraduationCardSpec(
            "Mientras {{c1::dormía}}, sonó el teléfono. (dormir)",
            "While I was sleeping, the phone rang.",
        ),
        GraduationCardSpec(
            "Antes la gente {{c1::escribía}} cartas a mano. (escribir)",
            "People used to write letters by hand.",
        ),
        GraduationCardSpec(
            "Nos {{c1::veíamos}} todos los días después de clase. (ver)",
            "We used to see each other every day after class.",
        ),
        GraduationCardSpec(
            "{{c1::Caminábamos}} por el parque cada mañana. (caminar)",
            "We would walk through the park every morning.",
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Preterite vs imperfect
// ---------------------------------------------------------------------------------------------

private fun spanishPastContrast() = CourseDefinition(
    id = "es_past_contrast",
    language = "es",
    title = "Preterite vs imperfect",
    blurb = "Choosing between the two past tenses: identifying completed events and continuous backgrounds.",
    prerequisites = listOf("es_preterite", "es_imperfect"),
    parts = listOf(
        part("How the choice works", from = "aspect"),
        part("The choice in real sentences", from = "interrupted"),
    ),
    lessons = listOf(
        LessonDefinition(
            id = "aspect",
            title = "One event, two perspectives",
            tagline = "Focus on the shape of the event rather than when it occurred.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE, Tense.IMPERFECT),
                contrastCategory = "preterite_imperfect",
            ),
            segments = listOf(
                teach(
                    "Focusing on the boundary of an action",
                    prose(
                        """
                        Both tenses describe the past. The difference lies in the boundary of the
                        event. The preterite views an action from the outside as a closed unit.
                        The imperfect views the same action from the inside, focusing on its
                        duration without defining a start or end point.

                        The exact same event can take either tense depending on what you want to
                        communicate. *Ayer llovió* (yesterday it rained) states a completed fact
                        about the day. *Llovía cuando salí* (it was raining when I left) uses the
                        rain as the backdrop for a specific action. Neither tense is further in
                        the past than the other.

                        When telling a story, the imperfect sets the stage and describes the
                        conditions. The preterite moves the plot forward. For example, *Era una
                        noche oscura* (it was a dark night) establishes the scene, while *Alguien
                        llamó a la puerta* (someone knocked on the door) advances the narrative.
                        """,
                    ),
                    examples(
                        ex(
                            "dormir", imp(Person.YO),
                            "**Dormía** profundamente cuando sonó la alarma.",
                            "I was sound asleep when the alarm went off.",
                            note = "Sleeping = ongoing background (imperfect); the alarm = intruding event (preterite).",
                        ),
                        ex(
                            "leer", pret(Person.YO),
                            "Anoche **leí** el informe entero.",
                            "Last night I read the whole report.",
                            note = "Completed, delimited — preterite.",
                        ),
                        ex(
                            "leer", imp(Person.YO),
                            "**Leía** el informe cuando me llamaste.",
                            "I was reading the report when you called me.",
                            note = "Same verb, same evening — now it is the background.",
                        ),
                    ),
                ),
                cloze(
                    8,
                    """
                    Contrast cloze: one blank, exactly two options — the preterite and the imperfect
                    of the same verb, same person. The sentence's structure (background vs event,
                    stage vs plot) must decide it; vary which side is correct.
                    """,
                ),
                drill(12, emphasis = 0.5),
            ),
        ),
        LessonDefinition(
            id = "triggers",
            title = "Time markers and their limits",
            tagline = "Learn how words like ayer and siempre guide your choice, and why they occasionally fail.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE, Tense.IMPERFECT),
                contrastCategory = "preterite_imperfect",
            ),
            segments = listOf(
                teach(
                    "Relying on context clues",
                    prose(
                        """
                        Specific time markers naturally align with one tense. Words that define a
                        closed timeframe point to the preterite: *ayer, anoche, de repente, una
                        vez, el año pasado, entonces,* and *durante* followed by a specific
                        duration. Words implying repetition or ongoing states point to the
                        imperfect: *siempre, todos los días, cada verano, mientras, de niño,
                        normalmente,* and *antes*. Expressions of frequency like *a veces* or
                        *nunca* can pair with either tense depending on the context.

                        You can rely on these markers for most sentences, but they remain
                        guidelines rather than absolute rules. The word *siempre* typically
                        indicates a habit, requiring the imperfect, as in *siempre llegaba tarde*
                        (he always arrived late). However, if the broader timeframe is permanently
                        closed, the preterite takes over. *Siempre vivió en Madrid* means he lived
                        in Madrid his entire life, and that lifetime is completed. The underlying
                        grammar rules always override the vocabulary triggers.
                        """,
                    ),
                    examples(
                        ex(
                            "llegar", imp(Person.EL),
                            "Siempre **llegaba** tarde a clase.",
                            "He always used to arrive late to class.",
                            note = "Open-ended habit: imperfect.",
                        ),
                        ex(
                            "vivir", pret(Person.EL),
                            "Siempre **vivió** en Madrid.",
                            "He lived in Madrid all his life.",
                            note = "Same *siempre* — but a completed life makes it preterite.",
                        ),
                        ex(
                            "trabajar", pret(Person.YO),
                            "**Trabajé** allí durante cinco años.",
                            "I worked there for five years.",
                            note = "*Durante* + a closed period: preterite, however long the period.",
                        ),
                    ),
                ),
                cloze(
                    10,
                    """
                    Contrast cloze built around trigger phrases: most items where the trigger's
                    default answer is right, but include at least two where the aspect overrides
                    the trigger (e.g. *siempre* + a closed lifetime → preterite). Two options per
                    item: preterite vs imperfect of the same verb and person.
                    """,
                ),
                drill(10, emphasis = 0.5),
            ),
        ),
        LessonDefinition(
            id = "interrupted",
            title = "Intersecting timelines",
            tagline = "Combine the tenses to describe an ongoing background action interrupted by a specific event.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE, Tense.IMPERFECT),
                contrastCategory = "preterite_imperfect",
            ),
            segments = listOf(
                teach(
                    "Layering the past",
                    prose(
                        """
                        A massive portion of spoken Spanish relies on combining both past tenses
                        into a single sentence.

                        This pattern usually takes the form of an imperfect verb, the word
                        *cuando*, and a preterite verb: *dormía cuando sonó el teléfono*. The
                        imperfect establishes the action that was already happening. The preterite
                        introduces the specific event that cut across it. The interruption does
                        not necessarily terminate the background action; it simply plots a point
                        on the continuous timeline. This mirrors the English structure of
                        combining "was doing" with a simple past verb.

                        You can also describe two simultaneous background actions without any
                        interruption. In these cases, you connect two imperfect verbs with
                        *mientras*. *Mientras yo cocinaba, él ponía la mesa* describes two ongoing
                        actions sharing the same timeframe.
                        """,
                    ),
                    examples(
                        ex(
                            "ver", imp(Person.NOSOTROS),
                            "**Veíamos** la televisión cuando se fue la luz.",
                            "We were watching TV when the power went out.",
                            note = "Background (imperfect) cut by an event (preterite).",
                        ),
                        ex(
                            "entrar", pret(Person.EL),
                            "Todos hablaban cuando **entró** el profesor.",
                            "Everyone was talking when the teacher came in.",
                            note = "The intruding event, and the only preterite in the sentence.",
                        ),
                        ex(
                            "preparar", imp(Person.YO),
                            "Mientras yo **preparaba** la cena, él ponía la mesa.",
                            "While I was making dinner, he was setting the table.",
                            note = "*Mientras*: two backgrounds, no interruption, both imperfect.",
                        ),
                    ),
                ),
                cloze(
                    10,
                    """
                    Contrast cloze on the interrupted-action frame. Each sentence pairs an ongoing
                    situation with an event, or two simultaneous ongoing situations with *mientras*.
                    Blank one verb at a time; two options — the preterite and the imperfect of the
                    same verb and person. Include several items where the blank is the *event* half
                    and several where it is the *background* half.
                    """,
                ),
                drill(10, emphasis = 0.5),
            ),
        ),
        LessonDefinition(
            id = "meaning_changers",
            title = "Verbs that shift in translation",
            tagline = "Understand why state verbs like saber and conocer change their English meaning in the preterite.",
            estimatedMinutes = 14,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE, Tense.IMPERFECT),
                verbs = setOf("saber", "conocer", "querer", "poder", "tener"),
                contrastCategory = "preterite_imperfect",
            ),
            segments = listOf(
                teach(
                    "Bounding continuous states",
                    prose(
                        """
                        Applying the preterite to certain state verbs forces a shift in their
                        English translation. Bounding a continuous state turns it into the
                        specific moment that state began or ended.

                        **saber**: *sabía* means you knew something, while **supe** marks the
                        exact moment you found out. **conocer**: *conocía* means you were already
                        acquainted with someone or something, while **conocí** pinpoints the
                        moment you met or discovered them. **querer**: *quería* represents a
                        general desire (wanted), while **quise** signifies an attempted action
                        (tried to), and **no quise** indicates a hard refusal. **poder**: *podía*
                        outlines a general ability, while **pude** confirms you managed to execute
                        the action, and **no pude** means you failed. **tener**: *tenía* describes
                        ongoing possession (had), while **tuve** marks the acquisition (got or
                        received). These are not distinct verbs; they are the natural consequence
                        of applying a fixed boundary to an ongoing state.
                        """,
                    ),
                    examples(
                        ex(
                            "saber", pret(Person.YO),
                            "Lo **supe** ayer por la tarde.",
                            "I found out yesterday afternoon.",
                        ),
                        ex(
                            "conocer", imp(Person.YO),
                            "Ya la **conocía** de la universidad.",
                            "I already knew her from university.",
                        ),
                        ex(
                            "conocer", pret(Person.YO),
                            "La **conocí** en una fiesta en 2019.",
                            "I met her at a party in 2019.",
                        ),
                        ex(
                            "querer", pret(Person.EL),
                            "**Quiso** abrir la puerta, pero estaba cerrada con llave.",
                            "He tried to open the door, but it was locked.",
                        ),
                    ),
                ),
                cloze(
                    10,
                    """
                    Contrast cloze on the meaning-changing verbs (saber, conocer, querer, poder,
                    tener): the English gloss in parentheses after each sentence disambiguates
                    which meaning is intended, and the learner picks the tense that carries it.
                    Two options per item: preterite vs imperfect, same verb and person.
                    """,
                ),
                drill(10, emphasis = 0.7),
            ),
        ),
        LessonDefinition(
            id = "narration",
            title = "Constructing a narrative",
            tagline = "Alternate between the two past tenses to give rhythm and structure to your stories.",
            estimatedMinutes = 15,
            scope = LessonScope(
                tenses = setOf(Tense.PRETERITE, Tense.IMPERFECT),
                contrastCategory = "preterite_imperfect",
            ),
            segments = listOf(
                teach(
                    "The two tenses working together",
                    prose(
                        """
                        Natural storytelling weaves these two tenses together continuously. The
                        imperfect paints the setting and maintains the environmental background,
                        while the preterite punctuates the timeline with specific actions. *Era
                        medianoche y llovía. Estaba cansado. De repente alguien llamó a la puerta.
                        Me levanté, abrí — y no había nadie.*

                        This final lesson consists entirely of contextual practice. You will
                        complete short narratives by choosing the correct tense for each blanked
                        verb. Completing this step moves the course into the spaced repetition
                        phase, where these contrasts will appear in your daily flashcard reviews
                        to solidify your comprehension.
                        """,
                    ),
                ),
                cloze(
                    12,
                    """
                    A short past-tense narrative (6–8 sentences, one scene) with each verb blanked
                    in turn as separate cloze items, in story order. Each item: two options, the
                    preterite and imperfect of the same verb and person. Mix stage-setting
                    (imperfect) and plot (preterite) so both answers occur several times.
                    """,
                ),
                drill(10, emphasis = 0.6),
            ),
        ),
    ),
    graduationCards = listOf(
        GraduationCardSpec(
            "{{c1::Dormía}} profundamente cuando {{c2::sonó}} la alarma. (dormir, sonar)",
            "I was sound asleep when the alarm went off.",
        ),
        GraduationCardSpec(
            "La {{c1::conocí}} en una fiesta; ya {{c2::conocía}} a su hermano. (conocer)",
            "I met her at a party; I already knew her brother.",
        ),
        GraduationCardSpec(
            "Lo {{c1::supe}} ayer — antes no {{c2::sabía}} nada. (saber)",
            "I found out yesterday — before that I knew nothing.",
        ),
        GraduationCardSpec(
            "{{c1::Era}} medianoche y {{c2::llovía}} cuando {{c3::llegamos}}. (ser, llover, llegar)",
            "It was midnight and raining when we arrived.",
        ),
        GraduationCardSpec(
            "No {{c1::quiso}} ayudarme, aunque {{c2::podía}}. (querer, poder)",
            "He refused to help me, even though he was able to.",
        ),
        GraduationCardSpec(
            "Siempre {{c1::vivió}} en Madrid; nunca {{c2::quería}} mudarse. (vivir, querer)",
            "He lived in Madrid all his life; he never wanted to move.",
        ),
        GraduationCardSpec(
            "{{c1::Veíamos}} la televisión cuando {{c2::se fue}} la luz. (ver, irse)",
            "We were watching TV when the power went out.",
        ),
        GraduationCardSpec(
            "Todos {{c1::hablaban}} cuando {{c2::entró}} el profesor. (hablar, entrar)",
            "Everyone was talking when the teacher came in.",
        ),
    ),
)
