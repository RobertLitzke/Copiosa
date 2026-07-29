package com.copiosa.domain.quiz

import kotlinx.serialization.Serializable

/**
 * The pre-seeded answer set for one phrase cue, as the LLM returns it: the [correct] native-language
 * translation plus four [distractors] — native translations of things that sound similar or would be
 * said at a similar moment but mean something different. Serialized into quiz_cues.optionsJson; the
 * session builder shows [correct] plus a random three of the four distractors.
 */
@Serializable
data class PhraseOptions(val correct: String, val distractors: List<String>)

/**
 * One "phrases heard in a setting" rapid-fire scenario — the generic mechanism behind Restaurant and
 * future scenarios (register, airport/hotel, greetings). [type] is the phrase [CueType] the pool is
 * keyed by; [settingPrompt] is the situation description injected into the generation prompt. Adding a
 * scenario is one [CueType] entry plus one [PhraseScenario] in [PhraseScenarios].
 */
data class PhraseScenario(
    val type: CueType,
    val settingPrompt: String,
)

/** The registry of phrase scenarios; the single place to register new ones. */
object PhraseScenarios {
    val RESTAURANT = PhraseScenario(
        type = CueType.RESTAURANT,
        settingPrompt = "short things a diner HEARS from the waiter, bartender, or host at a " +
            "restaurant — greeting and seating, taking the order, offering items, checking in during " +
            "the meal, the bill, payment and tipping. Vary the phrasing, emphasis, and degree of " +
            "formality across many close permutations of the same intents. Do NOT name specific foods " +
            "or drinks beyond very generic ones (water, bread, spice).",
    )

    val ALL: List<PhraseScenario> = listOf(RESTAURANT)

    fun forType(type: CueType): PhraseScenario? = ALL.firstOrNull { it.type == type }
}
