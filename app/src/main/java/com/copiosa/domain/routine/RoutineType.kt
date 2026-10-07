package com.copiosa.domain.routine

/**
 * A named, reusable routine the user can launch from the Routines tab. Persisted as its [name] on a
 * [com.copiosa.data.local.RoutineRunEntity].
 *
 * The routine type owns the whole shape of the session, so the [RoutinePlanner] stays generic: the
 * arousal arc ([bandOrder] + [ascending]), the activation ceiling ([maxArousal] and the soft quota
 * above it), whether a type is pinned to the very end ([closerType]), what synthesis must produce to
 * count as a usable routine ([requiredType]), and the register the generator writes in.
 *
 * The bedtime wind-down de-escalates toward sleep and closes on a [ActivityType.SHORT_TEXT] story; the
 * daytime activation is its mirror image — a low-arousal warm-up ramping to a high-arousal peak, with
 * no closer, since it should hand the learner back to their day at full alertness.
 *
 * [maxArousal] is the tier the routine draws from freely. Above it the cap is soft rather than
 * absolute: a routine may admit up to [highArousalSlots] more activating activities, confined to
 * [highArousalBand]. The bedtime wind-down takes one, in Engage — enough for a genuinely activating
 * drill to open the routine, while the remaining phases still de-escalate toward sleep. Short bedtime
 * routines get no Engage budget at all, so they stay entirely within the cap. Daytime draws freely from
 * every tier, so its quota fields go unused.
 */
enum class RoutineType(
    val maxArousal: ArousalLevel,
    /** The arousal arc: the bands in the order this routine plays them. */
    val bandOrder: List<ArousalBand>,
    /** Within-band ordering by arousal weight — bedtime winds down, daytime builds up. */
    val ascending: Boolean,
    /** The type pinned to the very end of the routine, or null when it needs no closer. */
    val closerType: ActivityType?,
    /** Synthesis fails the run when this type is missing from the assembled plan; null = no such type. */
    val requiredType: ActivityType?,
    /** The generator's persona clause — what kind of session it is preparing. */
    val personaLine: String,
    /** The register every generated target-language sentence is written in. */
    val registerLine: String,
    /** How the user's chosen theme is introduced in the generation request. */
    val themeLabel: String,
    /** What to ask for when the user picked no theme. */
    val themeFallback: String,
    val highArousalSlots: Int = 1,
    val highArousalBand: ArousalBand = ArousalBand.ENGAGE,
) {
    BEDTIME(
        maxArousal = ArousalLevel.MEDIUM,
        bandOrder = listOf(ArousalBand.ENGAGE, ArousalBand.SETTLE, ArousalBand.DRIFT),
        ascending = false,
        closerType = ActivityType.SHORT_TEXT,
        requiredType = ActivityType.SHORT_TEXT,
        personaLine = "a calm bedtime practice routine",
        registerLine = "simple, natural, calming",
        themeLabel = "Tonight's theme",
        themeFallback = "Choose one calming, sleep-friendly theme and weave it through the activities.",
    ),
    DAYTIME(
        maxArousal = ArousalLevel.HIGH,
        bandOrder = listOf(ArousalBand.DRIFT, ArousalBand.SETTLE, ArousalBand.ENGAGE),
        ascending = true,
        closerType = null,
        requiredType = null,
        personaLine = "a brisk daytime activation routine",
        registerLine = "clear, natural, energetic",
        themeLabel = "Today's theme",
        themeFallback = "Choose one vivid, everyday theme and weave it through the activities.",
    ),
    ;

    /** Whether [def] sits above this routine's freely-drawn tier and so needs a quota slot. */
    fun isAboveCap(def: ActivityDefinition): Boolean = def.arousalLevel.ordinal > maxArousal.ordinal

    /** Whether [def] is allowed in this routine at all — the per-activity routine tag. */
    fun admits(def: ActivityDefinition): Boolean = def.routineTypes?.contains(this) ?: true
}
