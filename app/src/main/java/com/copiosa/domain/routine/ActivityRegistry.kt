package com.copiosa.domain.routine

import com.copiosa.domain.routine.activities.AffectivePi
import com.copiosa.domain.routine.activities.BedtimeStory
import com.copiosa.domain.routine.activities.CognateHunt
import com.copiosa.domain.routine.activities.ContrastCloze
import com.copiosa.domain.routine.activities.GuessThenFlip
import com.copiosa.domain.routine.activities.MultipleChoiceCloze
import com.copiosa.domain.routine.activities.NarrowReading
import com.copiosa.domain.routine.activities.RapidFireListening
import com.copiosa.domain.routine.activities.RecognitionCards
import com.copiosa.domain.routine.activities.ReferentialPi
import com.copiosa.domain.routine.activities.SerialStory
import com.copiosa.domain.routine.activities.SwipeSort

/**
 * The single list of every routine activity. Order matters for the prompt: the system prompt's
 * activity-rules block is emitted in registry order. To add an activity: write its
 * [ActivityDescriptor] (+ content class in RoutinePlan.kt and renderer in ui/routines/activities/),
 * then register it here — the planner, prompt builder, synthesizer, and status screen pick it up
 * from the registry.
 */
object ActivityRegistry {
    val all: List<ActivityDescriptor> = listOf(
        GuessThenFlip,
        ReferentialPi,
        ContrastCloze,
        NarrowReading,
        AffectivePi,
        BedtimeStory,
        RecognitionCards,
        MultipleChoiceCloze,
        SwipeSort,
        CognateHunt,
        SerialStory,
        RapidFireListening,
    )

    val byType: Map<ActivityType, ActivityDescriptor> = all.associateBy { it.type }

    operator fun get(type: ActivityType): ActivityDescriptor = byType.getValue(type)
}
