package com.copiosa.domain.course.data

import com.copiosa.domain.course.AuthoredChoiceItem
import com.copiosa.domain.course.AuthoredExample
import com.copiosa.domain.course.AuthoredSegment
import com.copiosa.domain.course.CourseDefinition
import com.copiosa.domain.course.CoursePart
import com.copiosa.domain.course.DidacticBlock
import com.copiosa.domain.course.GraduationCardSpec
import com.copiosa.domain.course.LessonDefinition
import com.copiosa.domain.course.LessonScope
import com.copiosa.domain.course.LlmSlotKind
import com.copiosa.domain.routine.ClozeItem

/**
 * The Spanish *por* / *para* course: fifteen lessons taking the split from the one question that
 * resolves most sentences, through each preposition's own territory, to the pairs where the choice
 * is the whole of the meaning.
 *
 * **Why this course looks different from the tense courses.** Everything in
 * [SpanishPastCourses][spanishPastCourses] can be generated: a paradigm table comes off the engine
 * and a drill comes out of the lexicon, so the author writes prose and the machine supplies the
 * practice. A preposition has no paradigm. So this course carries its practice too — every item in
 * a [bank] is written here, beside the teaching it follows, and `CourseCatalogTest` checks its shape
 * the way it checks the tense courses' morphology. The banks are what the course is: they are the
 * offline floor, the pool the warm-ups and checkups draw from, and the thing an
 * [AuthoredSegment.LlmSlot] garnishes rather than replaces.
 *
 * Ids are frozen (see [CourseDefinition]). Every option list is `por` then `para` in that order, so
 * the item's answer index reads as the answer itself in the JSON dump, and no item can be scored by
 * learning a position — the banks are checked for a real mix of both answers.
 */
