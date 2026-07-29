package com.copiosa.domain.routine

/**
 * A named, reusable routine the user can launch from the Routines tab. For now only the bedtime
 * wind-down; the [RoutinePlanner] holds each type's phase-band selection function. Persisted as its
 * [name] on a [com.copiosa.data.local.RoutineRunEntity].
 *
 * [maxArousal] is the tier the routine draws from freely. Above it the cap is soft rather than
 * absolute: a routine may admit up to [highArousalSlots] more activating activities, confined to
 * [highArousalBand]. The bedtime wind-down takes one, in Engage — enough for a genuinely activating
 * drill to open the routine, while the remaining phases still de-escalate toward sleep. Short
 * routines get no Engage budget at all, so they stay entirely within the cap.
 */
enum class RoutineType(
    val maxArousal: ArousalLevel,
    val highArousalSlots: Int = 1,
    val highArousalBand: ArousalBand = ArousalBand.ENGAGE,
) {
    BEDTIME(ArousalLevel.MEDIUM),
    ;

    /** Whether [def] sits above this routine's freely-drawn tier and so needs a quota slot. */
    fun isAboveCap(def: ActivityDefinition): Boolean = def.arousalLevel.ordinal > maxArousal.ordinal
}
