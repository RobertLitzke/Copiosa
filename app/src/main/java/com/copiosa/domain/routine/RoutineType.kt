package com.copiosa.domain.routine

/**
 * A named, reusable routine the user can launch from the Routines tab. For now only the bedtime
 * wind-down; the [RoutinePlanner] holds each type's phase-band selection function. Persisted as its
 * [name] on a [com.copiosa.data.local.RoutineRunEntity].
 *
 * [maxArousal] caps how activating an activity may be: the planner drops any [ActivityDefinition] above
 * it, so the bedtime wind-down can never draw a [ArousalLevel.HIGH] activity (e.g. typed production).
 */
enum class RoutineType(val maxArousal: ArousalLevel) {
    BEDTIME(ArousalLevel.MEDIUM),
}