internal fun spanishPorParaCourse(): CourseDefinition = CourseDefinition(
    id = "es_por_para",
    language = "es",
    title = "Por vs para",
    blurb = "Both become \"for\" in English. Learn which direction each one points, and the sentences where the choice carries the meaning.",
    parts = listOf(
        part("The question that decides it", from = "core"),
        part("What para points at", from = "purpose"),
        part("What por points back at", from = "cause"),
        part("Choosing under pressure", from = "minimal_pairs"),
    ),
    lessons = listOf(

        // -- 1. core -----------------------------------------------------------------------------
        LessonDefinition(
            id = "core",
            title = "Backward and forward",
            tagline = "Ask whether the phrase looks back at a cause or forward to a goal.",
            estimatedMinutes = 13,
            scope = porPara,
            segments = listOf(
                teach(
                    "One question, most of the answers",
                    prose(
                        """
                        Both words arrive in English as *for*, which is why they feel
                        interchangeable. In Spanish they point in opposite directions. **Por**
                        looks **backward**, at whatever came first and produced the situation: a
                        reason, a cause, a source, something given in exchange. **Para** looks
                        **forward**, at whatever the situation is aimed at: a goal, a destination,
                        a recipient, a deadline.

                        When you are stuck, ask which way the phrase points. *Lo hice por ti*
                        looks back at you as the reason I acted. *Lo hice para ti* looks forward
                        to you as the person who receives the result. Both sentences are correct
                        Spanish and they describe different situations.

                        Nearly everything in this course is a special case of that split. The
                        special cases are worth learning one at a time, because the direction is
                        not always obvious in the moment. The backward-forward question is what
                        you fall back on when none of them comes to mind.
                        """,
                    ),
                    examples(
                        ex(
                            "llamar",
                            "Te llamé **por** curiosidad.",
                            "I called you out of curiosity.",
                            note = "Curiosity came first and produced the call.",
                        ),
                        ex(
                            "llamar",
                            "Te llamé **para** invitarte.",
                            "I called you to invite you.",
                            note = "The invitation is what the call was aimed at.",
                        ),
                        ex(
                            "hacer",
                            "Lo hice **por** ti.",
                            "I did it because of you.",
                            note = "You were the reason. Perhaps you asked, perhaps I owed you a favour.",
                        ),
                        ex(
                            "hacer",
                            "Lo hice **para** ti.",
                            "I made it for you.",
                            note = "Same verb, same person, and now you are the one who gets it.",
                        ),
                    ),
                ),
                bank(
                    pp("Compré este libro ___ ti; es tu regalo.", PARA, "You are who the gift is aimed at."),
                    pp("Volví temprano ___ el frío.", POR, "The cold is the reason I came back."),
                    pp("Leo en español ___ aprender más rápido.", PARA, "The reading is aimed at learning."),
                    pp("No pude venir ___ la lluvia.", POR, "The rain caused it."),
                    pp("Guardé un poco de pastel ___ mi hermana.", PARA, "She is the one who receives it."),
                    pp("Salí de casa ___ comprar pan.", PARA, "Buying bread is the goal of going out."),
                    pp("Lo dejé en el tren ___ olvido.", POR, "Forgetfulness is the cause."),
                    pp("Me disculpé ___ llegar tarde.", POR, "An apology looks back at what you already did."),
                ),
                teach(
                    "When both fit",
                    prose(
                        """
                        Plenty of sentences accept either word, and those are the useful ones,
                        because the preposition is then carrying the whole message. *Trabajo para
                        Marta* means Marta is my boss. *Trabajo por Marta* means I am covering
                        Marta's shift while she is away. Nothing else in the sentence changes.

                        Test the whole clause rather than the two words around the blank. In
                        *luchamos ___ la libertad*, freedom is what drives the fight, so the
                        answer is *por*. In *ahorramos ___ la casa*, the house is what the saving
                        is aimed at, so the answer is *para*. In both sentences the noun after
                        the blank is a thing the speaker cares about, and that is not what decides
                        it.
                        """,
                    ),
                    examples(
                        ex(
                            "trabajar",
                            "Trabajo **para** Marta.",
                            "I work for Marta.",
                            note = "She is my employer, and my work is aimed at her.",
                        ),
                        ex(
                            "trabajar",
                            "Trabajo **por** Marta.",
                            "I am working in Marta's place.",
                            note = "She is absent, and covering for her is why I am here.",
                        ),
                        ex(
                            "luchar",
                            "Luchamos **por** la libertad.",
                            "We fight for freedom.",
                            note = "Freedom is the motive behind the fight.",
                        ),
                    ),
                ),
                bank(
                    pp("Cambié mi turno ___ el suyo.", POR, "An exchange: one thing swapped for another."),
                    pp("Este regalo es ___ mi madre.", PARA, "She receives it."),
                    pp("Respondí el correo ___ mi jefe, que estaba enfermo.", POR, "I answered in his place."),
                    pp("Necesito el informe ___ el martes.", PARA, "A deadline the report is aimed at."),
                    pp("Lo dejó todo ___ amor.", POR, "Love was the motive."),
                    pp("Compramos flores ___ la fiesta.", PARA, "The party is what the flowers are for."),
                    pp("Gracias ___ tu ayuda.", POR, "Thanks always looks back at what was already done."),
                ),
                cloze(
                    8,
                    """
                    One blank, exactly two options — *por* and *para*, in that order. Each sentence
                    must be decided by whether the phrase looks back at a cause or forward to a
                    goal, and no item may rely on a use this course has not taught yet (no rates,
                    no fixed expressions, no passive agents). Vary which side is correct.
                    """,
                ),
            ),
        ),

        // -- 2. motion ---------------------------------------------------------------------------
        LessonDefinition(
            id = "motion",
            title = "Through a place, toward a place",
            tagline = "Separate movement across or around a space from movement aimed at a destination.",
            estimatedMinutes = 13,
            scope = porPara,
            segments = listOf(
                teach(
                    "Route and destination",
                    prose(
                        """
                        Movement makes the split easy to picture. **Por** is the route: the space
                        you move across, through, along or around. **Para** is the destination:
                        the point the movement is aimed at, which you may not have reached yet.

                        *Caminamos por el parque* says the park is where the walking happened. It
                        does not say where we ended up. *Caminamos para el parque* says the park
                        is where we were heading, and says nothing about where we walked on the
                        way. English marks the same difference with *through* and *around* on one
                        side and *to* and *toward* on the other.

                        Departure verbs take *para* naturally, because leaving is aimed
                        somewhere: *salgo para Madrid* is the standard way to say you are off to
                        Madrid. With *por*, the same sentence would place Madrid on your route
                        rather than at its end.
                        """,
                    ),
                    examples(
                        ex(
                            "caminar",
                            "Caminamos **por** el parque toda la tarde.",
                            "We walked around the park all afternoon.",
                            note = "The park is the space we moved through.",
                        ),
                        ex(
                            "salir",
                            "Salimos **para** Madrid a las seis.",
                            "We set off for Madrid at six.",
                            note = "Madrid is where the trip is aimed.",
                        ),
                        ex(
                            "pasar",
                            "El tren pasa **por** Valencia.",
                            "The train goes through Valencia.",
                            note = "Valencia is a point on the route, not the end of it.",
                        ),
                    ),
                ),
                bank(
                    pp("Vamos ___ la playa; llegamos en veinte minutos.", PARA, "The beach is the destination."),
                    pp("Dimos una vuelta ___ el centro.", POR, "The centre is the space we moved around in."),
                    pp("El autobús pasa ___ mi calle.", POR, "My street is on its route."),
                    pp("Este tren sale ___ Sevilla dentro de poco.", PARA, "Seville is where it is headed."),
                    pp("Entramos ___ la puerta de atrás.", POR, "The door is the opening we passed through."),
                    pp("Corrimos ___ la orilla del río.", POR, "The riverbank is the stretch we ran along."),
                    pp("Mañana salgo ___ Lisboa.", PARA, "Lisbon is the destination of the departure."),
                    pp("Los niños corrían ___ toda la casa.", POR, "They ran all over the house, not toward it."),
                ),
                teach(
                    "Around, roughly, somewhere over there",
                    prose(
                        """
                        A second use grows out of the route idea. **Por** with a place can mean
                        *somewhere in the area of*, without committing to a point. *Vive por el
                        centro* means she lives somewhere around the centre. *Hay una farmacia por
                        aquí* means there is a chemist somewhere nearby.

                        This vagueness is a real part of the word and it never transfers to
                        *para*. If you say *vive para el centro*, a Spanish speaker will hear a
                        strange claim about the purpose of her life rather than a rough address.
                        """,
                    ),
                    examples(
                        ex(
                            "vivir",
                            "Vive **por** el centro, creo.",
                            "She lives somewhere around the centre, I think.",
                            note = "An approximate area, deliberately vague.",
                        ),
                        ex(
                            "haber",
                            "¿Hay un cajero **por** aquí?",
                            "Is there a cash machine around here?",
                            note = "*Por aquí* is the ordinary way to say \"around here\".",
                        ),
                        ex(
                            "volver",
                            "Volvimos **para** casa antes de que anocheciera.",
                            "We headed home before dark.",
                            note = "Home is the destination, so it takes *para*.",
                        ),
                    ),
                ),
                bank(
                    pp("¿Hay una farmacia ___ aquí cerca?", POR, "Somewhere in the area — the vague *por*."),
                    pp("Mis primos viven ___ el norte.", POR, "A rough region, not a destination."),
                    pp("Ya es tarde; me voy ___ casa.", PARA, "Home is where the going is aimed."),
                    pp("El gato se escapó ___ la ventana.", POR, "The window is the opening it went through."),
                    pp("Vamos ___ el aeropuerto, que perdemos el vuelo.", PARA, "The airport is the destination."),
                    pp("Viajamos ___ toda España aquel verano.", POR, "All over Spain — the space covered."),
                    pp("Mandé el paquete ___ Barcelona ayer.", PARA, "Barcelona is where the parcel is going."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Every sentence
                    involves movement or location: a route, a space crossed, an approximate area,
                    or a destination. Include at least two items using the vague *por aquí / por
                    allí* sense. Vary which side is correct.
                    """,
                ),
            ),
        ),

        // -- 3. time -----------------------------------------------------------------------------
        LessonDefinition(
            id = "time",
            title = "How long, and by when",
            tagline = "Use one word for stretches of time and rough moments, the other for deadlines.",
            estimatedMinutes = 13,
            scope = porPara,
            segments = listOf(
                teach(
                    "Duration and vagueness",
                    prose(
                        """
                        Time splits the same way space does. **Por** covers a stretch: how long
                        something lasted, or roughly when it happened. *Estudié por dos horas*
                        gives the length. *Por la mañana* gives a rough part of the day rather
                        than a clock time.

                        Spanish often drops the *por* of duration altogether. *Estudié dos horas*
                        is at least as common as *estudié por dos horas*, and in Spain it is the
                        more usual phrasing. Both are correct, so you can recognise the *por*
                        without feeling obliged to produce it.

                        The rough-moment use is not optional in the same way. *Por la mañana*,
                        *por la tarde* and *por la noche* are how you say in the morning, in the
                        afternoon and at night across most of the Spanish-speaking world.
                        """,
                    ),
                    examples(
                        ex(
                            "dormir",
                            "Dormí **por** doce horas seguidas.",
                            "I slept for twelve hours straight.",
                            note = "A stretch of time. *Dormí doce horas* says the same thing.",
                        ),
                        ex(
                            "trabajar",
                            "Trabajo mejor **por** la mañana.",
                            "I work better in the morning.",
                            note = "A rough part of the day, not a clock time.",
                        ),
                        ex(
                            "quedar",
                            "Se quedaron en Roma **por** un mes.",
                            "They stayed in Rome for a month.",
                            note = "How long the stay lasted.",
                        ),
                    ),
                ),
                bank(
                    pp("Estuvimos esperando ___ media hora.", POR, "How long the waiting lasted."),
                    pp("Siempre leo un rato ___ la noche.", POR, "A rough part of the day."),
                    pp("El informe tiene que estar listo ___ el viernes.", PARA, "Friday is the deadline."),
                    pp("Me prestó el coche ___ el fin de semana.", POR, "The length of the loan."),
                    pp("Necesito una respuesta ___ mañana.", PARA, "Tomorrow is the limit the answer is aimed at."),
                    pp("Vivió en Chile ___ tres años.", POR, "The duration of the stay."),
                    pp("Deja el pastel ___ el domingo.", PARA, "Sunday is what the cake is being saved for."),
                    pp("Nos vemos ___ la tarde.", POR, "A vague afternoon, not a fixed hour."),
                ),
                teach(
                    "Deadlines look forward",
                    prose(
                        """
                        **Para** with a time expression names the moment something has to be
                        ready. *Para el viernes* means by Friday, not on Friday and not during
                        Friday. The deadline is the point the work is aimed at, so it is the same
                        forward-looking *para* you met in the first lesson.

                        The distinction survives even when the two look alike. *Lo terminé para
                        las tres* means I had it done by three o'clock. *Lo terminé a las tres*
                        means three o'clock is when I finished. If you want to say something
                        happened at a particular hour, neither preposition is the answer: Spanish
                        uses *a*.
                        """,
                    ),
                    examples(
                        ex(
                            "terminar",
                            "Lo terminé **para** las tres.",
                            "I had it finished by three.",
                            note = "Three o'clock is the limit, not the moment of finishing.",
                        ),
                        ex(
                            "necesitar",
                            "Lo necesito **para** el lunes.",
                            "I need it by Monday.",
                            note = "Monday is the point the delivery is aimed at.",
                        ),
                        ex(
                            "esperar",
                            "Esperé **por** casi una hora.",
                            "I waited for nearly an hour.",
                            note = "A stretch, not a deadline.",
                        ),
                    ),
                ),
                bank(
                    pp("Hay que entregarlo ___ el día quince.", PARA, "The fifteenth is the deadline."),
                    pp("Estudiamos juntos ___ dos semanas antes del examen.", POR, "How long the studying went on."),
                    pp("Este trabajo es ___ el próximo mes.", PARA, "Next month is when it is due."),
                    pp("Cerraron la tienda ___ unos días.", POR, "The length of the closure."),
                    pp("Guarda el vestido ___ la boda.", PARA, "The wedding is the occasion it is being kept for."),
                    pp("Habló ___ veinte minutos sin parar.", POR, "The duration of the talking."),
                    pp("¿Puedes tenerlo ___ esta noche?", PARA, "Tonight is the limit."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Every sentence is
                    about time: a duration, a vague part of the day, or a deadline. Include at
                    least three deadline items and at least two using *por la mañana / tarde /
                    noche*. Vary which side is correct.
                    """,
                ),
            ),
        ),

        // -- 4. purpose --------------------------------------------------------------------------
        LessonDefinition(
            id = "purpose",
            title = "In order to",
            tagline = "Use para before an infinitive whenever you are saying what something is for.",
            estimatedMinutes = 12,
            scope = porPara,
            segments = listOf(
                teach(
                    "Para plus an infinitive",
                    prose(
                        """
                        This is the single most reliable rule in the course. When the next word is
                        an infinitive and you are saying what the action is **for**, the answer is
                        **para**. *Vine para ayudarte* means I came in order to help you. *Sirve
                        para abrir botellas* means it is for opening bottles.

                        English hides this rule, because English usually drops the *in order*.
                        *I came to help you* and *I came in order to help you* are the same
                        sentence, and Spanish makes the second one explicit. If you can insert
                        *in order to* into the English without changing the meaning, Spanish
                        wants *para*.

                        The rule also covers the questions. *¿Para qué?* asks what for, meaning
                        with what purpose. *¿Por qué?* asks why, meaning from what cause. A
                        Spanish speaker will answer the two differently: *¿para qué estudias
                        español?* invites a goal, and *¿por qué estudias español?* invites a
                        reason.
                        """,
                    ),
                    examples(
                        ex(
                            "venir",
                            "Vine **para** ayudarte con la mudanza.",
                            "I came to help you with the move.",
                            note = "Helping is the purpose of coming.",
                        ),
                        ex(
                            "servir",
                            "Este cuchillo sirve **para** cortar pan.",
                            "This knife is for cutting bread.",
                            note = "What the object is for.",
                        ),
                        ex(
                            "abrir",
                            "Abrí la ventana **para** que entrara aire.",
                            "I opened the window so that some air would come in.",
                            note = "*Para que* plus the subjunctive, when the purpose has a different subject.",
                        ),
                    ),
                ),
                bank(
                    pp("Uso esta aplicación ___ organizar mis notas.", PARA, "An infinitive of purpose."),
                    pp("Te escribo ___ pedirte un favor.", PARA, "Asking the favour is the point of writing."),
                    pp("Lo felicité ___ su ascenso.", POR, "Congratulations look back at what happened."),
                    pp("Necesito gafas ___ leer de cerca.", PARA, "What the glasses are for."),
                    pp("Se disculpó ___ haber gritado.", POR, "The apology is about a past action."),
                    pp("Vino ___ despedirse de nosotros.", PARA, "Saying goodbye is the purpose."),
                    pp("No sé ___ qué sirve este botón.", PARA, "*Para qué* asks what something is for."),
                    pp("No me explicó ___ qué llegó tan tarde.", POR, "*Por qué* asks the reason."),
                ),
                teach(
                    "Purpose that is not an infinitive",
                    prose(
                        """
                        The same *para* works in front of a noun when the noun names a use rather
                        than a recipient. *Un vaso para agua* is a water glass, a glass whose
                        purpose is water. *Un vaso de agua* is a glass with water in it. *Pastillas
                        para la tos* are cough sweets, and the cough is what they are aimed at
                        rather than what caused them.

                        That last one catches learners out, because in English *for a cough*
                        sounds like a reason. Ask what the pills are aimed at, and *para* comes
                        out right.
                        """,
                    ),
                    examples(
                        ex(
                            "tomar",
                            "Tomo estas pastillas **para** el dolor de cabeza.",
                            "I take these pills for my headache.",
                            note = "The headache is what the pills are aimed at.",
                        ),
                        ex(
                            "usar",
                            "Es una caja **para** herramientas.",
                            "It is a toolbox.",
                            note = "Tools are the purpose of the box, not its contents right now.",
                        ),
                        ex(
                            "comprar",
                            "Compré una crema **para** las manos.",
                            "I bought a hand cream.",
                            note = "What the cream is for.",
                        ),
                    ),
                ),
                bank(
                    pp("Es un champú ___ el pelo seco.", PARA, "What the shampoo is aimed at."),
                    pp("Me dieron algo ___ la fiebre.", PARA, "The fever is what the medicine targets."),
                    pp("Lo castigaron ___ mentir.", POR, "Punishment looks back at the offence."),
                    pp("Compró una mesa ___ el jardín.", PARA, "Where the table is destined."),
                    pp("Le di las gracias ___ el consejo.", POR, "Gratitude points back at the advice given."),
                    pp("Guardo esta caja ___ los juguetes.", PARA, "The box is for the toys."),
                    pp("Estudia mucho ___ aprobar el examen.", PARA, "Passing is the goal of the studying."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Centre the set on
                    *para* + infinitive and *para* + a noun naming a use, with three or four
                    contrasting items where a backward-looking *por* is correct (thanking,
                    apologising, being punished or congratulated for something already done).
                    """,
                ),
            ),
        ),

        // -- 5. recipient ------------------------------------------------------------------------
        LessonDefinition(
            id = "recipient",
            title = "Who gets it",
            tagline = "Name the person or thing on the receiving end of an action or an object.",
            estimatedMinutes = 12,
            scope = porPara,
            segments = listOf(
                teach(
                    "The person at the end of the arrow",
                    prose(
                        """
                        **Para** names the recipient. A gift, a letter, a message, a portion of
                        food, a favour: whoever ends up with it takes *para*. *Este café es para
                        ti* hands you the coffee. *Traje algo para los niños* says the children
                        are who it is for.

                        The same word covers things that are not people. *Un cargador para el
                        móvil* is a phone charger; the phone is what receives the charge.
                        Anything on the receiving end of the arrow takes *para*, whether it can
                        thank you or not.
                        """,
                    ),
                    examples(
                        ex(
                            "traer",
                            "Traje unos dulces **para** los niños.",
                            "I brought some sweets for the children.",
                            note = "They are the recipients.",
                        ),
                        ex(
                            "escribir",
                            "Escribió una canción **para** su hermana.",
                            "He wrote a song for his sister.",
                            note = "The song is aimed at her.",
                        ),
                        ex(
                            "comprar",
                            "Compré un cargador **para** el móvil.",
                            "I bought a charger for the phone.",
                            note = "The phone receives the charge, so it works like a recipient.",
                        ),
                    ),
                ),
                bank(
                    pp("Esta carta es ___ el director.", PARA, "He is the addressee."),
                    pp("Hice la cena ___ toda la familia.", PARA, "They are who the meal is for."),
                    pp("Lo hago ___ ti, porque me lo pediste.", POR, "You asked, so you are the reason."),
                    pp("Aparté una silla ___ Ana.", PARA, "Ana receives the seat."),
                    pp("Guardo un sitio ___ mi hermano.", PARA, "My brother gets the place."),
                    pp("Se preocupa mucho ___ sus hijos.", POR, "*Preocuparse por* looks back at what worries you."),
                    pp("Trajo flores ___ la anfitriona.", PARA, "She is the recipient."),
                    pp("Compré una funda ___ el portátil.", PARA, "The laptop is what the case is for."),
                ),
                teach(
                    "Doing it for someone, two ways",
                    prose(
                        """
                        Here the two words separate cleanly, and the difference is worth holding
                        on to. *Escribí la carta para Juan* means Juan receives the letter. *La
                        escribí por Juan* means I wrote it in Juan's place, because he could not
                        or would not do it himself.

                        English uses *for* on both sides of that difference and relies on context.
                        Spanish forces you to choose, so decide which one you mean before you
                        open your mouth: is the person **getting** the result, or is the person
                        the one who **should have done** it?
                        """,
                    ),
                    examples(
                        ex(
                            "escribir",
                            "Escribí la carta **para** Juan.",
                            "I wrote the letter for Juan.",
                            note = "Juan is the addressee.",
                        ),
                        ex(
                            "escribir",
                            "Escribí la carta **por** Juan.",
                            "I wrote the letter on Juan's behalf.",
                            note = "It was his job and I stood in for him.",
                        ),
                        ex(
                            "hablar",
                            "Hablo **por** todo el equipo.",
                            "I speak on behalf of the whole team.",
                            note = "Standing in for others is the substitution *por*.",
                        ),
                    ),
                ),
                bank(
                    pp("Fui a la reunión ___ mi compañera, que estaba enferma.", POR, "I went in her place."),
                    pp("Reservé una mesa ___ cuatro personas.", PARA, "The table is for them."),
                    pp("Firma tú ___ mí, que no llego a tiempo.", POR, "Signing in my place."),
                    pp("Preparé una sorpresa ___ mis padres.", PARA, "They receive the surprise."),
                    pp("El abogado habló ___ su cliente.", POR, "He spoke on the client's behalf."),
                    pp("Compramos un regalo ___ los recién casados.", PARA, "They are the recipients."),
                    pp("Dio la cara ___ su amigo.", POR, "He stood up in his friend's place."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Half the items should
                    be plain recipients (*para*) and half should be doing something in someone's
                    place (*por*), with enough context that only one reading survives. Keep the
                    people ordinary and unnamed or named simply.
                    """,
                ),
            ),
        ),

        // -- 6. deadline -------------------------------------------------------------------------
        LessonDefinition(
            id = "deadline",
            title = "Employers, destinations, due dates",
            tagline = "Group the everyday uses of para that all point at something still ahead.",
            estimatedMinutes = 12,
            scope = porPara,
            segments = listOf(
                teach(
                    "Working for, heading for, due by",
                    prose(
                        """
                        Three common uses of **para** share a shape. *Trabajo para una empresa
                        alemana* points your work at the company that employs you. *Este tren es
                        para Bilbao* points the train at Bilbao. *Es para el jueves* points the
                        task at Thursday.

                        Employment is the one worth drilling, because it collides with a use of
                        *por* you already met. *Trabajo para Ana* makes Ana my employer. *Trabajo
                        por Ana* makes Ana absent and me covering her shift. Both are everyday
                        sentences in an office, and the wrong one will be understood as the other.
                        """,
                    ),
                    examples(
                        ex(
                            "trabajar",
                            "Trabaja **para** una empresa alemana.",
                            "She works for a German company.",
                            note = "The company employs her.",
                        ),
                        ex(
                            "ser",
                            "Este autobús es **para** el aeropuerto.",
                            "This bus goes to the airport.",
                            note = "The airport is where it is aimed.",
                        ),
                        ex(
                            "quedar",
                            "Quedamos **para** el jueves.",
                            "We arranged to meet on Thursday.",
                            note = "Thursday is the point the arrangement is aimed at.",
                        ),
                    ),
                ),
                bank(
                    pp("Mi hermana trabaja ___ un banco.", PARA, "The bank employs her."),
                    pp("Hoy trabajo ___ Luis, que está de baja.", POR, "Covering for someone who is away."),
                    pp("Este paquete es ___ el señor Ruiz.", PARA, "He is the addressee."),
                    pp("Lo quiero ___ el lunes sin falta.", PARA, "Monday is the deadline."),
                    pp("Nos multaron ___ aparcar mal.", POR, "The fine looks back at the offence."),
                    pp("¿Este tren es ___ Valencia?", PARA, "Valencia is its destination."),
                    pp("Terminé el trabajo ___ la fecha límite.", PARA, "The deadline is what I finished by."),
                    pp("Le pagaron bien ___ el trabajo.", POR, "Payment in exchange for work done."),
                ),
                teach(
                    "Enough for, ready for",
                    prose(
                        """
                        **Para** also measures sufficiency. *Hay comida para diez* means there is
                        enough food for ten people. *No tengo dinero para eso* means I cannot
                        afford it. The number or the thing after *para* is the demand the supply
                        is being measured against, which is still something ahead of you.

                        The related *estar para* says you are ready for something, or in the mood
                        for it. *No estoy para bromas* means I am in no mood for jokes.
                        """,
                    ),
                    examples(
                        ex(
                            "haber",
                            "Hay comida **para** diez personas.",
                            "There is enough food for ten people.",
                            note = "Ten is the demand the food is measured against.",
                        ),
                        ex(
                            "tener",
                            "No tengo tiempo **para** eso ahora.",
                            "I do not have time for that right now.",
                            note = "The task is what the time would be spent on.",
                        ),
                        ex(
                            "estar",
                            "No estoy **para** bromas hoy.",
                            "I am in no mood for jokes today.",
                            note = "*Estar para* is being ready or in the mood for something.",
                        ),
                    ),
                ),
                bank(
                    pp("No hay sillas ___ todos.", PARA, "Everyone is the demand being measured."),
                    pp("Compré pan suficiente ___ la semana.", PARA, "The week is what the bread has to cover."),
                    pp("Lo felicitaron ___ su cumpleaños.", POR, "Congratulations look back at the occasion."),
                    pp("No estoy ___ discusiones a estas horas.", PARA, "*Estar para* is being in the mood for."),
                    pp("Le dieron un premio ___ su novela.", POR, "The prize is in recognition of the novel."),
                    pp("¿Tienes dinero ___ el billete?", PARA, "The ticket is what the money has to cover."),
                    pp("Está preparado ___ la entrevista.", PARA, "Ready for what is coming."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Draw on employment,
                    destinations, deadlines and sufficiency for the *para* items, and on payment,
                    fines, prizes and covering for an absent colleague for the *por* items.
                    Include one *trabajar por* vs *trabajar para* pair.
                    """,
                ),
            ),
        ),

        // -- 7. standard -------------------------------------------------------------------------
        LessonDefinition(
            id = "standard",
            title = "Considering what it is",
            tagline = "Compare something against the standard you would expect, and give an opinion.",
            estimatedMinutes = 11,
            scope = porPara,
            segments = listOf(
                teach(
                    "Against expectations",
                    prose(
                        """
                        **Para** can set up a comparison with a standard. *Para ser noviembre,
                        hace calor* means that given it is November, it is warm — warmer than
                        November has any right to be. The phrase after *para* is the yardstick,
                        and the sentence says the thing does not match it.

                        The construction is common and it usually carries a note of surprise.
                        *Habla muy bien para un principiante* praises the beginner by measuring
                        him against beginners. *Es muy alto para su edad* measures a child
                        against other children of that age. Nothing about the phrase is
                        backward-looking, which is why it takes *para*.
                        """,
                    ),
                    examples(
                        ex(
                            "hacer",
                            "**Para** ser noviembre, hace mucho calor.",
                            "For November, it is very warm.",
                            note = "November is the standard the weather is failing to match.",
                        ),
                        ex(
                            "hablar",
                            "Habla muy bien **para** un principiante.",
                            "He speaks very well for a beginner.",
                            note = "Beginners are the yardstick.",
                        ),
                        ex(
                            "ser",
                            "Es muy maduro **para** su edad.",
                            "He is very mature for his age.",
                            note = "His age sets the expectation he exceeds.",
                        ),
                    ),
                ),
                bank(
                    pp("La ciudad está tranquila ___ ser lunes.", PARA, "Monday is the standard being compared against."),
                    pp("El piso es barato ___ estar en el centro.", PARA, "Its location sets the expectation."),
                    pp("Nos felicitaron ___ el buen trabajo.", POR, "Praise looks back at the work done."),
                    pp("Cocina muy bien ___ tener quince años.", PARA, "His age is the yardstick."),
                    pp("Lo cambiaron ___ otro modelo.", POR, "An exchange of one thing for another."),
                    pp("Sabe mucho ___ ser tan joven.", PARA, "Youth is the standard she exceeds."),
                    pp("Hace frío ___ estar en agosto.", PARA, "August sets the expectation."),
                ),
                teach(
                    "In my view",
                    prose(
                        """
                        A short, very frequent use: **para mí**, *para ti*, *para nosotros* mean
                        in my opinion, in your view, as far as we are concerned. *Para mí, es la
                        mejor película del año* offers a judgement rather than a fact.

                        Keep it apart from *por mí*, which means as far as I am concerned in a
                        different sense: it grants permission or waives an objection. *Por mí,
                        podéis iros* means I have no objection to you leaving. The two are one
                        letter apart and they do different jobs.
                        """,
                    ),
                    examples(
                        ex(
                            "ser",
                            "**Para** mí, es la mejor película del año.",
                            "In my view it is the best film of the year.",
                            note = "An opinion.",
                        ),
                        ex(
                            "poder",
                            "**Por** mí, podéis iros cuando queráis.",
                            "As far as I am concerned you can leave whenever you like.",
                            note = "Waiving an objection, not offering an opinion.",
                        ),
                        ex(
                            "parecer",
                            "**Para** ella, el problema no es el dinero.",
                            "As she sees it, money is not the problem.",
                            note = "Her view of the matter.",
                        ),
                    ),
                ),
                bank(
                    pp("Este restaurante es, ___ mí, el mejor del barrio.", PARA, "An opinion."),
                    pp("Empezad sin nosotros; ___ mí, no hay problema.", POR, "*Por mí* waives an objection."),
                    pp("La decisión, ___ ellos, ya está tomada.", PARA, "How they see it."),
                    pp("Brindamos ___ los novios.", POR, "A toast looks back at the people honoured."),
                    pp("Toca muy bien ___ un principiante.", PARA, "Beginners are the standard."),
                    pp("Lo dimos ___ imposible.", POR, "*Dar por* treats something as settled."),
                    pp("La reunión, ___ nosotros, puede esperar.", PARA, "Our view of the matter."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Focus on *para* + a
                    standard (*para ser lunes*, *para su edad*, *para un principiante*) and on
                    *para mí* meaning in my view, with two or three *por* items for contrast —
                    including at least one *por mí* meaning I have no objection.
                    """,
                ),
            ),
        ),

        // -- 8. cause ----------------------------------------------------------------------------
        LessonDefinition(
            id = "cause",
            title = "Because of, out of",
            tagline = "Name the cause, the motive or the feeling that produced what happened.",
            estimatedMinutes = 13,
            scope = porPara,
            segments = listOf(
                teach(
                    "The thing that made it happen",
                    prose(
                        """
                        **Por** names a cause. *Cancelaron el vuelo por la niebla* blames the fog.
                        *Lo hizo por miedo* blames fear. Whatever sits after *por* came first and
                        produced what the sentence describes, which is the backward direction from
                        lesson one in its purest form.

                        English marks this with *because of*, *out of*, *on account of* and
                        sometimes a bare *for*. All of them become *por*. The one to watch is the
                        bare *for*: *he was arrested for stealing* is *por robar*, because the
                        stealing came first.

                        Feelings are the most common causes of all. *Por amor*, *por miedo*, *por
                        vergüenza*, *por curiosidad*, *por costumbre* — a feeling that drove an
                        action always takes *por*.
                        """,
                    ),
                    examples(
                        ex(
                            "llegar",
                            "Llegamos tarde **por** el tráfico.",
                            "We arrived late because of the traffic.",
                            note = "The traffic caused the lateness.",
                        ),
                        ex(
                            "hacer",
                            "Lo hizo **por** miedo, no **por** maldad.",
                            "He did it out of fear, not out of malice.",
                            note = "A feeling that drove the action.",
                        ),
                        ex(
                            "cerrar",
                            "Cerraron la carretera **por** la nieve.",
                            "They closed the road because of the snow.",
                            note = "The snow came first.",
                        ),
                    ),
                ),
                bank(
                    pp("Se suspendió el partido ___ la lluvia.", POR, "The rain caused the cancellation."),
                    pp("Lo detuvieron ___ robar en una tienda.", POR, "The theft is the reason for the arrest."),
                    pp("No salí ___ el dolor de cabeza.", POR, "The headache kept me in."),
                    pp("Ahorro ___ comprarme una bici.", PARA, "The bike is the goal of the saving."),
                    pp("Lloraba ___ pura alegría.", POR, "Joy caused the crying."),
                    pp("Se enfadó ___ lo que dijiste.", POR, "Your words came first."),
                    pp("Vino ___ verte, nada más.", PARA, "Seeing you is the purpose of the visit."),
                    pp("Lo conocí ___ casualidad.", POR, "Chance is how it came about."),
                ),
                teach(
                    "Por eso, por lo tanto",
                    prose(
                        """
                        Because *por* carries cause, it heads several of the connectors Spanish
                        uses to move from a reason to a result. *Por eso* means that is why.
                        *Por lo tanto* and *por consiguiente* mean therefore. *Por si acaso*
                        means just in case, and looks back at the risk you are guarding against.

                        Notice how *por qué*, *porque* and *por eso* line up. *¿Por qué no viniste?*
                        asks the cause. *Porque estaba enfermo* gives it. *Por eso no vine* points
                        back at a cause already mentioned. All three are the same backward-looking
                        word, spelled apart by grammar rather than meaning.
                        """,
                    ),
                    examples(
                        ex(
                            "venir",
                            "Estaba enfermo; **por** eso no vine.",
                            "I was ill, and that is why I did not come.",
                            note = "*Por eso* points back at a cause already given.",
                        ),
                        ex(
                            "llevar",
                            "Lleva paraguas **por** si acaso.",
                            "Take an umbrella just in case.",
                            note = "Guarding against a risk you can already see.",
                        ),
                        ex(
                            "cerrar",
                            "Llovía, y **por** lo tanto cerramos temprano.",
                            "It was raining, so we closed early.",
                            note = "*Por lo tanto* moves from cause to result.",
                        ),
                    ),
                ),
                bank(
                    pp("Perdí el tren; ___ eso llegué tarde.", POR, "*Por eso* points back at the cause."),
                    pp("Coge el abrigo ___ si acaso.", POR, "*Por si acaso* guards against a known risk."),
                    pp("Estudia ___ sacar buenas notas.", PARA, "Good marks are the goal."),
                    pp("Nos quedamos en casa ___ el mal tiempo.", POR, "The weather caused it."),
                    pp("Lo dijo ___ envidia.", POR, "Envy drove the remark."),
                    pp("Apunté la dirección ___ no olvidarla.", PARA, "Not forgetting is the purpose."),
                    pp("Se disculparon ___ el retraso.", POR, "The apology is about the delay that happened."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Build the *por* items
                    around causes, motives and feelings (*por el tráfico*, *por miedo*, *por
                    casualidad*), and the contrasting *para* items around goals and purposes, so
                    that the sentence's own logic decides which direction the phrase points.
                    """,
                ),
            ),
        ),

        // -- 9. exchange -------------------------------------------------------------------------
        LessonDefinition(
            id = "exchange",
            title = "Swapping one thing for another",
            tagline = "Cover prices, trades, thanks and standing in for someone with a single idea.",
            estimatedMinutes = 12,
            scope = porPara,
            segments = listOf(
                teach(
                    "Something given, something received",
                    prose(
                        """
                        **Por** marks an exchange. Money for goods, one object for another, work
                        for pay, a favour for thanks: whenever two things trade places, the thing
                        given up takes *por*. *Lo compré por veinte euros* names what the book
                        cost me. *Cambié el azul por el rojo* names what I gave back.

                        Thanking works the same way, which is why *gracias por* never varies.
                        *Gracias por venir*, *gracias por todo*, *gracias por tu paciencia*: the
                        thanks are given in return for something already done. A learner reaching
                        for *gracias para* is hearing the English *thanks for* and translating the
                        preposition rather than the idea.
                        """,
                    ),
                    examples(
                        ex(
                            "comprar",
                            "Lo compré **por** veinte euros.",
                            "I bought it for twenty euros.",
                            note = "Twenty euros is what I gave up.",
                        ),
                        ex(
                            "cambiar",
                            "Cambié el jersey azul **por** el rojo.",
                            "I swapped the blue jumper for the red one.",
                            note = "The blue one is what I gave back.",
                        ),
                        ex(
                            "venir",
                            "Gracias **por** venir tan pronto.",
                            "Thank you for coming so quickly.",
                            note = "The thanks are in return for something already done.",
                        ),
                    ),
                ),
                bank(
                    pp("Vendió el coche ___ tres mil euros.", POR, "The price he received."),
                    pp("Gracias ___ la invitación.", POR, "Thanks are given in return."),
                    pp("Te cambio mi bocadillo ___ el tuyo.", POR, "A straight swap."),
                    pp("Ahorré todo el año ___ este viaje.", PARA, "The trip is what the saving is aimed at."),
                    pp("Me pagan poco ___ tantas horas.", POR, "Pay in exchange for hours worked."),
                    pp("Lo di ___ imposible y me fui.", POR, "*Dar algo por* treats it as settled."),
                    pp("Compramos entradas ___ el concierto.", PARA, "The concert is what the tickets are for."),
                    pp("¿Cuánto te dieron ___ la bici?", POR, "What you received in exchange."),
                ),
                teach(
                    "Standing in, speaking for",
                    prose(
                        """
                        Substitution is an exchange too, and it takes the same *por*. If you do
                        something in someone's place, you have swapped yourself for them. *Firmé
                        por él* means I signed instead of him. *Habla por todos nosotros* means he
                        speaks on our behalf.

                        This is the use that collides most often with *para*, because English uses
                        *for* on both sides. Ask whether the person is receiving the result or
                        being replaced. *Cociné para mi madre* means she eats. *Cociné por mi
                        madre* means she was supposed to cook and I took her place.
                        """,
                    ),
                    examples(
                        ex(
                            "preparar",
                            "Preparé la cena **por** mi madre, que llegó tardísimo.",
                            "I made dinner in my mother's place, since she got in very late.",
                            note = "It was her turn and I took it.",
                        ),
                        ex(
                            "preparar",
                            "Preparé la cena **para** mi madre.",
                            "I made dinner for my mother.",
                            note = "She is the one who eats it.",
                        ),
                        ex(
                            "hablar",
                            "No hables **por** mí, por favor.",
                            "Do not speak for me, please.",
                            note = "Do not take my place in the conversation.",
                        ),
                    ),
                ),
                bank(
                    pp("Yo conduzco ___ ti, que estás cansado.", POR, "Taking your turn at the wheel."),
                    pp("Preparé una tarta ___ tu cumpleaños.", PARA, "The birthday is what the cake is for."),
                    pp("Contestó ___ su hermano pequeño.", POR, "He answered in the boy's place."),
                    pp("Aparté estas revistas ___ ti.", PARA, "You are the one who gets them."),
                    pp("Lo vendieron ___ la mitad de precio.", POR, "The price accepted in exchange."),
                    pp("Recogió el premio ___ su hermano, que no pudo ir.", POR, "He collected it in his brother's place."),
                    pp("Estas herramientas son ___ el taller.", PARA, "Where they are destined."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Build the set around
                    exchange: prices, swaps, pay, thanks, and standing in for someone. Include at
                    least two minimal-pair-style items where a recipient *para* is the correct
                    answer, so the exchange reading has to be ruled out rather than assumed.
                    """,
                ),
            ),
        ),

        // -- 10. means ---------------------------------------------------------------------------
        LessonDefinition(
            id = "means",
            title = "By what means",
            tagline = "Say how something travelled, how it was sent, and who performed the action.",
            estimatedMinutes = 12,
            scope = porPara,
            segments = listOf(
                teach(
                    "The channel it came through",
                    prose(
                        """
                        **Por** names the means: the channel, the medium, the method. *Te llamo
                        por teléfono*, *lo mandé por correo*, *nos enteramos por la radio*, *viajar
                        por avión*. This grows straight out of the route sense in lesson two — a
                        message travels through a channel the way a train travels through a town.

                        English uses *by* here, and *by* is a reliable signal. *By phone*, *by
                        email*, *by post*, *by mistake*: all of them are *por*.
                        """,
                    ),
                    examples(
                        ex(
                            "hablar",
                            "Hablamos **por** teléfono ayer.",
                            "We spoke on the phone yesterday.",
                            note = "The phone is the channel.",
                        ),
                        ex(
                            "enviar",
                            "Te lo envío **por** correo electrónico.",
                            "I will send it to you by email.",
                            note = "Email is the medium.",
                        ),
                        ex(
                            "mandar",
                            "Lo mandaron **por** avión.",
                            "They sent it by air.",
                            note = "The method of transport.",
                        ),
                    ),
                ),
                bank(
                    pp("Me enteré ___ las noticias.", POR, "The news was the channel."),
                    pp("Mándame el archivo ___ correo.", POR, "Post or email as a medium."),
                    pp("Lo dije ___ error, perdona.", POR, "*Por error* is by mistake."),
                    pp("Preparo la presentación ___ el lunes.", PARA, "Monday is the deadline."),
                    pp("Nos comunicamos ___ mensajes.", POR, "Messages are the channel."),
                    pp("Compré el billete ___ internet.", POR, "The internet is how the buying happened."),
                    pp("Este documento es ___ la abogada.", PARA, "She is the recipient."),
                    pp("Se enteró ___ un amigo común.", POR, "The friend was the channel."),
                ),
                teach(
                    "Who did it",
                    prose(
                        """
                        The passive agent is the same idea one step further. In *la novela fue
                        escrita por Cervantes*, Cervantes is the channel the writing came through.
                        Spanish uses the passive far less than English does, but when it appears,
                        the doer always takes **por**.

                        You will meet this constantly in reading rather than in speech: *dirigida
                        por*, *fundado por*, *pintado por*, *organizado por*. Recognise it and you
                        will never be tempted to write *escrito para Cervantes*, which would make
                        him the person the book was written for.
                        """,
                    ),
                    examples(
                        ex(
                            "escribir",
                            "La novela fue escrita **por** una autora chilena.",
                            "The novel was written by a Chilean author.",
                            note = "The doer of a passive verb takes *por*.",
                        ),
                        ex(
                            "dirigir",
                            "La película está dirigida **por** un director joven.",
                            "The film is directed by a young director.",
                            note = "Same pattern, and very common in reviews.",
                        ),
                        ex(
                            "escribir",
                            "Escribió el prólogo **para** una autora chilena.",
                            "He wrote the prologue for a Chilean author.",
                            note = "Now she is the recipient, and the meaning changes entirely.",
                        ),
                    ),
                ),
                bank(
                    pp("El edificio fue diseñado ___ un arquitecto italiano.", POR, "The doer of a passive verb."),
                    pp("La cena fue preparada ___ los alumnos.", POR, "They performed the action."),
                    pp("Escribió una dedicatoria ___ su profesora.", PARA, "She receives the dedication."),
                    pp("El concierto está organizado ___ el ayuntamiento.", POR, "The council is the organiser."),
                    pp("Han reservado esta sala ___ la reunión.", PARA, "The room is for the meeting."),
                    pp("Fue descubierto ___ casualidad.", POR, "Chance is the means."),
                    pp("Nos avisaron ___ megafonía.", POR, "The public address system was the channel."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Cover means and
                    channels (*por teléfono*, *por correo*, *por internet*, *por error*) and the
                    passive agent (*escrito por*, *organizado por*). Include two items where the
                    person after the blank is a recipient rather than a doer, so *para* is right.
                    """,
                ),
            ),
        ),

        // -- 11. rate ----------------------------------------------------------------------------
        LessonDefinition(
            id = "rate",
            title = "So much per unit",
            tagline = "Express frequency, distribution and percentages with a single fixed pattern.",
            estimatedMinutes = 11,
            scope = porPara,
            segments = listOf(
                teach(
                    "Per",
                    prose(
                        """
                        Wherever English says **per**, Spanish says **por**. *Dos veces por
                        semana*, *cien kilómetros por hora*, *veinte euros por persona*, *el
                        cinco por ciento*. There is no competing *para* reading here, so this is
                        the easiest lesson in the course to get right and the easiest to forget
                        under time pressure.

                        The pattern is fixed: a quantity, then *por*, then the unit it is
                        distributed across. It does not change for time units, money, distance or
                        percentages, and the noun after *por* almost always goes without an
                        article. *Por semana*, not *por la semana*.
                        """,
                    ),
                    examples(
                        ex(
                            "correr",
                            "Corre tres veces **por** semana.",
                            "He runs three times a week.",
                            note = "A frequency: the pattern takes no article.",
                        ),
                        ex(
                            "pagar",
                            "Pagamos treinta euros **por** persona.",
                            "We paid thirty euros a head.",
                            note = "The amount distributed across people.",
                        ),
                        ex(
                            "subir",
                            "Los precios subieron un diez **por** ciento.",
                            "Prices rose by ten per cent.",
                            note = "*Por ciento* is the standard way to say per cent.",
                        ),
                    ),
                ),
                bank(
                    pp("Voy al gimnasio dos veces ___ semana.", POR, "A frequency: so many times per week."),
                    pp("El coche iba a ciento veinte kilómetros ___ hora.", POR, "Distance per unit of time."),
                    pp("Salió a diez euros ___ cabeza.", POR, "The cost distributed across people."),
                    pp("Compré la entrada ___ el sábado.", PARA, "Saturday is what the ticket is for."),
                    pp("Solo el veinte ___ ciento contestó.", POR, "*Por ciento* is per cent."),
                    pp("Nos dan una hora ___ día de descanso.", POR, "An amount per day."),
                    pp("Reservé mesa ___ las nueve.", PARA, "Nine is the time the booking is aimed at."),
                    pp("Cobra cincuenta euros ___ clase.", POR, "The rate per lesson."),
                ),
                teach(
                    "Distribution and order",
                    prose(
                        """
                        The same *por* spreads things out one at a time. *Uno por uno* means one
                        by one. *Poco a poco* uses a different preposition, but *paso por paso*,
                        *día por día* and *palabra por palabra* all take *por* to walk through a
                        sequence.

                        A related use divides a group. *Nos repartimos el trabajo por parejas*
                        splits the work into pairs. *Los ordené por fechas* sorted them by date.
                        Here *por* is naming the axis you are cutting along, which is close to the
                        route sense once again.
                        """,
                    ),
                    examples(
                        ex(
                            "revisar",
                            "Lo revisé palabra **por** palabra.",
                            "I checked it word by word.",
                            note = "Walking through a sequence.",
                        ),
                        ex(
                            "entrar",
                            "Entraron uno **por** uno.",
                            "They came in one by one.",
                            note = "The same distributive pattern.",
                        ),
                        ex(
                            "guardar",
                            "Guardé las fotos **por** años.",
                            "I filed the photos by year.",
                            note = "The axis the group is being cut along.",
                        ),
                    ),
                ),
                bank(
                    pp("Los revisamos uno ___ uno.", POR, "One by one — a distributive sequence."),
                    pp("Lo leí línea ___ línea.", POR, "Walking through the text in order."),
                    pp("Guardo la ropa ___ colores.", POR, "The axis the sorting follows."),
                    pp("Estas cajas son ___ el trastero.", PARA, "Where they are destined."),
                    pp("Trabajamos ___ parejas.", POR, "The group divided into pairs."),
                    pp("Necesito los datos ___ el jueves.", PARA, "Thursday is the deadline."),
                    pp("Cuesta dos euros ___ unidad.", POR, "The price per unit."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Centre on rates and
                    distribution (*por semana*, *por hora*, *por persona*, *por ciento*, *uno por
                    uno*), with two or three deadline or recipient items where *para* is correct,
                    so the learner still has to look at the sentence.
                    """,
                ),
            ),
        ),

        // -- 12. por_purpose ---------------------------------------------------------------------
        LessonDefinition(
            id = "por_purpose",
            title = "When por looks forward too",
            tagline = "Handle the goals and errands that take por instead of the para you expect.",
            estimatedMinutes = 14,
            scope = porPara,
            segments = listOf(
                teach(
                    "Going to fetch something",
                    prose(
                        """
                        Here is the overlap that undoes the neat picture. *Voy por pan* means I am
                        going to get some bread. The bread is clearly a goal, and yet the
                        preposition is **por**.

                        The logic still holds if you look closely. *Ir por algo* frames the thing
                        as what is **pulling** you out of the house, the motive for the errand,
                        rather than as a destination you are aimed at. Spain often says *ir a por*
                        instead, which makes the errand reading explicit; Latin America keeps the
                        plain *ir por*. Both are standard in their regions.

                        The same shape appears with other fetching verbs. *Vengo por mis llaves*,
                        *pasé por el pan*, *mandaron por el médico*. If someone is going to
                        collect a thing or a person, expect *por*.
                        """,
                    ),
                    examples(
                        ex(
                            "ir",
                            "Voy **por** pan, vuelvo en cinco minutos.",
                            "I am going to get bread, back in five minutes.",
                            note = "An errand. The bread is what pulls me out.",
                        ),
                        ex(
                            "venir",
                            "Vine **por** mis llaves.",
                            "I came to pick up my keys.",
                            note = "Collecting something already there.",
                        ),
                        ex(
                            "ir",
                            "Voy **para** el hospital.",
                            "I am heading to the hospital.",
                            note = "A destination, so the ordinary *para* of motion.",
                        ),
                    ),
                ),
                bank(
                    pp("Bajo ___ el periódico y subo.", POR, "An errand: fetching the paper."),
                    pp("Pasé ___ ti a las ocho, como quedamos.", POR, "Coming to collect you."),
                    pp("Este autobús va ___ el centro.", PARA, "The centre is its destination."),
                    pp("Mandaron ___ el médico enseguida.", POR, "They sent for the doctor."),
                    pp("Salgo ___ el aeropuerto ahora mismo.", PARA, "The airport is the destination."),
                    pp("Vengo ___ los documentos que dejé aquí.", POR, "Collecting something."),
                    pp("Compré fruta ___ la semana.", PARA, "The week is what the fruit has to cover."),
                    pp("Voy ___ agua, ¿quieres algo?", POR, "Fetching something."),
                ),
                teach(
                    "Causes that look like goals",
                    prose(
                        """
                        A second overlap: *por* with a cause you are working toward. *Luchan por
                        la igualdad*, *trabaja por la paz*, *hizo mucho por este barrio*. Equality
                        and peace are goals in any ordinary sense, and the preposition is still
                        *por*, because Spanish frames them as the motive driving the effort rather
                        than as an endpoint.

                        Finally, *por* plus an infinitive is not a purpose at all. It carries one
                        of two meanings. Backward: *lo castigaron por llegar tarde* gives the
                        offence. Forward, with *estar* or *quedar*: *la casa está por pintar*
                        means the house is still to be painted, not yet done. Neither one is *in
                        order to*, and that job belongs to *para* alone.
                        """,
                    ),
                    examples(
                        ex(
                            "luchar",
                            "Luchan **por** la igualdad.",
                            "They are fighting for equality.",
                            note = "The cause driving the effort.",
                        ),
                        ex(
                            "quedar",
                            "Todavía queda mucho **por** hacer.",
                            "There is still a lot left to do.",
                            note = "*Por* + infinitive: still pending.",
                        ),
                        ex(
                            "hacer",
                            "Lo hizo **para** ayudarnos.",
                            "He did it in order to help us.",
                            note = "The purpose infinitive, which is always *para*.",
                        ),
                    ),
                ),
                bank(
                    pp("Trabajan ___ mejorar el barrio.", PARA, "*Para* + infinitive is the purpose."),
                    pp("Lucharon ___ sus derechos durante años.", POR, "A cause driving the effort."),
                    pp("La cama está ___ hacer todavía.", POR, "*Estar por* + infinitive means still pending."),
                    pp("Me llamó ___ preguntarme una cosa.", PARA, "Asking is the purpose of the call."),
                    pp("Lo multaron ___ conducir sin carné.", POR, "The offence came first."),
                    pp("Queda una hora ___ terminar.", POR, "An hour still left before it is done."),
                    pp("Se sacrificó ___ su familia.", POR, "His family is the motive."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Concentrate on the
                    overlap: errands (*ir por pan*, *venir por las llaves*), causes worked toward
                    (*luchar por*), and *por* + infinitive meaning still-undone (*está por
                    hacer*). Include three items where *para* + infinitive is correct, so the
                    learner has to tell a purpose from a motive rather than guessing *por*.
                    """,
                ),
            ),
        ),

        // -- 13. minimal_pairs -------------------------------------------------------------------
        LessonDefinition(
            id = "minimal_pairs",
            title = "Two sentences, one letter apart",
            tagline = "Read pairs where the preposition carries the entire difference in meaning.",
            estimatedMinutes = 14,
            scope = porPara,
            segments = listOf(
                teach(
                    "The pairs worth memorising",
                    prose(
                        """
                        A handful of sentences change meaning completely on the preposition, and
                        they are the ones a listener will notice. Learn them as pairs.

                        **Trabajo por Ana** — I am covering Ana's shift. **Trabajo para Ana** —
                        Ana is my boss.

                        **Lo hice por ti** — you were the reason I did it. **Lo hice para ti** —
                        you get the result.

                        **Voy por el médico** — I am going to fetch the doctor. **Voy para el
                        médico** — I am on my way to the doctor's.

                        **Compré un regalo por mi hermana** — I bought it in her place, because
                        she could not. **Compré un regalo para mi hermana** — she is getting the
                        present.

                        **Es difícil por su edad** — his age is what makes it hard. **Es difícil
                        para su edad** — it is hard when you measure him against others his age.
                        """,
                    ),
                    examples(
                        ex(
                            "hablar",
                            "Habló **por** el grupo.",
                            "He spoke on behalf of the group.",
                            note = "Substitution: he took the group's place.",
                        ),
                        ex(
                            "hablar",
                            "Habló **para** el grupo.",
                            "He spoke to the group.",
                            note = "The group is the audience receiving the talk.",
                        ),
                        ex(
                            "responder",
                            "Respondió **por** mí.",
                            "He answered in my place.",
                            note = "Compare *respondió para mí*, which is not how Spanish puts it.",
                        ),
                    ),
                ),
                bank(
                    pp("Ana está de baja, así que hoy trabajo ___ ella.", POR, "Covering her shift."),
                    pp("Llevo dos años trabajando ___ esta empresa.", PARA, "The company employs me."),
                    pp("Lo hice ___ ti; me lo pediste tú.", POR, "You asked, so you are the reason."),
                    pp("Lo hice ___ ti; ábrelo cuando llegues a casa.", PARA, "You receive the result."),
                    pp("Salgo ___ el médico; tengo cita a las cinco.", PARA, "Heading to an appointment."),
                    pp("Voy ___ el médico; hay que traerlo enseguida.", POR, "Fetching the doctor."),
                    pp("Compré el regalo ___ mi hermana, que no pudo salir de casa.", POR, "In her place."),
                    pp("Compré el regalo ___ mi hermana; se lo doy mañana.", PARA, "She receives it."),
                ),
                teach(
                    "Reading the rest of the sentence",
                    prose(
                        """
                        In every pair above, the words either side of the blank are identical. The
                        thing that decides the answer is always somewhere else in the sentence:
                        *que no pudo salir* points to substitution, *se lo doy mañana* points to a
                        recipient, *me lo pediste* points to a cause.

                        That is the habit worth taking out of this course. Read past the blank
                        before you choose. A learner who decides on the noun alone will be right
                        about half the time, and half is the score you get from guessing.
                        """,
                    ),
                    examples(
                        ex(
                            "escribir",
                            "Escribí el informe **por** él, que estaba de viaje.",
                            "I wrote the report in his place, since he was away.",
                            note = "*Que estaba de viaje* is what settles it.",
                        ),
                        ex(
                            "escribir",
                            "Escribí el informe **para** él, y se lo mandé el lunes.",
                            "I wrote the report for him and sent it to him on Monday.",
                            note = "*Se lo mandé* shows he is the recipient.",
                        ),
                        ex(
                            "tocar",
                            "Tocó una pieza **para** los invitados.",
                            "She played a piece for the guests.",
                            note = "An audience receives the performance.",
                        ),
                    ),
                ),
                bank(
                    pp("Habló ___ los alumnos durante una hora; todos escuchaban.", PARA, "They were the audience."),
                    pp("Habló ___ los alumnos en la reunión, porque ellos no podían asistir.", POR, "He represented them."),
                    pp("Es un problema ___ su edad; a los ochenta cuesta más.", POR, "His age causes the difficulty."),
                    pp("Está muy ágil ___ su edad.", PARA, "Measured against others his age."),
                    pp("Cociné ___ mi padre, que se había torcido el tobillo.", POR, "I took his turn."),
                    pp("Cociné ___ mi padre; le encantó la sopa.", PARA, "He ate it."),
                    pp("Estudia ___ su familia, que espera mucho de él.", POR, "His family is the motive."),
                    pp("Estudia ___ ser ingeniero.", PARA, "Becoming an engineer is the goal."),
                ),
                cloze(
                    10,
                    """
                    One blank, two options — *por* and *para*, in that order. Every item must be a
                    sentence whose words either side of the blank would allow both answers, with a
                    later clause that settles it (*porque no podía venir*, *se lo doy mañana*, *me
                    lo pidió él*). Pair the items up so the same frame appears with each answer.
                    """,
                ),
            ),
        ),

        // -- 14. fixed_phrases -------------------------------------------------------------------
        LessonDefinition(
            id = "fixed_phrases",
            title = "Set phrases",
            tagline = "Learn the expressions that come as whole units and never take the other word.",
            estimatedMinutes = 12,
            scope = porPara,
            segments = listOf(
                teach(
                    "Expressions built on por",
                    prose(
                        """
                        A large share of everyday Spanish is fixed phrases, and *por* has most of
                        them. They do not need analysing, only learning as units.

                        **por favor** (please) · **por supuesto** (of course) · **por fin** (at
                        last) · **por cierto** (by the way) · **por eso** (that is why) · **por lo
                        menos** (at least) · **por si acaso** (just in case) · **por ahora** (for
                        now) · **por suerte** (luckily) · **por desgracia** (unfortunately) ·
                        **por lo visto** (apparently) · **por casualidad** (by chance) · **por
                        ejemplo** (for example) · **por todas partes** (everywhere) · **por
                        último** (lastly)

                        Several are causes wearing a coat, and a couple are not analysable at all.
                        Either way, none of them accepts *para*.

                        The practice below mixes these phrases with ordinary sentences from earlier
                        lessons, so recognising the phrase is the work. A set of items that were all
                        set phrases could be answered without reading them.
                        """,
                    ),
                    examples(
                        ex(
                            "poder",
                            "¿Puedes repetirlo, **por** favor?",
                            "Could you repeat that, please?",
                            note = "*Por favor* is a fixed unit.",
                        ),
                        ex(
                            "llegar",
                            "**Por** fin llegaste.",
                            "You are here at last.",
                            note = "*Por fin* marks relief that something finally happened.",
                        ),
                        ex(
                            "olvidar",
                            "**Por** cierto, olvidé decírtelo.",
                            "By the way, I forgot to tell you.",
                            note = "A discourse marker, not a cause.",
                        ),
                    ),
                ),
                bank(
                    pp("¿Me pasas la sal, ___ favor?", POR, "*Por favor* — please."),
                    pp("Claro, ___ supuesto que te ayudo.", POR, "*Por supuesto* — of course."),
                    pp("Ayer ___ fin terminamos el proyecto.", POR, "*Por fin* — at last."),
                    pp("Necesito tu respuesta ___ el jueves.", PARA, "An ordinary deadline, not a set phrase."),
                    pp("Llévate el paraguas, ___ si acaso.", POR, "*Por si acaso* — just in case."),
                    pp("Este paquete es ___ ti.", PARA, "An ordinary recipient."),
                    pp("Oye, ___ cierto, ¿has visto a Marta?", POR, "*Por cierto* — by the way."),
                    pp("Lo llamé ___ darle las gracias.", PARA, "*Para* + infinitive: the purpose of the call."),
                ),
                teach(
                    "Expressions built on para",
                    prose(
                        """
                        *Para* has fewer, and they are worth knowing precisely because they are
                        the exceptions to a list dominated by *por*.

                        **para siempre** (forever) · **para nada** (not at all) · **para
                        entonces** (by then) · **para colmo** (to top it all off) · **no es para
                        tanto** (it is not that bad) · **para variar** (for a change, usually
                        sarcastic) · **para que** (so that, with the subjunctive)

                        Watch *para nada*: it intensifies a negative rather than meaning *for
                        nothing*. *No me gustó para nada* means I did not like it at all. If you
                        want to say something was done in vain, the phrase is *en vano* or *para
                        nada* read very differently by context, so prefer *en vano* when that is
                        what you mean.
                        """,
                    ),
                    examples(
                        ex(
                            "quedar",
                            "Se quedó allí **para** siempre.",
                            "He stayed there for good.",
                            note = "*Para siempre* — forever.",
                        ),
                        ex(
                            "gustar",
                            "No me gustó **para** nada.",
                            "I did not like it at all.",
                            note = "*Para nada* intensifies the negative.",
                        ),
                        ex(
                            "empezar",
                            "Y **para** colmo, empezó a llover.",
                            "And to top it all off, it started raining.",
                            note = "*Para colmo* introduces the last straw.",
                        ),
                    ),
                ),
                bank(
                    pp("Se fueron ___ siempre.", PARA, "*Para siempre* — forever."),
                    pp("No me importa ___ nada.", PARA, "*Para nada* intensifies the negative."),
                    pp("Y ___ colmo, perdimos las llaves.", PARA, "*Para colmo* — to top it all off."),
                    pp("No te preocupes, no es ___ tanto.", PARA, "*No es para tanto* — it is not that bad."),
                    pp("Se han mudado, ___ lo visto.", POR, "*Por lo visto* — apparently."),
                    pp("Llegó tarde, ___ variar.", PARA, "*Para variar* — for a change."),
                    pp("Lo encontré ___ casualidad.", POR, "*Por casualidad* — by chance."),
                    pp("Podríamos ir el sábado, ___ ejemplo.", POR, "*Por ejemplo* — for example."),
                ),
                cloze(
                    8,
                    """
                    One blank, two options — *por* and *para*, in that order. Every item must turn
                    on a set phrase from this lesson, embedded in a short natural sentence rather
                    than quoted bare. Mix the *por* expressions with the *para* ones so the answer
                    is never predictable from the lesson's overall lean.
                    """,
                ),
            ),
        ),

        // -- 15. review --------------------------------------------------------------------------
        LessonDefinition(
            id = "review",
            title = "Everything at once",
            tagline = "Work through connected passages where every use in the course is back in play.",
            estimatedMinutes = 15,
            scope = porPara,
            segments = listOf(
                teach(
                    "Putting the whole course in play",
                    prose(
                        """
                        Every lesson so far has told you which use it was about, which makes the
                        choice easier than it will be in conversation. This last sitting takes
                        that scaffolding away. The items below run through connected passages
                        with all fifteen uses mixed together, in the proportions they actually
                        occur.

                        If an item stops you, go back to the question from the first lesson.
                        Does the phrase look backward at a cause, a source, an exchange or a
                        route? Or does it look forward at a goal, a recipient, a destination or a
                        deadline? That question was the whole course, and everything since has
                        been its special cases.

                        Finishing this lesson graduates the course. The sentences you found
                        hardest become flashcards, and *por* and *para* start appearing in your
                        daily reviews and in the contrast drills of your routine.
                        """,
                    ),
                ),
                bank(
                    pp("Salimos ___ el pueblo a las siete de la mañana.", PARA, "The village is the destination."),
                    pp("Tardamos dos horas ___ la niebla.", POR, "The fog caused the delay."),
                    pp("Paramos ___ el camino a desayunar.", POR, "*Por el camino* — along the way."),
                    pp("Compramos algo de comer ___ el viaje.", PARA, "The journey is what the food is for."),
                    pp("Pagamos doce euros ___ los dos.", POR, "The price for both of us."),
                    pp("Llamé a mi madre ___ teléfono desde el coche.", POR, "The phone is the channel."),
                    pp("Le dije que llegaríamos ___ la tarde.", POR, "A rough part of the day."),
                    pp("Ella nos había preparado la casa ___ el fin de semana.", PARA, "The weekend is what it was readied for."),
                ),
                teach(
                    "One more passage",
                    prose(
                        """
                        The second set below is denser and leans on the uses learners lose first:
                        errands, substitution, rates and the set phrases. Read the whole sentence
                        each time before you answer.
                        """,
                    ),
                ),
                bank(
                    pp("Después de dar tres vueltas, ___ fin encontramos aparcamiento.", POR, "*Por fin* — at last."),
                    pp("Bajé ___ el pan mientras ella deshacía las maletas.", POR, "An errand: fetching bread."),
                    pp("La panadería abre solo tres días ___ semana.", POR, "A frequency."),
                    pp("Me atendió una chica que trabajaba allí ___ su tía.", POR, "Covering for her aunt."),
                    pp("Compré también un pastel ___ mi sobrina.", PARA, "She is the recipient."),
                    pp("Tiene de todo ___ ser un pueblo pequeño.", PARA, "The village measured against expectations."),
                    pp("Volví ___ casa antes de que se enfriara.", PARA, "Home is the destination."),
                    pp("Nos quedamos allí ___ una semana entera.", POR, "How long the stay lasted."),
                    pp("Todavía queda mucho ___ ver en la zona.", POR, "*Por* + infinitive: still pending."),
                    pp("Lo dejamos ___ el año que viene.", PARA, "Next year is what it is postponed to."),
                ),
                cloze(
                    12,
                    """
                    A single connected passage of six to eight sentences describing an ordinary
                    day or short trip, with each *por* or *para* blanked in turn as a separate
                    item, in story order. Two options per item — *por* and *para*, in that order.
                    Mix at least five different uses across the passage and keep both answers
                    well represented.
                    """,
                ),
            ),
        ),
    ),
    graduationCards = listOf(
        GraduationCardSpec(
            "Trabajo {{c1::para}} Ana. (Ana is my boss)",
            "I work for Ana — she employs me.",
        ),
        GraduationCardSpec(
            "Hoy trabajo {{c1::por}} Ana, que está de baja. (covering her shift)",
            "Today I am working in Ana's place, since she is off sick.",
        ),
        GraduationCardSpec(
            "Lo hice {{c1::por}} ti; me lo pediste tú. (you were the reason)",
            "I did it because of you — you asked me to.",
        ),
        GraduationCardSpec(
            "Lo hice {{c1::para}} ti; ábrelo mañana. (you get the result)",
            "I made it for you — open it tomorrow.",
        ),
        GraduationCardSpec(
            "Caminamos {{c1::por}} el parque y luego salimos {{c2::para}} casa.",
            "We walked around the park and then set off home.",
        ),
        GraduationCardSpec(
            "Necesito el informe {{c1::para}} el viernes, y lo esperé {{c2::por}} dos semanas.",
            "I need the report by Friday, and I waited for it for two weeks.",
        ),
        GraduationCardSpec(
            "Voy {{c1::por}} pan; vuelvo en cinco minutos. (an errand)",
            "I am going to get bread — back in five minutes.",
        ),
        GraduationCardSpec(
            "Estudia mucho {{c1::para}} aprobar, y lo hace {{c2::por}} su familia.",
            "He studies hard in order to pass, and he does it for his family's sake.",
        ),
        GraduationCardSpec(
            "Corre tres veces {{c1::por}} semana y paga treinta euros {{c2::por}} mes.",
            "He runs three times a week and pays thirty euros a month.",
        ),
        GraduationCardSpec(
            "{{c1::Para}} ser noviembre, hace calor; {{c2::por}} suerte no llueve.",
            "For November it is warm; luckily it is not raining.",
        ),
        GraduationCardSpec(
            "La novela fue escrita {{c1::por}} una autora chilena.",
            "The novel was written by a Chilean author.",
        ),
        GraduationCardSpec(
            "{{c1::Por}} fin llegaste, y {{c2::para}} colmo sin paraguas.",
            "You are here at last, and to top it off without an umbrella.",
        ),
    ),
)

// ---------------------------------------------------------------------------------------------
// Authoring shorthands. This file is content, and ceremony would bury it.
// ---------------------------------------------------------------------------------------------

private const val POR = "por"
private const val PARA = "para"

/**
 * The scope every lesson here shares: no tenses, because there is no paradigm to narrow, and the
 * `por_para` bucket so this course's answers and the routine's contrast cloze train one estimate.
 */
private val porPara = LessonScope(contrastCategory = "por_para")

private fun prose(text: String) = DidacticBlock.Prose(text.trimIndent())
private fun examples(vararg items: AuthoredExample) = DidacticBlock.Examples(items.toList())

/**
 * A worked example. No [AuthoredExample.cell]: the bolded word is the preposition, not a verb form,
 * so there is no morphology here for `CourseCatalogTest` to replay. The infinitive is still named
 * and still checked against the lexicon, which keeps the sentences inside vocabulary the rest of the
 * app knows.
 */
private fun ex(infinitive: String, sentence: String, gloss: String, note: String? = null) =
    AuthoredExample(infinitive, cell = null, sentence = sentence, gloss = gloss, note = note)

private fun part(title: String, from: String) = CoursePart(title, from)
private fun teach(title: String? = null, vararg blocks: DidacticBlock) =
    AuthoredSegment.Didactic(title, blocks.toList())

private fun bank(vararg items: AuthoredChoiceItem) = AuthoredSegment.AuthoredDrill(items.toList())
private fun cloze(count: Int, brief: String) =
    AuthoredSegment.LlmSlot(LlmSlotKind.CLOZE, count, brief.trimIndent())

/**
 * One item. [answer] is the word itself rather than an index, so the authoring reads as the teaching
 * does; the options are always `por` then `para`, which makes the dumped JSON legible and means no
 * item can be answered from a position the learner has memorised.
 */
private fun pp(sentence: String, answer: String, why: String): AuthoredChoiceItem {
    val options = listOf(POR, PARA)
    require(answer in options) { "answer must be por or para: '$answer'" }
    require(sentence.split(ClozeItem.BLANK).size == 2) { "exactly one blank per item: '$sentence'" }
    return AuthoredChoiceItem(sentence, options, options.indexOf(answer), why)
}
