package com.copiosa.data.settings

import com.copiosa.domain.model.Cefr
import com.copiosa.domain.model.Persona
import com.copiosa.domain.model.ProviderId
import com.copiosa.domain.model.ReadingFont
import com.copiosa.domain.model.ReadingTextSize
import com.copiosa.domain.model.ThemeCountry
import com.copiosa.domain.model.ThemeMode
import com.copiosa.domain.prompt.PromptBuilder
import com.copiosa.domain.srs.SpacedRepetitionScheduler

/** Non-secret user settings. API keys are stored separately (encrypted). */
data class AppSettings(
    val provider: ProviderId = ProviderId.GEMINI,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val openRouterModel: String = DEFAULT_OPENROUTER_MODEL,
    val nativeLanguage: String = "en",
    val targetLanguage: String = "es",
    val defaultLevel: Cefr = Cefr.B2,
    val defaultCountry: String = "Mexico",
    /** Editable conversation system-prompt template (supports placeholders). */
    val corePrompt: String = PromptBuilder.DEFAULT_CORE_PROMPT,
    /** Extra instructions appended to the core prompt; placeholders are substituted here too. */
    val customInstructions: String = "",
    /** BCP-47 tag driving the app's own UI language; follows native language by default. */
    val appLanguage: String = "en",
    /** Light/dark theme preference; SYSTEM follows the OS setting. */
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Visual persona skin (chat layout, fonts, chrome); UNSTYLED = stock look. */
    val persona: Persona = Persona.UNSTYLED,
    /** Country palette/motif for the theme system; visual only, independent of [defaultCountry]. */
    val themeCountry: ThemeCountry = ThemeCountry.NONE,
    /** Typeface for reading text (chat replies, definitions). */
    val readingFont: ReadingFont = ReadingFont.SERIF,
    /** Size of reading text. */
    val readingTextSize: ReadingTextSize = ReadingTextSize.MEDIUM,
    /** Supertonic speaker id (0-based) for generated audio; auditioned in Settings. */
    val ttsVoiceSid: Int = 0,
    /** Playback speed for generated audio (1.0 = normal); clamped to [TTS_SPEED_MIN]..[TTS_SPEED_MAX]. */
    val ttsSpeed: Float = 1.0f,
    /** Days after playback that playlist audio is auto-deleted by maintenance; 0 = keep forever. */
    val autoDeletePlayedAfterDays: Int = 14,
    /**
     * FSRS target recall probability for this language's cards: the share of reviews you want to get
     * right. Higher means shorter intervals and more reviews. Clamped to
     * [SpacedRepetitionScheduler.MIN_RETENTION]..[SpacedRepetitionScheduler.MAX_RETENTION].
     */
    val desiredRetention: Double = SpacedRepetitionScheduler.DEFAULT_RETENTION,
    /**
     * Whether background maintenance may spend LLM calls for this profile (topic suggestions,
     * auto-retrying failed chats). Local-only upkeep runs either way.
     */
    val backgroundLlmTasks: Boolean = true,
    /**
     * The conjugation drill's setup for this profile, as serialized
     * [com.copiosa.domain.conjugation.ConjugationDrillConfig]. Null until the learner opens the drill
     * — the screen then seeds a default from [defaultCountry], since vosotros is only worth drilling
     * in Spain.
     */
    val conjugationDrillConfigJson: String? = null,
    /**
     * Daily study-time goal in minutes for this profile. Drives the home tracker's target line;
     * days that meet or beat it are highlighted. Clamped to [DAILY_GOAL_MIN]..[DAILY_GOAL_MAX].
     */
    val dailyGoalMinutes: Int = DEFAULT_DAILY_GOAL_MINUTES,
    /**
     * Whether background maintenance may post a "cards are due" reminder notification for this
     * profile (at most ~daily). Off by default — turning it on prompts for notification permission.
     */
    val reviewReminders: Boolean = false,
    /**
     * Whether the book reader translates ahead automatically while reading (staying a couple of
     * sections past the reading position). Off = translation only advances via the explicit
     * "continue translating" control or a book's background slow-translate flag.
     */
    val bookAutoReadAhead: Boolean = true,
    /**
     * Whether the reader's "N of M sections translated" banner has been dismissed. Once waved away
     * it stays gone for good — "Continue translating" remains in the reader's overflow menu.
     */
    val bookFrontierHintDismissed: Boolean = false,
) {
    /** The model string to send for [p]. */
    fun modelFor(p: ProviderId): String = when (p) {
        ProviderId.GEMINI -> geminiModel
        ProviderId.OPENROUTER -> openRouterModel
    }

    /** The model string to send for the currently selected provider. */
    val activeModel: String get() = modelFor(provider)

    companion object {
        // Speech-speed slider bounds: 50%–150%, defaulting to 100%.
        const val TTS_SPEED_MIN = 0.5f
        const val TTS_SPEED_MAX = 1.5f

        // Home-tracker daily study goal: bounds for the slider, defaulting to 10 minutes.
        const val DAILY_GOAL_MIN = 5
        const val DAILY_GOAL_MAX = 60
        const val DEFAULT_DAILY_GOAL_MINUTES = 10

        const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"
        // OpenRouter is free-text in the UI; this is just a cheap, stable starting default.
        const val DEFAULT_OPENROUTER_MODEL = "openai/gpt-4o-mini"

        val GEMINI_MODELS = listOf("gemini-3.5-flash", "gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash")

        /**
         * Human-readable model name for chips/pills, e.g. "gemini-3.5-flash" -> "Gemini 3.5 Flash".
         * OpenRouter ids are `vendor/model`, which the prettifier would mangle, so they're shown raw.
         */
        fun displayName(model: String): String {
            if ('/' in model) return model
            val parts = model.split('-', '.')
            return buildString {
                parts.forEachIndexed { i, part ->
                    val isNum = part.isNotEmpty() && part.all { it.isDigit() }
                    val prevNum = i > 0 && parts[i - 1].all { it.isDigit() }
                    if (i > 0) append(if (isNum && prevNum) "." else " ")
                    append(part.replaceFirstChar { it.uppercase() })
                }
            }
        }
    }
}
